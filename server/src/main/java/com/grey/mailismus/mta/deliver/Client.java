/*
 * Copyright 2010-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.grey.base.utils.IP;
import com.grey.base.utils.TSAP;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.ByteChars;
import com.grey.base.sasl.SaslEntity;
import com.grey.base.ExceptionUtils;
import com.grey.logging.Logger.LEVEL;
import com.grey.naf.reactor.CM_Client;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.TimerNAF;
import com.grey.naf.reactor.config.SSLConfig;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.dns.resolver.engine.ResolverAnswer;
import com.grey.naf.dns.resolver.engine.ResourceData;

import com.grey.mailismus.Task;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.deliver.client.ConnectionConfig;
import com.grey.mailismus.mta.deliver.client.SmtpMessage;
import com.grey.mailismus.mta.deliver.client.SmtpRelay;
import com.grey.mailismus.mta.deliver.client.SmtpResponseDescriptor;
import com.grey.mailismus.mta.deliver.client.SmtpSender;
import com.grey.mailismus.errors.MailismusException;

/**
 * Each instance of this class handles one SMTP connection, and can be reused.
 * This is a single-threaded class which runs in the context of a Dispatcher.
 */
class Client
	extends CM_Client
	implements ResolverDNS.Client,
		TimerNAF.Handler
{
	private static final String LOG_PREFIX = "SMTP-Client";

	private enum PROTO_STATE {S_DISCON, S_CONN, S_READY, S_AUTH, S_STLS, S_HELO, S_EHLO, S_MAILFROM, S_MAILTO, S_DATA, S_MAILBODY,
		S_QUIT, S_RESET, S_END}
	private enum PROTO_EVENT {E_CONNECTED, E_DISCONNECTED, E_REPLY, E_LOCALERROR, E_DISCONNECT, E_SSL}
	private enum PROTO_ACTION {A_CONNECT, A_DISCONNECT, A_HELO, A_EHLO, A_MAILFROM, A_MAILTO, A_DATA, A_MAILBODY,
		A_QUIT, A_RESET, A_ENDMESSAGE, A_STARTSESSION, A_ENDSESSION, A_LOGIN, A_STLS}

	private static final byte OPEN_ANGLE = '<';
	private static final byte CLOSE_ANGLE = '>';
	private static final ByteChars SMTPREQ_MAILFROM = new ByteChars(Protocol.CMDREQ_MAILFROM).append(OPEN_ANGLE);
	private static final ByteChars SMTPREQ_MAILTO = new ByteChars(Protocol.CMDREQ_MAILTO).append(OPEN_ANGLE);

	private static final ByteChars FAILMSG_TMT = new ByteChars("SMTP session timed out");
	private static final ByteChars FAILMSG_NOSSL = new ByteChars("Remote MTA doesn't support SSL");
	private static final ByteChars FAILMSG_NORECIPS = new ByteChars("No valid recipients specified");
	private static final ByteChars FAILMSG_LOCAL = new ByteChars("Local message-handling issue");

	private static final int TMRTYPE_SESSIONTMT = 1;
	private static final int TMRTYPE_DISCON = 2;

	private static final byte S2_DNSWAIT = 1 << 0;
	private static final byte S2_REPLYCONTD = 1 << 1;
	private static final byte S2_DISCARD = 1 << 2; //discarding remainder of an excessively long reply (we're only interested in initial part)
	private static final byte S2_SENT_DATACMD = 1 << 3;
	private static final byte S2_DOMAIN_ERR = 1 << 4; //apply error status to entire domain, not just a particular message recipient
	private static final byte S2_SERVER_STLS = 1 << 5; //the server has advertised STARTTLS capability
	private static final byte S2_ABORT = 1 << 6;
	private static final byte S2_CNXLIMIT = (byte)(1 << 7); //this is 8-bit, but overflows Byte.MAX_VALUE

	private final List<ResourceData> dnsInfo = new ArrayList<>();
	private final List<SmtpResponseDescriptor> recipientStatus = new ArrayList<>();
	private final TSAP remote_tsap_buf = new TSAP();
	private final SharedFields shared;
	private SmtpMessage smtpMessage; //the current message being sent
	private SmtpSender smtpSender;
	private SmtpRelay active_relay;
	private ConnectionConfig conncfg; //config to apply to current connection
	private TSAP remote_tsap;
	private PROTO_STATE pstate;
	private byte state2; //secondary-state, qualifying some of the pstate phases
	private TimerNAF tmr_exit;
	private TimerNAF tmr_sesstmt;
	private long alt_tmtprotocol; //if non-zero, this overrides ConnectionConfig.tmtprotocol - used to set longer timeout for DATA phase
	private int mxptr; //indicates which dnsInfo.rrlist node we're currently connecting/connected to - only valid if dnsInfo non-empty
	private int recip_id; //indicates which recipient we're currently awaiting a response for xxx can it be replaced by size of recipStatus list?
	private int recips_sent; //how many recips we've already sent to server - will run ahead of recip_id in pipelining mode
	private int okrecips; //number of recipients accepted by server
	private int dataWait;
	private SmtpResponseDescriptor replyDescriptor; //xxx does this need to be a global?
	private short disconnect_status;
	private int pipe_cap; //max pipeline for current connection - 1 means pipelining not enabled
	private int pipe_count; //number of requests in current pipelined send
	private SaslEntity.MECH auth_method;
	private byte auth_step;
	private int msgcnt;
	private int cnxid; //increments with each incarnation - useful for distinguishing Transcript logs
	private String pfx_log;

	public String getLogID() {return pfx_log;}

	private void setFlag(byte f, boolean b) {if (b) {setFlag(f);} else {clearFlag(f);}}
	private void setFlag(byte f) {state2 |= f;}
	private void clearFlag(byte f) {state2 &= (byte) ~f;}
	private boolean isFlagSet(byte f) {return ((state2 & f) == f);}

	@Override
	protected SSLConfig getSSLConfig() {return (active_relay == null ? conncfg.getAnonSSL() : active_relay.getSslConfig());}

	Client(SharedFields shared, Dispatcher dsptch) {
		super(dsptch, shared.getBufferGenerator(), shared.getBufferGenerator());
		pfx_log = LOG_PREFIX+": ";
		this.shared = shared;
	}

	public void startConnection(SmtpMessage msg, SmtpSender sender, Relay relay) throws IOException {
		initConnection();
		smtpMessage = msg;
		smtpSender = sender;
		active_relay = relay;
		issueAction(PROTO_ACTION.A_CONNECT, PROTO_STATE.S_CONN);
	}

	public boolean stop() {
		setFlag(S2_ABORT);
		if (pstate == PROTO_STATE.S_DISCON) return (tmr_exit == null); // we're already completely stopped
		issueDisconnect(0, "Forcibly halted", null);
		return false;
	}

	private ConnectionConfig getConnectionConfig(int remote_ip) {
		for (ConnectionConfig cnxcfg : shared.getRemotesConfig()) {
			for (IP.Subnet ipnet : cnxcfg.getIpNets()) {
				if (ipnet.isMember(remote_ip)) {
					return cnxcfg;
				}
			}
		}
		return shared.getDefaultConfig();
	}

	private void transitionState(PROTO_STATE newstate) {
		pstate = newstate;
	}

	@Override
	public void ioReceived(ByteArrayRef rcvdata) throws IOException {
		if (pstate == PROTO_STATE.S_DISCON) return; //this method can be called in a loop, so skip it after a disconnect
		if (shared.getTranscript() != null) shared.getTranscript().data_in(pfx_log, rcvdata, getSystemTime());
		alt_tmtprotocol = 0;
		eventRaised(PROTO_EVENT.E_REPLY, rcvdata, null);
	}

	@Override
	public void ioDisconnected(CharSequence diagnostic) {
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, diagnostic);
	}

	private PROTO_STATE issueDisconnect(int statuscode, CharSequence diagnostic, ByteArrayRef failmsg) {
		if (pstate == PROTO_STATE.S_DISCON) return pstate;
		CharSequence discmsg = "Disconnect";

		if (shared.getTranscript() != null) {
			// We will transcript this at the actual point of closing the connection.
			// The POP3 client does this more cleanly, as it doesn't finalise the message until it transcripts it.
			if (diagnostic != null) {
				shared.getDisconnectMsgBuf().setLength(0);
				shared.getDisconnectMsgBuf().append(discmsg).append(" - ").append(diagnostic);
				discmsg = shared.getDisconnectMsgBuf();
			}
		}
		if (pstate == PROTO_STATE.S_RESET) statuscode = 0; // failed on transition to new message, so don't assign the blame to its recips
		disconnect_status = (short)statuscode;
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECT, failmsg, discmsg);
		return pstate;
	}

	@Override
	public void timerIndication(TimerNAF tmr, Dispatcher d) {
		switch (tmr.getType())
		{
		case TMRTYPE_DISCON:
			tmr_exit = null;
			disconnect();
			break;

		case TMRTYPE_SESSIONTMT:
			tmr_sesstmt = null;
			issueDisconnect(Protocol.REPLYCODE_TMPERR_CONN, "Timeout", FAILMSG_TMT);
			break;

		default:
			getLogger().error(pfx_log+": Unexpected timer-type="+tmr.getType());
			break;
		}
	}

	private void dnsLookup(boolean as_host) throws IOException {
		mxptr = 0;
		dnsInfo.clear();
		setFlag(S2_DNSWAIT);
		CharSequence domain = smtpMessage.getRecipients().get(0).getDomain();
		ResolverAnswer answer;
		if (as_host) {
			answer = shared.getDnsResolver().resolveHostname(domain, this, null, 0);
		} else {
			answer = shared.getDnsResolver().resolveMailDomain(domain, this, null, 0);
		}
		if (answer != null) dnsResolved(getDispatcher(), answer, null);
	}

	@Override
	public void dnsResolved(Dispatcher d, ResolverAnswer answer, Object callerparam) {
		try {
			handleDnsResult(answer);
		} catch (Throwable ex) {
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed on DNS response - "+answer);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed to process DNS response - "+ExceptionUtils.summary(ex));
		}
	}

	private void handleDnsResult(ResolverAnswer answer) throws UnknownHostException {
		clearFlag(S2_DNSWAIT);
		int statuscode = 0;
		CharSequence diagnostic = null;

		if (conncfg.isFallbackMX2A()) {
			if (answer.result == ResolverAnswer.STATUS.NODOMAIN
					&& answer.qtype == ResolverDNS.QTYPE_MX) {
				try {
					dnsLookup(true);
					return;
				} catch (Exception ex) {
					getLogger().log(LEVEL.ERR, ex, false, pfx_log+" failed on DNS-A lookup");
					answer.result = ResolverAnswer.STATUS.BADNAME;
				}
			}
		}

		switch (answer.result)
		{
		case OK:
			if (answer.qtype == ResolverDNS.QTYPE_MX) {
				for (int idx = 0; idx != answer.size(); idx++) {
					dnsInfo.add(answer.getMX(idx));
				}
			} else {
				dnsInfo.add(answer.getA());
			}
			connect(dnsInfo.get(0).getIP());
			return;
		case NODOMAIN:
		case BADNAME:
			statuscode = Protocol.REPLYCODE_PERMERR_ADDR;
			break;
		default:
			statuscode = Protocol.REPLYCODE_TMPERR_LOCAL;
			break;
		}

		if (getLogger().isActive(LEVEL.TRC) || shared.getTranscript() != null) {
			shared.getDisconnectMsgBuf().setLength(0);
			shared.getDisconnectMsgBuf().append("DNS=").append(answer.result);
			diagnostic = shared.getDisconnectMsgBuf();
		}
		connectionFailed(statuscode, diagnostic, null);
	}

	@Override
	public void eventError(Throwable ex) {
		eventErrorIndication(ex, null);
	}

	@Override
	public void eventError(TimerNAF tmr, Dispatcher d, Throwable ex) {
		eventErrorIndication(ex, tmr);
	}

	// error already logged by Dispatcher
	private void eventErrorIndication(Throwable ex, TimerNAF tmr) {
		raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "State="+pstate+" - "+ExceptionUtils.summary(ex));
	}

	private void issueConnect() throws IOException {
		if (active_relay != null) remote_tsap = active_relay.getAddress();

		if (remote_tsap != null) {
			connect();
			return;
		}
		dnsLookup(false);
	}

	// This is only called as the result of a DNS lookup on the destination domain, and is the only route via
	// which DNS lookups lead to the connect() method below.
	private void connect(int remote_ip) throws UnknownHostException {
		remote_tsap = remote_tsap_buf;
		remote_tsap.set(remote_ip, Protocol.TCP_PORT, getLogger().isActive(LEVEL.TRC2) || (shared.getTranscript() != null));
		connect();
	}

	// This is the only route via which ChannelMonitor.connect() gets called
	private void connect() throws UnknownHostException {
		conncfg = getConnectionConfig(remote_tsap.ip); //update from initial default or previous IP
		if (!shared.incrementServerConnections(remote_tsap.ip, conncfg)) {
			if (++mxptr < dnsInfo.size()) {
				connect(dnsInfo.get(mxptr).getIP());
				return;
			}
			setFlag((byte)(S2_ABORT | S2_CNXLIMIT)); //set ABORT too, for sake of setRecipientStatus()
			raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, null);
			return;
		}

		try {
			connect(remote_tsap.sockaddr);
		} catch (Throwable ex) {
			connectionFailed(0, "connect-error", ex);
		}
	}

	@Override
	protected void connected(boolean success, CharSequence diag, Throwable exconn) throws IOException {
		if (isFlagSet(S2_ABORT)) return; //we must be waiting for exit timer - will close the connection then
		if (!success) {
			connectionFailed(0, diag==null?"connect-fail":diag, exconn);
			return;
		}
		LEVEL lvl = LEVEL.TRC2;
		if (getLogger().isActive(lvl) || (shared.getTranscript() != null)) {
			TSAP local_tsap = TSAP.get(getLocalIP(), getLocalPort(), shared.getTmpTSAP(), true);
			if (getLogger().isActive(lvl)) {
				StringBuilder sb = shared.getTmpSB(true);
				sb.append(pfx_log).append(" connected to ");
				recordConnection(sb, local_tsap);
				getLogger().log(lvl, sb);
			}
			if (shared.getTranscript() != null) {
				StringBuilder sb = shared.getTmpSB(true);
				shared.getTranscript().connection_out(pfx_log, local_tsap.dotted_ip, local_tsap.port,
												remote_tsap.dotted_ip, remote_tsap.port, getSystemTime(),
												peerDescription(sb), usingSSL());
			}
		}
		eventRaised(PROTO_EVENT.E_CONNECTED, null, null);
	}

	// statuscode zero means that a TCP connection attempt has failed (with exconn giving the reason, and that we should
	// simply move onto the next known IP for our destination domain.
	// If statuscode is non-zero, then we're giving up on this destination domain (for now anyway, not necessarily a perm
	// error) and 'diagnostic' gives the reason.
	// statuscode zero therefore also means that remote_tsap is non-null, as we have attempted a connection.
	private void connectionFailed(int statuscode, CharSequence diagnostic, Throwable exconn) throws UnknownHostException {
		StringBuilder sb = shared.getTmpSB(true);

		if (statuscode == 0) {
			if (shared.getTranscript() != null) {
				StringBuilder sb2 = shared.getTmpSB2(true);
				shared.getTranscript().connection_out(pfx_log, null, 0, remote_tsap.dotted_ip, remote_tsap.port,
						getSystemTime(), peerDescription(sb2), usingSSL());
			}
			LEVEL lvl = (MailismusException.isError(exconn) ? LEVEL.WARN : LEVEL.TRC2);
			if (getLogger().isActive(lvl)) {
				sb.append(pfx_log).append(" failed to connect to ");
				recordConnection(sb, null);
				if (diagnostic != null) sb.append(" - ").append(diagnostic);
				if (exconn == null) {
					getLogger().log(lvl, sb);
				} else {
					getLogger().log(lvl, exconn, lvl == LEVEL.WARN, sb);
				}
			}

			// try next-preference MX relay
			if (++mxptr < dnsInfo.size()) {
				shared.decrementServerConnections(remote_tsap.ip, conncfg);
				connect(dnsInfo.get(mxptr).getIP());
				return;
			}

			// no more IPs left to try - this session has now definitively failed to connect
			statuscode = Protocol.REPLYCODE_TMPERR_CONN;
			sb.setLength(0);
			sb.append("tried MX-IPs=").append(dnsInfo.size());
			diagnostic = sb;
		}

		// Apply error to entire domain, so that we don't keep looking up impossible domains (for perm err) or making a thousand rapid-fire
		// connections to a valid domain which is currently down or struggling.
		disconnect_status = (short)statuscode;
		setFlag(S2_DOMAIN_ERR);
		LEVEL lvl = LEVEL.TRC;
		StringBuilder sbfail = shared.getTmpSB2(true);
		peerDescription(sbfail);

		if (getLogger().isActive(lvl) || (shared.getTranscript() != null)) {
			StringBuilder sbdisc = shared.getDisconnectMsgBuf();
			if (diagnostic == sbdisc) {
				sb.setLength(0);
				sb.append(sbdisc);
				diagnostic = sb;
			}
			sbdisc.setLength(0);
			sbdisc.append("Cannot connect to ").append(sbfail).append(" for msgid=").append(smtpMessage.getMessageId());
			if (diagnostic != null) sbdisc.append(" - ").append(diagnostic);
			diagnostic = sbdisc;

			if (getLogger().isActive(lvl)) {
				sb.setLength(0);
				sb.append(pfx_log).append(' ').append(sbdisc);
				getLogger().log(lvl, sb);
			}
		}
		shared.getFailMsgBuffer2().populate("Cannot connect to ").append(sbfail);
		if (exconn != null) shared.getFailMsgBuffer2().append(" - ").append(exconn.toString());
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, shared.getFailMsgBuffer2(), diagnostic);
	}

	// discmsg is a Transcript-friendly reason for the disconnect and failmsg is the provisional NDR diagnostic, which may
	// consist of a reject response from the remote server or a locally generated problem description (provisional because
	// we don't decide here whether any failure is transient or final).
	private void endConnection(CharSequence discmsg, ByteArrayRef failmsg) {
		LEVEL lvl = LEVEL.TRC2;
		if (getLogger().isActive(lvl)) {
			StringBuilder sb = shared.getTmpSB(true);
			sb.append(pfx_log).append(" ending with state=").append(pstate).append("/0x").append(Integer.toHexString(state2));
			sb.append(", remote=").append(remote_tsap).append("/dns=").append(dnsInfo.size());
			sb.append(", msgcnt=").append(msgcnt);
			if (discmsg != null) sb.append(" - reason=").append(discmsg);
			if (failmsg != null) sb.append(" - diagnostic=").append(shared.getTmpBC().populateBytes(failmsg));
			getLogger().log(lvl, sb);
		}
		if (tmr_sesstmt != null) {
			tmr_sesstmt.cancel();
			tmr_sesstmt = null;
		}
		if (pstate == PROTO_STATE.S_DISCON) return; // shutdown events criss-crossing each other - that's ok

		if (pstate == PROTO_STATE.S_HELO && disconnect_status == 0) {
			// We've obviously reacted to the rejection of an EHLO by falling back to HELO, but the subsequent disconnect indicates that the
			// server really didn't like us at all, and it's not just the EHLO/HELO issue, so treat the EHLO reply status as the disconnect
			// reason.
			// Even if the server has disconnected because it's genuinely unable to handle EHLO, that's still worth giving up on, because
			// (a) EHLO support is RFC-mandatory and (b) if it not only rejects EHLO but then hangs up without waiting for any more commands,
			// this server is genuinely impossible to deal with.
			// If the disconnection is unintentional on the server's part and due to some other issues, then (a) is still a good enough reason
			// to give up, and since it's impossible for us to know whether the disconnect is intentional or not, the combination of no
			// EHLO support and an inopportune disconnect is irrecoverable anyway. Message delivery can fail, this one has now failed!
			disconnect_status = replyDescriptor.smtpStatus();
			String msg = "Disconnected due to EHLO rejection";
			if (discmsg != null) msg += " - "+discmsg;
			discmsg = msg;
		}

		try {
			if (failmsg == null && discmsg != null) failmsg = shared.getFailMsgBuffer().populate(discmsg);
			SmtpResponseDescriptor rsp = new SmtpResponseDescriptor(disconnect_status, null, null);
			setRecipientStatus(-1, rsp);
		} catch (Exception ex) {
			getLogger().log(LEVEL.WARN, ex, false, pfx_log+" failed to set final recipients status");
		}
		if (remote_tsap != null && !isFlagSet(S2_CNXLIMIT)) shared.decrementServerConnections(remote_tsap.ip, conncfg);
		remote_tsap_buf.clear();

		if (isFlagSet(S2_DNSWAIT)) {
			try {
				shared.getDnsResolver().cancel(this);
			} catch (Exception ex) {
				getLogger().log(LEVEL.INFO, ex, false, pfx_log+" failed to cancel DNS ops");
			}
		}
		clearFlag(S2_DNSWAIT);
		dnsInfo.clear();

		// don't call disconnect() till next Dispatcher callback, to prevent reentrancy issues
		if (shared.getTranscript() != null && discmsg != null) shared.getTranscript().event(pfx_log, discmsg, getSystemTime());
		long delay = (isFlagSet(S2_ABORT) ? 0 : conncfg.getDelayChannelClose().toMillis());
		dataWait = 0;
		transitionState(PROTO_STATE.S_DISCON);
		tmr_exit = getDispatcher().setTimer(delay, TMRTYPE_DISCON, this);
	}

	// this eliminate the Throws declaration for events that are known not to throw
	private PROTO_STATE raiseSafeEvent(PROTO_EVENT evt, ByteArrayRef rspdata, CharSequence discmsg) {
		try {
			eventRaised(evt, rspdata, discmsg);
		} catch (Throwable ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed to issue event="+evt);
			endConnection("Failed to issue event="+evt+" - "+ExceptionUtils.summary(ex), null);
		}
		return pstate;
	}

	private PROTO_STATE eventRaised(PROTO_EVENT evt, ByteArrayRef rspdata, CharSequence discmsg) throws IOException {
		switch (evt)
		{
		case E_CONNECTED:
			transitionState(PROTO_STATE.S_READY);
			dataWait++; //waiting for greeting
			break;

		case E_DISCONNECTED:
			endConnection(discmsg, rspdata);
			break;

		case E_DISCONNECT:
			// this is an action rather than an event, but include it here so we can wrap it in raiseSafeEvent()
			issueAction(PROTO_ACTION.A_DISCONNECT, null, 0, discmsg, rspdata);
			break;

		case E_REPLY:
			// Note that if we act on the start of a reply before we've seen the end of it, there is an infinitesmal chance that we will send
			// our next request prematurely, and look like a slammer. I say infinitesmal because it's almost certain that the entire reply is
			// already in transit even if we've only seen part of it so far, but setting a larger rcvbufsiz would mean this never happens anyway.
			// The largest reply I've seen so far is Hotmail's 308-byte greeting.
			boolean discardThis = isFlagSet(S2_DISCARD); //discard this chunk of data?
			setFlag(S2_DISCARD, rspdata.byteAt(rspdata.size() - 1) != '\n'); //discard next received chunk?
			if (!discardThis) handleReply(rspdata);
			break;

		case E_SSL:
			if (shared.getTranscript() != null) shared.getTranscript().event(pfx_log, "Switched to SSL mode", getSystemTime());
			initMessage();
			pipe_cap = 1; //we will go through EHLO response again
			issueAction(PROTO_ACTION.A_EHLO, PROTO_STATE.S_EHLO);
			break;

		case E_LOCALERROR:
			issueDisconnect(Protocol.REPLYCODE_TMPERR_LOCAL, "Local Error - "+discmsg, null);
			break;

		default:
			getLogger().error(pfx_log+": Unrecognised event="+evt);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised event="+evt);
			break;
		}
		if (pstate == PROTO_STATE.S_DISCON) dataWait = 0; //this check should be redundant, but belt and braces

		if (dataWait == 0) {
			if (tmr_sesstmt != null) {
				tmr_sesstmt.cancel();
				tmr_sesstmt = null;
			}
			if (pstate != PROTO_STATE.S_STLS) getReader().endReceive();
		} else {
			long tmt = (alt_tmtprotocol == 0 ? conncfg.getIdleTimeout().toMillis() : alt_tmtprotocol);
			if (tmr_sesstmt == null) {
				// we're in a state that requires the timer, so if it doesn't exist, that's because it's not created yet - create now
				tmr_sesstmt = getDispatcher().setTimer(tmt, TMRTYPE_SESSIONTMT, this);
			} else {
				if (tmt != tmr_sesstmt.getInterval()) {
					tmr_sesstmt.reset(tmt);
				} else {
					if (tmr_sesstmt.age(getDispatcher()) > Task.MIN_RESET_PERIOD) tmr_sesstmt.reset();
				}
			}
			getReader().receiveDelimited((byte)'\n');
		}
		return pstate;
	}

	private PROTO_STATE handleReply(ByteArrayRef rspdata) throws IOException {
		// We don't expect continued replies for any command except EHLO, but they are always legal, so if we do receive a continued reply
		// in response to anything else then we discard the leading lines and wait for the final one, taking its reply code as the
		// definitive one. CORRECTION!! AOL sends a multi-line Greeting, so just as well we handle continued replies anywhere.
		setFlag(S2_REPLYCONTD, rspdata.byteAt(Protocol.REPLY_CODELEN) == Protocol.REPLY_CONTD);
		replyDescriptor = SmtpResponseDescriptor.parse(rspdata, false); //xxx need flag for enhanced-status
		if (isFlagSet(S2_REPLYCONTD)) {
			if (pstate != PROTO_STATE.S_EHLO) return pstate;
		} else {
			dataWait--;
		}
		short okreply = Protocol.REPLYCODE_OK;

		switch (pstate)
		{
		case S_READY:
			okreply = Protocol.REPLYCODE_READY;
			if (conncfg.isSayHelo()) return issueAction(PROTO_ACTION.A_HELO, PROTO_STATE.S_HELO, okreply, null, rspdata);
			issueAction(PROTO_ACTION.A_EHLO, PROTO_STATE.S_EHLO, okreply, null, rspdata);
			break;

		case S_RESET:
			issueAction(PROTO_ACTION.A_MAILFROM, PROTO_STATE.S_MAILFROM, okreply, null, rspdata);
			break;

		case S_HELO:
			issueAction(PROTO_ACTION.A_STARTSESSION, null, okreply, null, rspdata);
			break;

		case S_EHLO:
			if (replyDescriptor.smtpStatus() != okreply) {
				if (conncfg.isFallbackHelo()) return issueAction(PROTO_ACTION.A_HELO, PROTO_STATE.S_HELO);
			} else {
				if (matchesExtension(rspdata, Protocol.EXT_PIPELINE, false)) {
					pipe_cap = conncfg.getMaxPipeline();
				} else if (matchesExtension(rspdata, Protocol.EXT_STLS, false)) {
					setFlag(S2_SERVER_STLS);
				} else if (matchesExtension(rspdata, Protocol.EXT_AUTH, false)
						|| (active_relay.isAuthCompat() && matchesExtension(rspdata, Protocol.EXT_AUTH_COMPAT, true))) {
					if (active_relay != null && active_relay.isAuthRequired() && auth_method == null) {
						// loop through the advertised SASL mechanisms and use the first one we support
						int lmt = rspdata.limit();
						int off = rspdata.offset();
						while (off != lmt) {
							int off2 = off;
							while (rspdata.buffer()[off2] > ' ') off2++;
							shared.getTmpLightBC().set(rspdata.buffer(), off, off2-off);
							auth_method = shared.getAuthTypesSupported().get(shared.getTmpLightBC());
							//server might advertise EXTERNAL anyway without meaning it unless in SSL mode, so keep scanning for better option
							if (auth_method != null && auth_method != SaslEntity.MECH.EXTERNAL) break;
							off = off2;
							while (rspdata.buffer()[off] == ' ') off++;
						}
					}
				}
			}
			if (!isFlagSet(S2_REPLYCONTD)) issueAction(PROTO_ACTION.A_STARTSESSION, null, okreply, null, rspdata);
			break;

		case S_MAILFROM:
			issueAction(PROTO_ACTION.A_MAILTO, PROTO_STATE.S_MAILTO, okreply, null, rspdata);
			break;

		case S_MAILTO:
			//xxx Need to handle this and 252  if (replyDescriptor.smtpStatus() == Protocol.REPLYCODE_RECIPMOVING) reply_status = okreply;
			setRecipientStatus(recip_id++, replyDescriptor);
			okreply = 0; //an error response at this stage merely invalidates the current recipient, so reply status has now been processed
			if (recip_id == smtpMessage.getRecipients().size()) {
				if (okrecips == 0) return issueDisconnect(0, "No valid recipients", FAILMSG_NORECIPS);
				issueAction(PROTO_ACTION.A_DATA, PROTO_STATE.S_DATA, okreply, null, rspdata);
				break;
			}
			issueAction(PROTO_ACTION.A_MAILTO, PROTO_STATE.S_MAILTO, okreply, null, rspdata);
			break;

		case S_DATA:
			okreply = Protocol.REPLYCODE_DATA;
			issueAction(PROTO_ACTION.A_MAILBODY, PROTO_STATE.S_MAILBODY, okreply, null, rspdata);
			break;

		case S_MAILBODY:
			//this will set disconnect_status if reply status is Fail
			issueAction(PROTO_ACTION.A_ENDMESSAGE, null, okreply, null, rspdata);
			break;

		case S_QUIT:
			//this action exists purely to process the QUIT reply
			okreply = Protocol.REPLYCODE_BYE;
			issueAction(PROTO_ACTION.A_ENDSESSION, PROTO_STATE.S_END, okreply, null, rspdata);
			break;

		case S_STLS:
			startSSL();
			break;

		case S_AUTH:
			if (replyDescriptor.smtpStatus() != Protocol.REPLYCODE_AUTH_OK && replyDescriptor.smtpStatus() != Protocol.REPLYCODE_AUTH_CONTD) {
				return issueDisconnect(replyDescriptor.smtpStatus(), "Authentication failed", rspdata);
			}
			sendAuth(rspdata);
			break;

		default:
			// this is an internal bug whereby we're missing a state label
			getLogger().error(pfx_log+": Unrecognised state="+pstate);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised state="+pstate);
			break;
		}
		return pstate;
	}

	private PROTO_STATE issueAction(PROTO_ACTION action, PROTO_STATE newstate, int okreply, CharSequence discmsg, ByteArrayRef rspdata) throws IOException {
		if (okreply != 0 && replyDescriptor.smtpStatus() != okreply) {
			//note that okreply will already have been reset in state S_MAILTO
			LEVEL lvl = LEVEL.TRC;
			if (getLogger().isActive(lvl)) {
				int len = rspdata.size();
				while (rspdata.buffer()[rspdata.offset()+len-1] < ' ') len--; //strip trailing CRLF
				StringBuilder sb = shared.getTmpSB(true);
				sb.append(pfx_log).append(" rejected in state=").append(pstate).append(" - ");
				peerDescription(sb);
				sb.append(" replied: ").append(shared.getTmpLightBC().set(rspdata.buffer(), rspdata.offset(), len));
				getLogger().log(lvl, sb);
			}
			return issueDisconnect(replyDescriptor.smtpStatus(), "Server rejection", rspdata);
		}
		if (newstate != null) transitionState(newstate);
		boolean endpipe = (pipe_count == 0 && dataWait != 0); //changed state, but don't send any more until all pipelined commands are acked
		ByteBuffer reqbuf;

		switch (action)
		{
		case A_CONNECT:
			issueConnect();
			break;

		case A_HELO:
			auth_method = (active_relay == null ? null : active_relay.getAuthOverride());
			reqbuf = shared.getHeloBuffer(conncfg, false);
			transmit(reqbuf);
			break;

		case A_EHLO:
			auth_method = (active_relay == null ? null : active_relay.getAuthOverride());
			reqbuf = shared.getHeloBuffer(conncfg, true);
			transmit(reqbuf);
			break;

		case A_STARTSESSION:
			SSLConfig activessl = getSSLConfig();
			if (activessl != null && !usingSSL()) {
				if (isFlagSet(S2_SERVER_STLS)) {
					return issueAction(PROTO_ACTION.A_STLS, PROTO_STATE.S_STLS);
				}
				if (activessl.isMandatory()) {
					return issueDisconnect(Protocol.REPLYCODE_NOSSL, "Server doesn't support required SSL mode", FAILMSG_NOSSL);
				}
			}
			if (auth_method != null) {
				return issueAction(PROTO_ACTION.A_LOGIN, PROTO_STATE.S_AUTH);
			}
			issueAction(PROTO_ACTION.A_MAILFROM, PROTO_STATE.S_MAILFROM);
			break;

		case A_MAILFROM: //all recips members refer to same message, so they have a common Sender
			endpipe = sendPipelinedRequest(SMTPREQ_MAILFROM, false, smtpMessage.getSender(), null, true);
			if (!endpipe) issueAction(PROTO_ACTION.A_MAILTO, null);
			break;

		case A_MAILTO:
			while (!endpipe) {
				if (recips_sent == smtpMessage.getRecipients().size()) {
					issueAction(PROTO_ACTION.A_DATA, null);
					break;
				}
				SmtpMessage.Recipient recip = smtpMessage.getRecipients().get(recips_sent);
				endpipe = sendPipelinedRequest(SMTPREQ_MAILTO, false, recip.getMailbox(), recip.getDomain(), true);
				recips_sent++;
			}
			break;

		case A_DATA:
			if (isFlagSet(S2_SENT_DATACMD)) break; //in case we pipelined it before receiving all recip responses
			if (pipe_count != 0) {
				//in pipelined send-ahead mode - forego canned ByteBuffer, to piggyback reply on the buffered pipeline
				sendPipelinedRequest(Protocol.CMDREQ_DATA, true, null, null, false);
			} else {
				transmit(shared.getSmtpRequestData());
			}
			setFlag(S2_SENT_DATACMD);
			break;

		case A_MAILBODY:
			Object msgdata = smtpMessage.getData().get();
			Supplier<String> dataErrMsg = () -> null;
			long msgbytes = 0;
			try {
				if (msgdata instanceof Path) {
					Path p = (Path)msgdata;
					dataErrMsg = () -> (Files.exists(p) ? null : "Spool file missing");
					msgbytes = Files.size(p);
					getWriter().transmit(p);
				} else if (msgdata instanceof byte[]) {
					byte[] b = (byte[])msgdata;
					msgbytes = b.length;
					getWriter().transmit(b);
				} else {
					dataErrMsg = () -> "Invalid message data supplied - "+(msgdata == null ? null : msgdata.getClass().getName());
				}
			} catch (IOException ex) {
				if (dataErrMsg.get() == null)
					throw ex; //prob some temporary comms issue
			}
			if (dataErrMsg.get() != null)
				return issueDisconnect(Protocol.REPLYCODE_PERMERR_MISC, dataErrMsg.get(), FAILMSG_LOCAL);

			transmit(shared.getSmtpRequestEOM());
			alt_tmtprotocol = calculateMaxTime(msgbytes, conncfg.getMinRateData());
			if (alt_tmtprotocol < conncfg.getIdleTimeout().toMillis()) alt_tmtprotocol = 0;

			if (shared.getTranscript() != null) {
				StringBuilder sb = shared.getTmpSB(true);
				sb.append("Sent message-body octets=").append(msgbytes).append(" for msgid=");
				sb.append(smtpMessage.getMessageId());
				shared.getTranscript().event(pfx_log, sb, getSystemTime());
			}
			break;

		case A_ENDMESSAGE:
			smtpMessage = smtpSender.messageCompleted(smtpMessage, ++msgcnt);
			if (smtpMessage == null) {
				return issueAction(PROTO_ACTION.A_QUIT, PROTO_STATE.S_QUIT);
			}
			initMessage();
			issueAction(PROTO_ACTION.A_RESET, PROTO_STATE.S_RESET);
			break;

		case A_QUIT:
			if (conncfg.isSendQuit()) transmit(shared.getSmtpRequestQuit());
			if (!conncfg.isAwaitQuit()) issueDisconnect(0, "A_QUIT", null);
			break;

		case A_RESET:
			transmit(shared.getSmtpRequestReset());
			break;

		case A_ENDSESSION:
			issueDisconnect(0, "A_ENDSESSION", null);
			break;

		case A_DISCONNECT:
			endConnection(discmsg, rspdata);
			break;

		case A_STLS:
			transmit(shared.getSmtpRequestSTLS());
			break;

		case A_LOGIN:
			auth_step = 0;
			sendAuth(null);
			break;

		default:
			getLogger().error(pfx_log+": Unrecognised action="+action);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised action="+action);
			break;
		}
		return pstate;
	}

	private PROTO_STATE issueAction(PROTO_ACTION action, PROTO_STATE newstate) throws IOException {
		return issueAction(action, newstate, 0, null, null);
	}

	@Override
	protected void startedSSL() throws IOException {
		if (pstate == PROTO_STATE.S_DISCON) return; //we are about to close the connection
		eventRaised(PROTO_EVENT.E_SSL, null, null);
	}

	@Override
	protected void disconnectLingerDone(boolean ok, CharSequence info, Throwable ex) {
		if (shared.getTranscript() == null) return;
		StringBuilder sb = shared.getTmpSB(true);
		sb.append("Disconnect linger ");
		if (ok) {
			sb.append("completed");
		} else {
			sb.append("failed");
			if (info != null) sb.append(" - ").append(info);
			if (ex != null) sb.append(" - ").append(ex);
		}
		shared.getTranscript().event(pfx_log, sb, getSystemTime());
	}

	private PROTO_STATE sendAuth(ByteArrayRef rspdata) throws IOException {
		ByteChars rspbuf = shared.getTmpBC().clear();
		boolean auth_done = false;
		int step = auth_step++;

		if (auth_method == SaslEntity.MECH.PLAIN) {
			int finalstep = (active_relay.isAuthInitialResponse() ? 1 : 2);
			auth_done = (step == finalstep);
			if (step == 0) {
				rspbuf.append(Protocol.CMDREQ_SASL_PLAIN);
				if (active_relay.isAuthInitialResponse()) {
					rspbuf.append(' ');
					shared.getSaslPlain().init();
					shared.getSaslPlain().setResponse(null, active_relay.getUsername(), active_relay.getPassword(), rspbuf);
				}
			} else if (!auth_done) {
				shared.getSaslPlain().init();
				shared.getSaslPlain().setResponse(null, active_relay.getUsername(), active_relay.getPassword(), rspbuf);
			}
		} else if (auth_method == SaslEntity.MECH.EXTERNAL) {
			// we send a zero-length response (whether initial or not), to assume the derived authorization ID
			int finalstep = (active_relay.isAuthInitialResponse() ? 1 : 2);
			auth_done = (step == finalstep);
			if (step == 0) {
				rspbuf.append(Protocol.CMDREQ_SASL_EXTERNAL);
				if (active_relay.isAuthInitialResponse()) {
					rspbuf.append(' ').append(Protocol.AUTH_EMPTY);
				}
			} else if (!auth_done) {
				shared.getSaslExternal().init();
				shared.getSaslExternal().setResponse(null, rspbuf);
			}
		} else if (auth_method == SaslEntity.MECH.CRAM_MD5) {
			if (step == 0) {
				rspbuf.append(Protocol.CMDREQ_SASL_CMD5);
			} else if (step == 1) {
				rspdata.advance(Protocol.AUTH_CHALLENGE.length()); //advance past prefix
				rspdata.incrementSize(-Protocol.EOL.length()); //strip CRLF
				shared.getSaslCramMD5().setResponse(active_relay.getUsername(), ByteChars.valueOf(active_relay.getPassword()), rspdata, rspbuf);
			} else {
				auth_done = true;
			}
		} else {
			getLogger().error(pfx_log+": Missing case for authtype="+auth_method);
			return raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Missing case for authtype="+auth_method);
		}

		if (auth_done) {
			return issueAction(PROTO_ACTION.A_MAILFROM, PROTO_STATE.S_MAILFROM);
		}
		rspbuf.append(Protocol.EOL_BC);
		transmit(rspbuf);
		return pstate;
	}

	private void setRecipientStatus(int idx, SmtpResponseDescriptor reply) {
		if (idx == -1) {
			// apply this status to all recipients
			if (smtpMessage == null || smtpMessage.getRecipients() == null)
				return; //is probably an error condition, so worth checking if we are initialised
			for (int idx2 = 0; idx2 != smtpMessage.getRecipients().size(); idx2++) {
				setRecipientStatus(idx2, reply);
			}
			return;
		}
		SmtpResponseDescriptor prevStatus = (idx >= recipientStatus.size() ? null : recipientStatus.get(idx));

		if (prevStatus != null) {
			//overwriting an earlier status
			if (reply.smtpStatus() > prevStatus.smtpStatus()) {
				// this ensures that perm errors override preliminary temp errors, which in turn override preliminary success
				if (reply.smtpStatus() != Protocol.REPLYCODE_OK && prevStatus.smtpStatus() == Protocol.REPLYCODE_OK) {
					okrecips--; //retract an earlier success
				}
				recipientStatus.set(idx, reply);
			}
		} else {
			//appending new status - idx is presumably equal to recipientStatus.size() but don't bother checking
			if (reply.smtpStatus() == Protocol.REPLYCODE_OK) okrecips++;
			recipientStatus.add(reply);
		}
		smtpSender.recipientCompleted(smtpMessage, msgcnt, idx, reply, remote_tsap, isFlagSet(S2_ABORT));
	}

	// NB: We can use shared.pipebuf because the pipeline is always built up and sent within one callback
	private boolean sendPipelinedRequest(ByteChars cmd, boolean flush, CharSequence addr,
			CharSequence domain, boolean close_brace) throws IOException {
		ByteChars rspbuf = shared.getPipelineBuffer();
		if (pipe_count == 0) rspbuf.clear();
		rspbuf.append(cmd);

		if (addr != null) {
			rspbuf.append(addr);
			if (domain != null) rspbuf.append(EmailAddress.DLM_DOM).append(domain);
		}
		if (close_brace) rspbuf.append(CLOSE_ANGLE);
		rspbuf.append(Protocol.EOL_BC);
		pipe_count++;

		if (flush || (pipe_count == pipe_cap)) {
			dataWait = dataWait + pipe_count - 1; //subtract one because transmit() will also increment it
			transmit(rspbuf);
			pipe_count = 0;
		}
		return (pipe_count == 0);
	}

	private void transmit(ByteChars data) throws IOException {
		ByteBuffer buf = shared.encodeData(data);
		transmit(buf);
	}

	private void transmit(ByteBuffer xmtbuf) throws IOException {
		if (shared.getTranscript() != null && pstate != PROTO_STATE.S_MAILBODY) {
			shared.getTranscript().data_out(pfx_log, xmtbuf, 0, getSystemTime());
		}
		xmtbuf.position(0);
		getWriter().transmit(xmtbuf);
		dataWait++;
	}

	private boolean matchesExtension(ByteArrayRef data, char[] cmd, boolean with_equals) {
		int off = Protocol.REPLY_CODELEN + 1; //+1 for the hyphen or space following the "250"
		int len = data.size() - off - Protocol.EOL.length();
		if (len < cmd.length) return false;
		byte[] buf = data.buffer();
		off += data.offset();

		for (int idx = 0; idx != cmd.length; idx++) {
			if (Character.toUpperCase(buf[off++]) != cmd[idx]) return false;
		}
		boolean matches = (with_equals ? true : buf[off] <= ' ');

		if (matches) {
			while (buf[off] == ' ') off++; //bound to run into EOL before end-of-buffer, so this is safe
			data.advance(off - data.offset());
			data.incrementSize(-Protocol.EOL.length());
		}
		return matches;
	}

	private void initConnection() {
		cnxid++;
		pfx_log = LOG_PREFIX+"/E"+getCMID()+"-"+cnxid;
		initChannelMonitor();
		initMessage();
		smtpMessage = null;
		dnsInfo.clear();
		conncfg = shared.getDefaultConfig();
		pipe_cap = 1;
		remote_tsap = null;
		active_relay = null;
		msgcnt = 0;
		pstate = PROTO_STATE.S_DISCON;
		disconnect_status = 0;
		dataWait = 0;
		alt_tmtprotocol = 0;
		mxptr = 0;
	}

	private void initMessage() {
		state2 = 0;
		recip_id = 0;
		recips_sent = 0;
		okrecips = 0;
		pipe_count = 0;
		recipientStatus.clear();
	}

	public short getDomainError() {
		if (!isFlagSet(S2_DOMAIN_ERR)) return 0;
		return recipientStatus.get(0).smtpStatus();
	}

	private void recordConnection(StringBuilder sb, TSAP local_tsap) {
		sb.append(remote_tsap.dotted_ip).append(':').append(remote_tsap.port);
		if (local_tsap != null) sb.append(" on ").append(local_tsap.dotted_ip).append(':').append(local_tsap.port);
		sb.append(" for ");
		peerDescription(sb);
		sb.append(" with msgid=").append(smtpMessage.getMessageId());
		sb.append(", recips=").append(smtpMessage.getRecipients().size());
	}

	private CharSequence peerDescription(StringBuilder sb) {
		if (smtpMessage == null)
			return "nullpeer";
		String dlm = "";
		if (smtpMessage.getRecipients() != null && !smtpMessage.getRecipients().isEmpty()) {
			sb.append("domain=").append(smtpMessage.getRecipients().get(0).getDomain());
			dlm = "/";
		}
		if (active_relay != null) {
			sb.append(dlm).append("relay=").append(active_relay.getName()).append('=').append(active_relay.getAddress());
		}
		return sb;
	}

	// given a minimum bits per second, calculate the max time expected to send this many bytes (in milliseconds)
	private static long calculateMaxTime(long numBytes, long minBPS) {
		return (numBytes * minBPS) / (8_000); 
	}

	@Override
	public StringBuilder dumpAppState(StringBuilder sb) {
		int cnt = (smtpMessage == null || smtpMessage.getRecipients() == null ? -1 : smtpMessage.getRecipients().size());
		if (sb == null) sb = new StringBuilder();
		sb.append(pfx_log).append('/').append(pstate).append("/0x").append(Integer.toHexString(state2)).append(": ");
		peerDescription(sb);
		sb.append("; recips=").append(recip_id).append('/').append(cnt);
		return sb;
	}

	@Override
	public String toString() {
		return getClass().getName()+"=E"+getCMID();
	}
}
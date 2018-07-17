/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import com.grey.mailismus.AppConfig;
import com.grey.mailismus.Task;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.errors.MailismusException;
import com.grey.mailismus.mta.Protocol;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.EmailAddress;
import com.grey.logging.Logger.LEVEL;

final class Client
	extends com.grey.naf.reactor.CM_Client
	implements Delivery.MessageSender,
		com.grey.naf.dns.ResolverDNS.Client,
		com.grey.naf.EntityReaper, //only the prototype Client acts as reaper (for the active Clients)
		com.grey.naf.reactor.TimerNAF.Handler
{
	private static final byte OPEN_ANGLE = '<';
	private static final byte CLOSE_ANGLE = '>';
	private static final com.grey.base.utils.ByteChars SMTPREQ_MAILFROM = new com.grey.base.utils.ByteChars(Protocol.CMDREQ_MAILFROM).append(OPEN_ANGLE);
	private static final com.grey.base.utils.ByteChars SMTPREQ_MAILTO = new com.grey.base.utils.ByteChars(Protocol.CMDREQ_MAILTO).append(OPEN_ANGLE);

	private static final int TMRTYPE_SESSIONTMT = 1;
	private static final int TMRTYPE_DISCON = 2;

	private enum PROTO_STATE {S_DISCON, S_CONN, S_READY, S_AUTH, S_STLS, S_HELO, S_EHLO, S_MAILFROM, S_MAILTO, S_DATA, S_MAILBODY,
									S_QUIT, S_RESET, S_END}
	private enum PROTO_EVENT {E_CONNECTED, E_DISCONNECTED, E_REPLY, E_LOCALERROR, E_DISCONNECT, E_SSL}
	private enum PROTO_ACTION {A_CONNECT, A_DISCONNECT, A_HELO, A_EHLO, A_MAILFROM, A_MAILTO, A_DATA, A_MAILBODY,
									A_QUIT, A_RESET, A_ENDMESSAGE, A_STARTSESSION, A_ENDSESSION, A_LOGIN, A_STLS};

	private static final byte S2_DNSWAIT = 1 << 0;
	private static final byte S2_REPLYCONTD = 1 << 1;
	private static final byte S2_DISCARD = 1 << 2; //discarding remainder of an excessively long reply (we're only interested in initial part)
	private static final byte S2_SENT_DATACMD = 1 << 3;
	private static final byte S2_DOMAIN_ERR = 1 << 4; //apply error status to entire domain, not just a particular message recipient
	private static final byte S2_SERVER_STLS = 1 << 5; //the server has advertised STARTTLS capability
	private static final byte S2_ABORT = 1 << 6;
	private static final byte S2_CNXLIMIT = (byte)(1 << 7); //this is 8-bit, but overflows Byte.MAX_VALUE

	private static final com.grey.base.utils.ByteChars FAILMSG_TMT = new com.grey.base.utils.ByteChars("SMTP session timed out");
	private static final com.grey.base.utils.ByteChars FAILMSG_NOSSL = new com.grey.base.utils.ByteChars("Remote MTA doesn't support SSL");
	private static final com.grey.base.utils.ByteChars FAILMSG_NORECIPS = new com.grey.base.utils.ByteChars("No valid recipients specified");
	private static final com.grey.base.utils.ByteChars FAILMSG_NOSPOOL = new com.grey.base.utils.ByteChars("Message deleted from queue");

	// config to apply on a per-connection basis, depending who we're talking to
	private static final class ConnectionConfig
	{
		final com.grey.base.utils.IP.Subnet[] ipnets;
		final long tmtprotocol;
		final long minrate_data;
		final long nanosecs_per_byte;
		final long delay_chanclose; //has solved abort-on-close issues in the past
		final String announcehost;	//hostname to announce in HELO/EHLO
		final boolean sayHELO;		//default is to initially say EHLO
		final boolean fallbackHELO;	//fallback to HELO if EHLO is rejected - the default is not to
		final boolean sendQUIT;
		final boolean awaitQUIT;
		final int max_pipe; //max requests that can be pipelined in one send
		final com.grey.naf.SSLConfig anonssl; //controls SSL behaviour with respect to servers other than the configured relays

		// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned replies
		final java.nio.ByteBuffer reqbuf_helo;
		final java.nio.ByteBuffer reqbuf_ehlo;

		public ConnectionConfig(int id, com.grey.base.config.XmlConfig cfg, SharedFields common, ConnectionConfig dflts,
				com.grey.naf.reactor.Dispatcher dsptch, String logpfx)
				throws java.net.UnknownHostException, java.io.IOException, java.security.GeneralSecurityException
		{
			String label = logpfx+"remotenets #"+id+" connection config";
			if (id == 0) {
				ipnets = null;
			} else {
				ipnets = parseSubnets(cfg, "@ip", common.appConfig);
				if (ipnets == null) throw new MailismusConfigException(label+": missing 'ip' attribute");
			}
			announcehost = common.appConfig.getAnnounceHost(cfg, dflts==null ? common.appConfig.getAnnounceHost() : dflts.announcehost);
			tmtprotocol = cfg.getTime("timeout", dflts==null ? com.grey.base.utils.TimeOps.parseMilliTime("1m") : dflts.tmtprotocol);
			minrate_data = cfg.getSize("mindatarate", dflts==null ? 1024*1024 : dflts.minrate_data);
			delay_chanclose = cfg.getTime("delay_close", dflts==null ? 0 : dflts.delay_chanclose);
			sayHELO = cfg.getBool("sayHELO", dflts==null ? false : dflts.sayHELO);
			fallbackHELO = cfg.getBool("fallbackHELO", dflts==null ? false : dflts.fallbackHELO);
			sendQUIT = cfg.getBool("sendQUIT", dflts==null ? true : dflts.sendQUIT);
			awaitQUIT = (sendQUIT ? cfg.getBool("waitQUIT", dflts==null ? true : dflts.awaitQUIT) : false);

			com.grey.base.config.XmlConfig sslcfg = cfg.getSection("anonssl");
			anonssl = com.grey.naf.SSLConfig.create(sslcfg, dsptch.getApplicationContext().getConfig(), null, true);

			// ESMTP settings
			int _max_pipe = cfg.getInt("maxpipeline", false, dflts==null ? 25 : dflts.max_pipe);
			max_pipe = (_max_pipe == 0 ? 1 : _max_pipe);

			String announce = (announcehost == null ? "" : " " + announcehost);
			reqbuf_helo = Task.constBuffer(Protocol.CMDREQ_HELO+announce+Protocol.EOL);
			reqbuf_ehlo = Task.constBuffer(Protocol.CMDREQ_EHLO+announce+Protocol.EOL);

			if (tmtprotocol == 0 || minrate_data == 0) {
				throw new MailismusConfigException(logpfx+"Idle timeout and mindatarate cannot be zero");
			}
			long nanosecs = ( 8L * 1000L * 1000L * 1000L ) / minrate_data;
			if (nanosecs == 0) nanosecs = 0;
			nanosecs_per_byte = nanosecs;
			java.text.DecimalFormat commafmt = new java.text.DecimalFormat("#,###");

			if (ipnets == null) {
				dsptch.getLogger().info(logpfx+"Default connection config:");
			} else {
				String txt = "Subnets="+ipnets.length;
				String dlm = ": ";
				for (int idx = 0; idx != ipnets.length; idx++) {
					txt += dlm+com.grey.base.utils.IP.displayDottedIP(ipnets[idx].ip, null)+"/"+ipnets[idx].netprefix;
					dlm = ", ";
				}
				dsptch.getLogger().info(label+" - "+txt);
			}
			String pfx = "- ";
			dsptch.getLogger().info(pfx+"Announce="+announcehost
					+ "; sayHELO="+sayHELO+"; fallbackHELO="+fallbackHELO
					+ "; sendQUIT="+sendQUIT + "; waitQUIT="+awaitQUIT);
			if (anonssl != null) anonssl.declare(pfx, dsptch.getLogger());
			dsptch.getLogger().info(pfx+"timeout="+com.grey.base.utils.TimeOps.expandMilliTime(tmtprotocol)
					+"; mindatarate="+commafmt.format(minrate_data)+" bps"
					+"; pipelining-max="+max_pipe);
			dsptch.getLogger().trace(pfx+"delay_close="+delay_chanclose);
		}
	}

	private static final class SharedFields
	{
		final ConnectionConfig defaultcfg;
		final ConnectionConfig[] remotecfg;
		final com.grey.naf.BufferSpec bufspec;
		final boolean fallback_mx_a; //MX queries fall back to simple hostname lookup if no MX RRs exist
		final Client prototype_client;

		// assorted shareable objects
		final AppConfig appConfig;
		final Delivery.Controller controller;
		final Routing routing;
		final com.grey.base.sasl.PlainClient sasl_plain = new com.grey.base.sasl.PlainClient(true);
		final com.grey.base.sasl.CramMD5Client sasl_cmd5 = new com.grey.base.sasl.CramMD5Client(true);
		final com.grey.base.sasl.ExternalClient sasl_external = new com.grey.base.sasl.ExternalClient(true);
		final java.util.HashMap<com.grey.base.utils.ByteChars, com.grey.base.sasl.SaslEntity.MECH> authtypes_supported;
		final com.grey.mailismus.Transcript transcript;
		private final com.grey.base.collections.HashedMapIntInt active_serverconns; //maps server IP to current connection count
		private final int max_serverconns; //max simultaneous connections to any one server

		// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned commands
		final java.nio.ByteBuffer reqbuf_data;
		final java.nio.ByteBuffer reqbuf_quit;
		final java.nio.ByteBuffer reqbuf_reset;
		final java.nio.ByteBuffer reqbuf_eom;
		final java.nio.ByteBuffer reqbuf_stls;

		// temp work areas pre-allocated for efficiency
		final com.grey.base.utils.ByteChars pipebuf = new com.grey.base.utils.ByteChars(); //used up in one callback, so safe to share
		final StringBuilder disconnect_msg_buf = new StringBuilder();
		final com.grey.base.utils.TSAP tmptsap = new com.grey.base.utils.TSAP();
		final byte[] ipbuf = com.grey.base.utils.IP.ip2net(0, null, 0);
		final com.grey.base.utils.ByteChars failbc = new com.grey.base.utils.ByteChars();
		final com.grey.base.utils.ByteChars failbc2 = new com.grey.base.utils.ByteChars();
		final StringBuilder tmpsb = new StringBuilder();
		final StringBuilder tmpsb2 = new StringBuilder();
		final com.grey.base.utils.ByteChars tmpbc = new com.grey.base.utils.ByteChars();
		final com.grey.base.utils.ByteChars tmplightbc = new com.grey.base.utils.ByteChars(-1); //lightweight object without own storage
		java.nio.ByteBuffer tmpniobuf;

		public SharedFields(com.grey.base.config.XmlConfig cfg, com.grey.naf.reactor.Dispatcher dsptch,
				Delivery.Controller ctl, Client proto, String logpfx, int maxconns)
			throws java.net.UnknownHostException, java.io.IOException, java.security.GeneralSecurityException
		{
			prototype_client = proto;
			controller = ctl;
			routing = ctl.getRouting();
			appConfig = ctl.getAppConfig();

			transcript = com.grey.mailismus.Transcript.create(dsptch, cfg, "transcript");
			bufspec = new com.grey.naf.BufferSpec(cfg, "niobuffers", 256, 128);
			fallback_mx_a = cfg.getBool("fallbackMX_A", false);
			boolean nocnxlimit = cfg.getBool("nomaxsrvconns", false);
			if (bufspec.rcvbufsiz < 40) throw new MailismusConfigException(logpfx+"recvbuf="+bufspec.rcvbufsiz+" is too small");

			authtypes_supported = new java.util.HashMap<com.grey.base.utils.ByteChars, com.grey.base.sasl.SaslEntity.MECH>();
			com.grey.base.sasl.SaslEntity.MECH[] methods = new com.grey.base.sasl.SaslEntity.MECH[] {com.grey.base.sasl.SaslEntity.MECH.PLAIN,
					com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5, com.grey.base.sasl.SaslEntity.MECH.EXTERNAL};
			for (int idx = 0; idx != methods.length; idx++) {
				authtypes_supported.put(new com.grey.base.utils.ByteChars(methods[idx].toString().replace('_', '-')), methods[idx]);
			}

			max_serverconns = (nocnxlimit ? 0 : maxconns);
			active_serverconns = (max_serverconns == 0 ? null : new com.grey.base.collections.HashedMapIntInt());

			reqbuf_data = Task.constBuffer(Protocol.CMDREQ_DATA+Protocol.EOL);
			reqbuf_quit = Task.constBuffer(Protocol.CMDREQ_QUIT+Protocol.EOL);
			reqbuf_reset = Task.constBuffer(Protocol.CMDREQ_RESET+Protocol.EOL);
			reqbuf_eom = Task.constBuffer(Protocol.EOM);
			reqbuf_stls = Task.constBuffer(Protocol.CMDREQ_STLS+Protocol.EOL);

			// read the per-connection config
			defaultcfg = new ConnectionConfig(0, cfg, this, null, dsptch, logpfx);
			com.grey.base.config.XmlConfig[] cfgnodes = cfg.getSections("remotenets/remotenet");
			if (cfgnodes == null) {
				remotecfg = null;
			} else {
				remotecfg = new ConnectionConfig[cfgnodes.length];
				for (int idx = 0; idx != cfgnodes.length; idx++) {
					remotecfg[idx] = new ConnectionConfig(idx+1, cfgnodes[idx], this, defaultcfg, dsptch, logpfx);
				}
			}
			dsptch.getLogger().trace(logpfx+"fallbackMX_A="+fallback_mx_a+", maxsrvconns="+max_serverconns);
			dsptch.getLogger().trace(logpfx+bufspec);
		}

		public boolean incrementServerConnections(int ip)
		{
			if (active_serverconns == null) return true;
			int cnt = active_serverconns.get(ip);
			if (cnt == max_serverconns) return false;
			active_serverconns.put(ip, cnt+1);
			return true;
		}

		public void decrementServerConnections(int ip)
		{
			if (active_serverconns == null || ip == 0) return;
			int cnt = active_serverconns.get(ip);
			if (--cnt == 0) {
				active_serverconns.remove(ip);
			} else {
				active_serverconns.put(ip, cnt);
			}
		}
	}

	static final class Factory
		implements com.grey.base.collections.GenericFactory<Delivery.MessageSender>
	{
		private final Client prototype;
		public Factory(Client p) {prototype=p;}
		@Override
		public Client factory_create() {return new Client(prototype);}
	}

	private final Delivery.MessageParams msgparams = new Delivery.MessageParams();
	private final SharedFields shared;
	private final com.grey.base.utils.TSAP remote_tsap_buf;
	private final java.util.ArrayList<com.grey.naf.dns.ResourceData> dnsInfo = new java.util.ArrayList<com.grey.naf.dns.ResourceData>();
	private ConnectionConfig conncfg; //config to apply to current connection
	private Relay active_relay;
	private com.grey.base.utils.TSAP remote_tsap;
	private PROTO_STATE pstate;
	private byte state2; //secondary-state, qualifying some of the pstate phases
	private com.grey.naf.reactor.TimerNAF tmr_exit;
	private com.grey.naf.reactor.TimerNAF tmr_sesstmt;
	private long alt_tmtprotocol; //if non-zero, this overrides ConnectionConfig.tmtprotocol
	private int mxptr; //indicates which dnsInfo.rrlist node we're currently connecting/connected to - only valid if dnsInfo non-empty
	private int recip_id; //indicates which recipient we're currently awaiting a response for
	private int recips_sent; //how many recips we've already sent to server - will run ahead of recip_id in pipelining mode
	private int okrecips; //number of recipients accepted by server
	private int dataWait;
	private short reply_status;
	private short disconnect_status;
	private int pipe_cap; //max pipeline for current connection - 1 means pipelining not enabled
	private int pipe_count; //number of requests in current pipelined send
	private com.grey.base.sasl.SaslEntity.MECH auth_method;
	private byte auth_step;
	private int cnxid; //increments with each incarnation - useful for distinguishing Transcript logs
	private String pfx_log;
	private String pfx_transcript;

	@Override public Delivery.MessageParams getMessageParams() {return msgparams;}
	@Override public String getLogID() {return pfx_log;}

	private void setFlag(byte f, boolean b) {if (b) {setFlag(f);} else {clearFlag(f);}}
	private void setFlag(byte f) {state2 |= f;}
	private void clearFlag(byte f) {state2 &= ~f;}
	private boolean isFlagSet(byte f) {return ((state2 & f) == f);}

	@Override
	protected com.grey.naf.SSLConfig getSSLConfig() {return (active_relay == null ? conncfg.anonssl : active_relay.sslconfig);}

	// this is a prototype instance which provides configuration info for the others
	public Client(Delivery.Controller ctl, com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg, int maxconns)
		throws java.io.IOException
	{
		super(d, null, null);
		String pfx = "SMTP-Client";
		pfx_log = pfx+"/E"+getCMID();
		pfx += ": ";
		try {
			shared = new SharedFields(cfg, getDispatcher(), ctl, this, pfx, maxconns);
		} catch (java.security.GeneralSecurityException ex) {
			throw new MailismusConfigException("Failed to create shared config", ex);
		}
		remote_tsap_buf = null;
	}

	// this is an actual client which participates in SMTP connections
	Client(Client proto)
	{
		super(proto.getDispatcher(), proto.shared.bufspec, proto.shared.bufspec);
		shared = proto.shared;
		setLogPrefix();
		//will need to build addresses at connect time if we don't have a default relay
		remote_tsap_buf = (shared.routing.haveDefaultRelay() ? null : new com.grey.base.utils.TSAP());
	}

	private ConnectionConfig getConnectionConfig(int remote_ip)
	{
		int cnt = (shared.remotecfg == null ? 0 : shared.remotecfg.length);
		for (int idx = 0; idx != cnt; idx++) {
			ConnectionConfig cnxcfg = shared.remotecfg[idx];
			for (int idx2 = 0; idx2 != cnxcfg.ipnets.length; idx2++) {
				if (cnxcfg.ipnets[idx2].isMember(remote_ip)) return cnxcfg;
			}
		}
		return shared.defaultcfg;
	}

	private void transitionState(PROTO_STATE newstate)
	{
		pstate = newstate;
	}

	@Override
	public void start(Delivery.Controller ctl) throws java.io.IOException
	{
		setReaper(shared.prototype_client);
		initConnection();
		issueAction(PROTO_ACTION.A_CONNECT, PROTO_STATE.S_CONN);
	}

	@Override
	public boolean stop()
	{
		if (this == shared.prototype_client) {
			if (shared.transcript != null) {
				shared.transcript.close(getSystemTime());
			}
			return true;
		}
		setFlag(S2_ABORT);
		if (pstate == PROTO_STATE.S_DISCON) return (tmr_exit == null); // we're already completely stopped
		issueDisconnect(0, "Forcibly halted");
		return false; //the disconnect() will call the reaper when its done
	}

	@Override
	public void entityStopped(Object obj)
	{
		shared.controller.senderCompleted((Client)obj);
	}

	@Override
	public void ioReceived(ByteArrayRef rcvdata) throws java.io.IOException
	{
		if (pstate == PROTO_STATE.S_DISCON) return; //this method can be called in a loop, so skip it after a disconnect
		if (shared.transcript != null) shared.transcript.data_in(pfx_transcript, rcvdata, getSystemTime());
		alt_tmtprotocol = 0;
		eventRaised(PROTO_EVENT.E_REPLY, rcvdata, null);
	}

	@Override
	public void ioDisconnected(CharSequence diagnostic)
	{
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, diagnostic);
	}

	private PROTO_STATE issueDisconnect(int statuscode, CharSequence diagnostic)
	{
		return issueDisconnect(statuscode, diagnostic, null);
	}

	private PROTO_STATE issueDisconnect(int statuscode, CharSequence diagnostic, ByteArrayRef failmsg)
	{
		if (pstate == PROTO_STATE.S_DISCON) return pstate;
		CharSequence discmsg = "Disconnect";

		if (shared.transcript != null) {
			// We will transcript this at the actual point of closing the connection.
			// The POP3 client does this more cleanly, as it doesn't finalise the message until it transcripts it.
			if (diagnostic != null) {
				shared.disconnect_msg_buf.setLength(0);
				shared.disconnect_msg_buf.append(discmsg).append(" - ").append(diagnostic);
				discmsg = shared.disconnect_msg_buf;
			}
		}
		if (pstate == PROTO_STATE.S_RESET) statuscode = 0; // failed on transition to new message, so don't assign the blame to its recips
		disconnect_status = (short)statuscode;
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECT, failmsg, discmsg);
		return pstate;
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d)
	{
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

	private void dnsLookup(boolean as_host) throws java.io.IOException
	{
		mxptr = 0;
		dnsInfo.clear();
		setFlag(S2_DNSWAIT);
		com.grey.naf.dns.ResolverAnswer answer;
		if (as_host) {
			answer = getDispatcher().getResolverDNS().resolveHostname(msgparams.getDestination(), this, null, 0);
		} else {
			answer = getDispatcher().getResolverDNS().resolveMailDomain(msgparams.getDestination(), this, null, 0);
		}
		if (answer != null) dnsResolved(getDispatcher(), answer, null);
	}

	@Override
	public void dnsResolved(com.grey.naf.reactor.Dispatcher d, com.grey.naf.dns.ResolverAnswer answer, Object callerparam)
	{
		try {
			handleDnsResult(answer);
		} catch (Throwable ex) {
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed on DNS response - "+answer);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed to process DNS response - "+com.grey.base.ExceptionUtils.summary(ex));
		}
	}

	private void handleDnsResult(com.grey.naf.dns.ResolverAnswer answer) throws java.net.UnknownHostException
	{
		clearFlag(S2_DNSWAIT);
		int statuscode = 0;
		CharSequence diagnostic = null;

		if (shared.fallback_mx_a) {
			if (answer.result == com.grey.naf.dns.ResolverAnswer.STATUS.NODOMAIN
					&& answer.qtype == com.grey.naf.dns.ResolverDNS.QTYPE_MX) {
				try {
					dnsLookup(true);
					return;
				} catch (Exception ex) {
					getLogger().log(LEVEL.ERR, ex, false, pfx_log+" failed on DNS-A lookup");
					answer.result = com.grey.naf.dns.ResolverAnswer.STATUS.BADNAME;
				}
			}
		}

		switch (answer.result)
		{
		case OK:
			if (answer.qtype == com.grey.naf.dns.ResolverDNS.QTYPE_MX) {
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

		if (getLogger().isActive(LEVEL.TRC) || shared.transcript != null) {
			shared.disconnect_msg_buf.setLength(0);
			shared.disconnect_msg_buf.append("DNS=").append(answer.result);
			diagnostic = shared.disconnect_msg_buf;
		}
		connectionFailed(statuscode, diagnostic, null);
	}

	@Override
	public void eventError(Throwable ex)
	{
		eventErrorIndication(ex, null);
	}

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex)
	{
		eventErrorIndication(ex, tmr);
	}

	// error already logged by Dispatcher
	private void eventErrorIndication(Throwable ex, com.grey.naf.reactor.TimerNAF tmr)
	{
		raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "State="+pstate+" - "+com.grey.base.ExceptionUtils.summary(ex));
	}

	private void issueConnect() throws java.io.IOException
	{
		active_relay = msgparams.getRelay();
		if (active_relay != null) remote_tsap = active_relay.tsap;

		if (remote_tsap != null) {
			connect();
			return;
		}

		if (msgparams.getDestination() == null) {
			// If we got here this message is not routed, so the recipient domain must be null. Such a message
			// should never have been handed to this client, as it is intended solely for remote delivery via SMTP.
			connectionFailed(Protocol.REPLYCODE_PERMERR_ADDR, "SMTP client cannot deliver to local mailboxes", null);
			return;
		}
		dnsLookup(false);
	}

	// This is only called as the result of a DNS lookup on the destination domain, and is the only route via
	// which DNS lookups lead to the connect() method below.
	private void connect(int remote_ip) throws java.net.UnknownHostException
	{
		remote_tsap = remote_tsap_buf;
		remote_tsap.set(remote_ip, Protocol.TCP_PORT, getLogger().isActive(LEVEL.TRC2) || (shared.transcript != null));
		connect();
	}

	// This is the only route via which ChannelMonitor.connect() gets called
	private void connect() throws java.net.UnknownHostException
	{
		if (!shared.incrementServerConnections(remote_tsap.ip)) {
			if (++mxptr < dnsInfo.size()) {
				connect(dnsInfo.get(mxptr).getIP());
				return;
			}
			setFlag((byte)(S2_ABORT | S2_CNXLIMIT)); //set ABORT too, for sake of setRecipientStatus()
			raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, null);
			return;
		}
		conncfg = getConnectionConfig(remote_tsap.ip); //update from initial default or previous IP
		com.grey.base.utils.TSAP tsap = remote_tsap;
		Relay interceptor = shared.routing.getInterceptor();

		if (interceptor != null) {
			//active_relay==interceptor will be true if we we are trying additional MX relays because first one failed
			if (active_relay == null || active_relay == interceptor || !interceptor.dns_only) {
				//leave remote_tsap as is so that we log the IP address we would have connected to
				active_relay = interceptor;
				tsap = active_relay.tsap;
			}
		}

		try {
			connect(tsap.sockaddr);
		} catch (Throwable ex) {
			connectionFailed(0, "connect-error", ex);
		}
	}

	@Override
	protected void connected(boolean success, CharSequence diag, Throwable exconn) throws java.io.IOException
	{
		if (isFlagSet(S2_ABORT)) return; //we must be waiting for exit timer - will close the connection then
		if (!success) {
			connectionFailed(0, diag==null?"connect-fail":diag, exconn);
			return;
		}
		LEVEL lvl = LEVEL.TRC2;
		if (getLogger().isActive(lvl) || (shared.transcript != null)) {
			com.grey.base.utils.TSAP local_tsap = com.grey.base.utils.TSAP.get(getLocalIP(), getLocalPort(), shared.tmptsap, true);
			if (getLogger().isActive(lvl)) {
				StringBuilder sb = shared.tmpsb;
				sb.setLength(0);
				sb.append(pfx_log).append(" connected to ");
				recordConnection(sb, local_tsap);
				getLogger().log(lvl, sb);
			}
			if (shared.transcript != null) {
				Relay rly = msgparams.getRelay();
				CharSequence remote = (rly == null ? msgparams.getDestination() : rly.display());
				shared.transcript.connection_out(pfx_transcript, local_tsap.dotted_ip, local_tsap.port,
												remote_tsap.dotted_ip, remote_tsap.port, getSystemTime(),
												remote, usingSSL());
			}
		}
		eventRaised(PROTO_EVENT.E_CONNECTED, null, null);
	}

	// statuscode zero means that a TCP connection attempt has failed (with exconn giving the reason, and that we should
	// simply move onto the next known IP for our destination domain.
	// If statuscode is non-zero, then we're giving up on this destination domain (for now anyway, not necessarily a perm
	// error) and 'diagnostic' gives the reason.
	// statuscode zero therefore also means that remote_tsap is non-null, as we have attempted a connection.
	private void connectionFailed(int statuscode, CharSequence diagnostic, Throwable exconn) throws java.net.UnknownHostException
	{
		CharSequence extspid = null;
		StringBuilder sb = shared.tmpsb;

		if (statuscode == 0) {
			if (shared.transcript != null) {
				Relay rly = msgparams.getRelay();
				CharSequence remote = (rly == null ? msgparams.getDestination() : rly.display());
				shared.transcript.connection_out(pfx_transcript, null, 0, remote_tsap.dotted_ip, remote_tsap.port,
						getSystemTime(), remote, usingSSL());
			}
			LEVEL lvl = (MailismusException.isError(exconn) ? LEVEL.WARN : LEVEL.TRC2);
			if (getLogger().isActive(lvl)) {
				extspid = formatSPID(msgparams.getSPID());
				sb.setLength(0);
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
				shared.decrementServerConnections(remote_tsap.ip);
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
		StringBuilder sbfail = shared.tmpsb2;
		sbfail.setLength(0);
		peerDescription(sbfail);

		if (getLogger().isActive(lvl) || (shared.transcript != null)) {
			if (extspid == null) extspid = formatSPID(msgparams.getSPID());
			StringBuilder sbdisc = shared.disconnect_msg_buf;
			if (diagnostic == sbdisc) {
				sb.setLength(0);
				sb.append(sbdisc);
				diagnostic = sb;
			}
			sbdisc.setLength(0);
			sbdisc.append("Cannot connect to ").append(sbfail).append(" for msgid=").append(extspid);
			if (diagnostic != null) sbdisc.append(" - ").append(diagnostic);
			diagnostic = sbdisc;

			if (getLogger().isActive(lvl)) {
				sb.setLength(0);
				sb.append(pfx_log).append(' ').append(sbdisc);
				getLogger().log(lvl, sb);
			}
		}
		shared.failbc2.populate("Cannot connect to ").append(sbfail);
		if (exconn != null) shared.failbc2.append(" - ").append(exconn.toString());
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, shared.failbc2, diagnostic);
	}

	// discmsg is a Transcript-friendly reason for the disconnect and failmsg is the provisional NDR diagnostic, which may
	// consist of a reject response from the remote server or a locally generated problem description (provisional because
	// we don't decide here whether any failure is transient or final).
	private void endConnection(CharSequence discmsg, ByteArrayRef failmsg)
	{
		LEVEL lvl = LEVEL.TRC2;
		if (getLogger().isActive(lvl)) {
			shared.tmpsb.setLength(0);
			shared.tmpsb.append(pfx_log).append(" ending with state=").append(pstate).append("/0x").append(Integer.toHexString(state2));
			shared.tmpsb.append(", remote=").append(remote_tsap).append("/dns=").append(dnsInfo.size());
			shared.tmpsb.append(", msgcnt=").append(msgparams.messageCount());
			if (discmsg != null) shared.tmpsb.append(" - reason=").append(discmsg);
			if (failmsg != null) shared.tmpsb.append(" - diagnostic=").append(shared.tmpbc.populateBytes(failmsg));
			getLogger().log(lvl, shared.tmpsb);
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
			disconnect_status = reply_status;
			String msg = "Disconnected due to EHLO rejection";
			if (discmsg != null) msg += " - "+discmsg;
			discmsg = msg;
		}

		try {
			if (failmsg == null && discmsg != null) failmsg = shared.failbc.populate(discmsg);
			setRecipientStatus(-1, disconnect_status, failmsg, false);
		} catch (Exception ex) {
			getLogger().log(LEVEL.WARN, ex, false, pfx_log+" failed to set final recipients status");
		}
		if (remote_tsap != null && !isFlagSet(S2_CNXLIMIT)) shared.decrementServerConnections(remote_tsap.ip);
		if (remote_tsap_buf != null) remote_tsap_buf.clear(); //don't erase the statically configured TSAPs!

		if (isFlagSet(S2_DNSWAIT)) {
			try {
				getDispatcher().getResolverDNS().cancel(this);
			} catch (Exception ex) {
				getLogger().log(LEVEL.INFO, ex, false, pfx_log+" failed to cancel DNS ops");
			}
		}
		clearFlag(S2_DNSWAIT);
		dnsInfo.clear();

		// don't call disconnect() till next Dispatcher callback, to prevent reentrancy issues
		if (shared.transcript != null && discmsg != null) shared.transcript.event(pfx_transcript, discmsg, getSystemTime());
		long delay = (isFlagSet(S2_ABORT) ? 0 : conncfg.delay_chanclose);
		dataWait = 0;
		transitionState(PROTO_STATE.S_DISCON);
		tmr_exit = getDispatcher().setTimer(delay, TMRTYPE_DISCON, this);
	}

	// this eliminate the Throws declaration for events that are known not to throw
	private PROTO_STATE raiseSafeEvent(PROTO_EVENT evt, ByteArrayRef rspdata, CharSequence discmsg)
	{
		try {
			eventRaised(evt, rspdata, discmsg);
		} catch (Throwable ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed to issue event="+evt);
			endConnection("Failed to issue event="+evt+" - "+com.grey.base.ExceptionUtils.summary(ex), null);
		}
		return pstate;
	}

	private PROTO_STATE eventRaised(PROTO_EVENT evt, ByteArrayRef rspdata, CharSequence discmsg) throws java.io.IOException
	{
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
			if (shared.transcript != null) shared.transcript.event(pfx_transcript, "Switched to SSL mode", getSystemTime());
			initMessage();
			pipe_cap = 1; //we will go through EHLO response again
			issueAction(PROTO_ACTION.A_EHLO, PROTO_STATE.S_EHLO);
			break;

		case E_LOCALERROR:
			issueDisconnect(Protocol.REPLYCODE_TMPERR_LOCAL, "Local Error - "+discmsg);
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
			long tmt = (alt_tmtprotocol == 0 ? conncfg.tmtprotocol : alt_tmtprotocol);
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

	private PROTO_STATE handleReply(ByteArrayRef rspdata) throws java.io.IOException
	{
		// We don't expect continued replies for any command except EHLO, but they are always legal, so if we do receive a continued reply
		// in response to anything else then we discard the leading lines and wait for the final one, taking its reply code as the
		// definitive one. CORRECTION!! AOL sends a multi-line Greeting, so just as well we handle continued replies anywhere.
		setFlag(S2_REPLYCONTD, rspdata.byteAt(Protocol.REPLY_CODELEN) == Protocol.REPLY_CONTD);
		reply_status = getReplyCode(rspdata);
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
			if (conncfg.sayHELO) return issueAction(PROTO_ACTION.A_HELO, PROTO_STATE.S_HELO, okreply, null, rspdata);
			issueAction(PROTO_ACTION.A_EHLO, PROTO_STATE.S_EHLO, okreply, null, rspdata);
			break;

		case S_RESET:
			issueAction(PROTO_ACTION.A_MAILFROM, PROTO_STATE.S_MAILFROM, okreply, null, rspdata);
			break;

		case S_HELO:
			issueAction(PROTO_ACTION.A_STARTSESSION, null, okreply, null, rspdata);
			break;

		case S_EHLO:
			if (reply_status != okreply) {
				if (conncfg.fallbackHELO) return issueAction(PROTO_ACTION.A_HELO, PROTO_STATE.S_HELO);
			} else {
				if (matchesExtension(rspdata, Protocol.EXT_PIPELINE, false)) {
					pipe_cap = conncfg.max_pipe;
				} else if (matchesExtension(rspdata, Protocol.EXT_STLS, false)) {
					setFlag(S2_SERVER_STLS);
				} else if (active_relay != null && active_relay.auth_enabled && auth_method == null
						&& (matchesExtension(rspdata, Protocol.EXT_AUTH, false)
								|| (active_relay.auth_compat && matchesExtension(rspdata, Protocol.EXT_AUTH_COMPAT, true)))) {
					// loop through the advertised SASL mechanisms and use the first one we support
					int lmt = rspdata.limit();
					int off = rspdata.offset();
					while (off != lmt) {
						int off2 = off;
						while (rspdata.buffer()[off2] > ' ') off2++;
						shared.tmplightbc.set(rspdata.buffer(), off, off2-off);
						auth_method = shared.authtypes_supported.get(shared.tmplightbc);
						//server might advertise EXTERNAL anyway without meaning it unless in SSL mode, so keep scanning for better option
						if (auth_method != null && auth_method != com.grey.base.sasl.SaslEntity.MECH.EXTERNAL) break;
						off = off2;
						while (rspdata.buffer()[off] == ' ') off++;
					}
				}
			}
			if (!isFlagSet(S2_REPLYCONTD)) issueAction(PROTO_ACTION.A_STARTSESSION, null, okreply, null, rspdata);
			break;

		case S_MAILFROM:
			issueAction(PROTO_ACTION.A_MAILTO, PROTO_STATE.S_MAILTO, okreply, null, rspdata);
			break;

		case S_MAILTO:
			if (reply_status == Protocol.REPLYCODE_RECIPMOVING) reply_status = okreply;
			setRecipientStatus(recip_id++, reply_status, rspdata, true);
			okreply = 0; //an error response at this stage merely invalidates the current recipient, so reply status has now been processed
			if (recip_id == msgparams.recipCount()) {
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
			if (reply_status != Protocol.REPLYCODE_AUTH_OK && reply_status != Protocol.REPLYCODE_AUTH_CONTD) {
				return issueDisconnect(reply_status, "Authentication failed", rspdata);
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

	private PROTO_STATE issueAction(PROTO_ACTION action, PROTO_STATE newstate, int okreply, CharSequence discmsg, ByteArrayRef rspdata)
		throws java.io.IOException
	{
		if (okreply != 0 && reply_status != okreply) {
			//note that okreply will already have been reset in state S_MAILTO
			LEVEL lvl = LEVEL.TRC;
			if (getLogger().isActive(lvl)) {
				int len = rspdata.size();
				while (rspdata.buffer()[rspdata.offset()+len-1] < ' ') len--; //strip trailing CRLF
				StringBuilder sb = shared.tmpsb;
				sb.setLength(0);
				sb.append(pfx_log).append(" rejected in state=").append(pstate).append(" - ");
				peerDescription(sb);
				sb.append(" replied: ").append(shared.tmplightbc.set(rspdata.buffer(), rspdata.offset(), len));
				getLogger().log(lvl, sb);
			}
			return issueDisconnect(reply_status, "Server rejection", rspdata);
		}
		if (newstate != null) transitionState(newstate);
		boolean endpipe = (pipe_count == 0 && dataWait != 0); //changed state, but don't send any more until all pipelined commands are acked

		switch (action)
		{
		case A_CONNECT:
			issueConnect();
			break;

		case A_HELO:
			auth_method = (active_relay == null ? null : active_relay.auth_override);
			transmit(conncfg.reqbuf_helo);
			break;

		case A_EHLO:
			auth_method = (active_relay == null ? null : active_relay.auth_override);
			transmit(conncfg.reqbuf_ehlo);
			break;

		case A_STARTSESSION:
			com.grey.naf.SSLConfig activessl = getSSLConfig();
			if (activessl != null && !usingSSL()) {
				if (isFlagSet(S2_SERVER_STLS)) {
					return issueAction(PROTO_ACTION.A_STLS, PROTO_STATE.S_STLS);
				}
				if (activessl.mdty) {
					return issueDisconnect(Protocol.REPLYCODE_NOSSL, "Server doesn't support required SSL mode", FAILMSG_NOSSL);
				}
			}
			if (auth_method != null) {
				return issueAction(PROTO_ACTION.A_LOGIN, PROTO_STATE.S_AUTH);
			}
			issueAction(PROTO_ACTION.A_MAILFROM, PROTO_STATE.S_MAILFROM);
			break;

		case A_MAILFROM: //all recips members refer to same message, so they have a common Sender
			endpipe = sendPipelinedRequest(SMTPREQ_MAILFROM, false, msgparams.getSender(), null, true);
			if (!endpipe) issueAction(PROTO_ACTION.A_MAILTO, null);
			break;

		case A_MAILTO:
			while (!endpipe) {
				if (recips_sent == msgparams.recipCount()) {
					issueAction(PROTO_ACTION.A_DATA, null);
					break;
				}
				com.grey.mailismus.mta.queue.MessageRecip recip = msgparams.getRecipient(recips_sent);
				if (recip.spid != msgparams.getSPID()) {
					throw new IllegalStateException(pfx_log+" has mismatched SPID on recip "+recips_sent+"="
							+recip.domain_to+"/"+recip.qid
							+" - "+Integer.toHexString(recip.spid)+" vs "+Integer.toHexString(msgparams.getSPID()));
				}
				endpipe = sendPipelinedRequest(SMTPREQ_MAILTO, false, recip.mailbox_to, recip.domain_to, true);
				recips_sent++;
			}
			break;

		case A_DATA:
			if (isFlagSet(S2_SENT_DATACMD)) break; //in case we pipelined it before receiving all recip responses
			if (pipe_count != 0) {
				//in pipelined send-ahead mode - forego canned ByteBuffer, to piggyback reply on the buffered pipeline
				sendPipelinedRequest(Protocol.CMDREQ_DATA, true, null, null, false);
			} else {
				transmit(shared.reqbuf_data);
			}
			setFlag(S2_SENT_DATACMD);
			break;

		case A_MAILBODY:
			java.nio.file.Path fh_msg = shared.controller.getQueue().getMessage(msgparams.getSPID(), msgparams.getRecipient(0).qid);
			long msgbytes = java.nio.file.Files.size(fh_msg);
			long tmtnano = msgbytes * conncfg.nanosecs_per_byte;
			alt_tmtprotocol = tmtnano / (1000L * 1000L); //convert to millisecs
			if (alt_tmtprotocol < conncfg.tmtprotocol) alt_tmtprotocol = 0;
			try {
				getWriter().transmit(fh_msg);
			} catch (java.io.IOException ex) {
				if (java.nio.file.Files.exists(fh_msg, FileOps.LINKOPTS_NONE)) throw ex; //prob some temporary comms issue
				return issueDisconnect(Protocol.REPLYCODE_PERMERR_MISC, "Spool file missing", FAILMSG_NOSPOOL);
			}
			transmit(shared.reqbuf_eom);

			if (shared.transcript != null) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append("Sent message-body octets=").append(msgbytes).append(" for msgid=");
				shared.tmpsb.append(formatSPID(msgparams.getSPID()));
				shared.transcript.event(pfx_transcript, shared.tmpsb, getSystemTime());
			}
			break;

		case A_ENDMESSAGE:
			shared.controller.messageCompleted(this);
			if (msgparams.recipCount() == 0) return issueAction(PROTO_ACTION.A_QUIT, PROTO_STATE.S_QUIT);
			initMessage();
			LEVEL lvl = LEVEL.TRC2;
			if (getLogger().isActive(lvl)) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append(pfx_log).append(" follow-on msg #").append(msgparams.messageCount()).append(" - msgid=");
				shared.tmpsb.append(formatSPID(msgparams.getSPID())).append(", recips=").append(msgparams.recipCount());
				getLogger().log(lvl, shared.tmpsb);
			}
			issueAction(PROTO_ACTION.A_RESET, PROTO_STATE.S_RESET);
			break;

		case A_QUIT:
			if (conncfg.sendQUIT) transmit(shared.reqbuf_quit);
			if (!conncfg.awaitQUIT) issueDisconnect(0, "A_QUIT");
			break;

		case A_RESET:
			transmit(shared.reqbuf_reset);
			break;

		case A_ENDSESSION:
			issueDisconnect(0, "A_ENDSESSION");
			break;

		case A_DISCONNECT:
			endConnection(discmsg, rspdata);
			break;

		case A_STLS:
			transmit(shared.reqbuf_stls);
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

	private PROTO_STATE issueAction(PROTO_ACTION action, PROTO_STATE newstate)
		throws java.io.IOException
	{
		return issueAction(action, newstate, 0, null, null);
	}

	@Override
	protected void startedSSL() throws java.io.IOException
	{
		if (pstate == PROTO_STATE.S_DISCON) return; //we are about to close the connection
		eventRaised(PROTO_EVENT.E_SSL, null, null);
	}

	@Override
	protected void disconnectLingerDone(boolean ok, CharSequence info, Throwable ex)
	{
		if (shared.transcript == null) return;
		StringBuilder sb = shared.tmpsb;
		sb.setLength(0);
		sb.append("Disconnect linger ");
		if (ok) {
			sb.append("completed");
		} else {
			sb.append("failed");
			if (info != null) sb.append(" - ").append(info);
			if (ex != null) sb.append(" - ").append(ex);
		}
		shared.transcript.event(pfx_transcript, sb, getSystemTime());
	}

	private PROTO_STATE sendAuth(ByteArrayRef rspdata) throws java.io.IOException
	{
		com.grey.base.utils.ByteChars rspbuf = shared.tmpbc.clear();
		boolean auth_done = false;
		int step = auth_step++;

		if (auth_method == com.grey.base.sasl.SaslEntity.MECH.PLAIN) {
			int finalstep = (active_relay.auth_initrsp ? 1 : 2);
			auth_done = (step == finalstep);
			if (step == 0) {
				rspbuf.append(Protocol.CMDREQ_SASL_PLAIN);
				if (active_relay.auth_initrsp) {
					rspbuf.append(' ');
					shared.sasl_plain.init();
					shared.sasl_plain.setResponse(null, active_relay.usrnam, active_relay.passwd, rspbuf);
				}
			} else if (!auth_done) {
				shared.sasl_plain.init();
				shared.sasl_plain.setResponse(null, active_relay.usrnam, active_relay.passwd, rspbuf);
			}
		} else if (auth_method == com.grey.base.sasl.SaslEntity.MECH.EXTERNAL) {
			// we send a zero-length response (whether initial or not), to assume the derived authorization ID
			int finalstep = (active_relay.auth_initrsp ? 1 : 2);
			auth_done = (step == finalstep);
			if (step == 0) {
				rspbuf.append(Protocol.CMDREQ_SASL_EXTERNAL);
				if (active_relay.auth_initrsp) {
					rspbuf.append(' ').append(Protocol.AUTH_EMPTY);
				}
			} else if (!auth_done) {
				shared.sasl_external.init();
				shared.sasl_external.setResponse(null, rspbuf);
			}
		} else if (auth_method == com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5) {
			if (step == 0) {
				rspbuf.append(Protocol.CMDREQ_SASL_CMD5);
			} else if (step == 1) {
				rspdata.advance(Protocol.AUTH_CHALLENGE.length()); //advance past prefix
				rspdata.incrementSize(-Protocol.EOL.length()); //strip CRLF
				shared.sasl_cmd5.setResponse(active_relay.usrnam, active_relay.passwd, rspdata, rspbuf);
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

	private void setRecipientStatus(int idx, short statuscode, ByteArrayRef failmsg, boolean remote_rsp) throws java.io.IOException
	{
		if (idx == -1) {
			// apply this status to all recipients
			if (isFlagSet(S2_ABORT)) {
				// Any recipients who've already failed remain as failures, but recipients who had been marked as OK revert to
				// an unprocessed status, as we've clearly been interrupted before completing the message send.
				for (int idx2 = 0; idx2 != msgparams.recipCount(); idx2++) {
					com.grey.mailismus.mta.queue.MessageRecip recip = msgparams.getRecipient(idx2);
					if (recip.smtp_status == Protocol.REPLYCODE_OK) {
						recip.qstatus = com.grey.mailismus.mta.queue.MessageRecip.STATUS_READY;
					}
				}
				return;
			}

			for (int idx2 = 0; idx2 != msgparams.recipCount(); idx2++) {
				setRecipientStatus(idx2, statuscode, failmsg, remote_rsp);
			}
			return;
		}
		com.grey.mailismus.mta.queue.MessageRecip recip = msgparams.getRecipient(idx);
		if (!isFlagSet(S2_ABORT)) recip.qstatus = com.grey.mailismus.mta.queue.MessageRecip.STATUS_DONE;
		if (shared.routing.getInterceptor() != null) {
			//even though we generally log the theoretical destination address, we must audit the actual one
			recip.ip_send = shared.routing.getInterceptor().tsap.ip;
		} else {
			recip.ip_send = (remote_tsap == null ? 0 : remote_tsap.ip);
		}

		// this ensures that perm errors override preliminary temp errors, which in turn override preliminary success
		if (statuscode > recip.smtp_status) {
			if (recip.smtp_status == Protocol.REPLYCODE_OK) okrecips--; //retract an earlier success
			if (statuscode == Protocol.REPLYCODE_OK) okrecips++; //no status had been set yet
			recip.smtp_status = statuscode;

			// Set or clear the NDR diagnostic.
			// Note that we don't create the diagnostic-message file for NDRs themselves.
			if (recip.smtp_status == Protocol.REPLYCODE_OK) {
				if (recip.sender != null && recip.retrycnt != 0) {
					java.nio.file.Path fh = shared.controller.getQueue().getDiagnosticFile(recip.spid, recip.qid);
					Exception ex = FileOps.deleteFile(fh);
					if (ex != null) getLogger().warn(pfx_log+" failed to delete NDR-diagnostic="+fh.getFileName()+" - "+ex);
				}
			} else {
				LEVEL lvl = LEVEL.TRC2;
				if (remote_rsp && getLogger().isActive(lvl)) {
					int len = failmsg.size();
					while (failmsg.buffer()[failmsg.offset()+len-1] < ' ') len--; //strip trailing CRLF
					StringBuilder sb = shared.tmpsb;
					sb.setLength(0);
					sb.append(pfx_log).append(" rejected on recip ").append(idx+1).append('/').append(msgparams.recipCount());
					sb.append('=').append(msgparams.getRecipient(idx).mailbox_to).append(EmailAddress.DLM_DOM).append(msgparams.getRecipient(idx).domain_to);
					sb.append(" - ").append(shared.tmplightbc.set(failmsg.buffer(), failmsg.offset(), len));
					getLogger().log(lvl, sb);
				}
				int diaglen = (failmsg == null ? 0 : failmsg.size());
				if (recip.sender == null || recip.smtp_status <= Protocol.REPLYCODE_OK) diaglen = 0;
				while (diaglen != 0 && failmsg.buffer()[failmsg.offset()+diaglen-1] <= ' ') diaglen--;
				if (diaglen != 0) {
					java.io.OutputStream fstrm = null;
					try {
						fstrm = shared.controller.getQueue().createDiagnosticFile(recip.spid, recip.qid);
						if (failmsg.byteAt(0) >= '1' && failmsg.byteAt(0) <= '5') {
							//prefix message with the IP address we failed to send to
							com.grey.base.utils.IP.ip2net(recip.ip_send, shared.ipbuf, 0);
							fstrm.write(1);
							fstrm.write(shared.ipbuf);
						}
						fstrm.write(failmsg.buffer(), failmsg.offset(), diaglen);
					} catch (Exception ex) {
						getLogger().log(LEVEL.WARN, ex, true, pfx_log+" failed to set failure reason for "+recip);
					} finally {
						if (fstrm != null) fstrm.close();
					}
				}
			}
		}
	}

	// NB: We can use shared.pipebuf because the pipeline is always built up and sent within one callback
	private boolean sendPipelinedRequest(com.grey.base.utils.ByteChars cmd, boolean flush, com.grey.base.utils.ByteChars addr,
			com.grey.base.utils.ByteChars domain, boolean close_brace) throws java.io.IOException
	{
		com.grey.base.utils.ByteChars rspbuf = shared.pipebuf;
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

	private void transmit(com.grey.base.utils.ByteChars data) throws java.io.IOException
	{
		shared.tmpniobuf = shared.bufspec.encode(data, shared.tmpniobuf);
		transmit(shared.tmpniobuf);
	}

	private void transmit(java.nio.ByteBuffer xmtbuf) throws java.io.IOException
	{
		if (shared.transcript != null && pstate != PROTO_STATE.S_MAILBODY) {
			shared.transcript.data_out(pfx_transcript, xmtbuf, 0, getSystemTime());
		}
		xmtbuf.position(0);
		getWriter().transmit(xmtbuf);
		dataWait++;
	}

	private short getReplyCode(ByteArrayRef rsp)
	{
		int off = rsp.offset();
		short statuscode = (short)((rsp.buffer()[off++] - '0') * 100);
		statuscode += (rsp.buffer()[off++] - '0') * 10;
		statuscode += (rsp.buffer()[off] - '0');
		return statuscode;
	}

	private boolean matchesExtension(ByteArrayRef data, char[] cmd, boolean with_equals)
	{
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

	private void initConnection()
	{
		conncfg = shared.defaultcfg;
		pipe_cap = 1;
		remote_tsap = null;
		active_relay = null;
		pstate = PROTO_STATE.S_DISCON;
		disconnect_status = 0;
		dataWait = 0;
		alt_tmtprotocol = 0;
		mxptr = 0;
		dnsInfo.clear();
		cnxid++;
		initMessage();
		initChannelMonitor();
		setLogPrefix();
	}

	private void initMessage()
	{
		state2 = 0;
		recip_id = 0;
		recips_sent = 0;
		okrecips = 0;
		pipe_count = 0;
	}

	private void setLogPrefix() {
		int pos = shared.prototype_client.pfx_log.lastIndexOf('E');
		pfx_log = shared.prototype_client.pfx_log.substring(0, pos+1)+getCMID()+"-"+cnxid;
		pos = pfx_log.lastIndexOf('E');
		pfx_transcript = pfx_log.substring(pos);
	}

	private CharSequence formatSPID(int spid) {
		return shared.controller.getQueue().externalSPID(spid);
	}

	@Override
	public short getDomainError()
	{
		if (!isFlagSet(S2_DOMAIN_ERR)) return 0;
		return msgparams.getRecipient(0).smtp_status;
	}

	private void recordConnection(StringBuilder sb, com.grey.base.utils.TSAP local_tsap)
	{
		sb.append(remote_tsap.dotted_ip).append(':').append(remote_tsap.port);
		if (local_tsap != null) sb.append(" on ").append(local_tsap.dotted_ip).append(':').append(local_tsap.port);
		sb.append(" for ");
		peerDescription(sb);
		sb.append(" with msgid=").append(formatSPID(msgparams.getSPID()));
		sb.append(", recips=").append(msgparams.recipCount());
	}

	private void peerDescription(StringBuilder sb)
	{
		if (msgparams.getRelay() != null) {
			sb.append("relay=").append(msgparams.getRelay().display());
		} else {
			sb.append("domain=").append(msgparams.getDestination());
		}
	}

	static com.grey.base.utils.IP.Subnet[] parseSubnets(com.grey.base.config.XmlConfig cfg, String fldnam, AppConfig appConfig)
		throws java.net.UnknownHostException
	{
		String[] arr = cfg.getTuple(fldnam, "|", false, null);
		if (arr == null) return null;
		java.util.ArrayList<com.grey.base.utils.IP.Subnet> lst = new java.util.ArrayList<com.grey.base.utils.IP.Subnet>();
		for (int idx = 0; idx != arr.length; idx++) {
			String val = arr[idx].trim();
			if (val.length() == 0) continue;
			if (appConfig != null) val = appConfig.parseHost(null, null, false, val);
			lst.add(com.grey.base.utils.IP.parseSubnet(val));
		}
		if (lst.size() == 0) return null;
		return lst.toArray(new com.grey.base.utils.IP.Subnet[lst.size()]);
	}

	@Override
	public StringBuilder dumpAppState(StringBuilder sb)
	{
		if (sb == null) sb = new StringBuilder();
		sb.append(pfx_log).append('/').append(pstate).append("/0x").append(Integer.toHexString(state2)).append(": ");
		peerDescription(sb);
		sb.append("; msgcnt=").append(msgparams.messageCount());
		sb.append("; recips=").append(recip_id).append('/').append(msgparams.recipCount());
		return sb;
	}

	@Override
	public String toString() {
		return getClass().getName()+"=E"+getCMID();
	}
}
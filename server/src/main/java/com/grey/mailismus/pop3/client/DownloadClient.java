/*
 * Copyright 2012-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.pop3.client;

import com.grey.logging.Logger;
import com.grey.logging.Logger.LEVEL;
import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.IP;
import com.grey.base.config.XmlConfig;
import com.grey.mailismus.Task;
import com.grey.mailismus.pop3.POP3Protocol;
import com.grey.naf.SSLConfig;
import com.grey.mailismus.mta.queue.QueueFactory;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.errors.MailismusException;

public class DownloadClient
	extends com.grey.naf.reactor.CM_Client
	implements com.grey.naf.reactor.TimerNAF.Handler
{
	private enum PROTO_STATE {S_DISCON, S_CONN, S_GREET, S_AUTH, S_STLS, S_CAPA, S_QUIT, S_STAT, S_RETR, S_DELE}
	private enum PROTO_EVENT {E_CONNECT, E_CONNECTED, E_DISCONNECT, E_DISCONNECTED, E_REPLY, E_LOGIN, E_STLS, E_CAPA,
								E_COUNTMESSAGES, E_GETMESSAGE, E_DELMESSAGE, E_QUIT, E_LOCALERROR}

	private static final byte CFG_PRESERVEMSGS = 1 << 0; //leave messages on server
	private static final byte CFG_OMITRCVHDR = 1 << 1;
	private static final byte CFG_SMTPMODE = 1 << 2;
	private static final byte CFG_SASLINITRSP = 1 << 3;
	private static final byte CFG_SENDCAPA = 1 << 4; //we don't analyse the response, so this is more for the benefit of transcripts
	private static final byte CFG_FULLTRANS = 1 << 5;

	private static final byte S2_MULTILINE = 1 << 0; //waiting for multi-line reply to complete
	private static final byte S2_HAVESTATUS = 1 << 1; //we've received the status code (line 1) of a multi-line reply
	private static final byte S2_NOTRANSCRIPT = 1 << 2;

	private static final byte[] ENDRSP = new com.grey.base.utils.ByteChars(POP3Protocol.ENDRSP).toArray();
	private static final byte[] STATUS_OK = new com.grey.base.utils.ByteChars(POP3Protocol.STATUS_OK).toArray();
	private static final byte[] STATUS_ERR = new com.grey.base.utils.ByteChars(POP3Protocol.STATUS_ERR).toArray();
	private static final byte[] STATUS_EMPTY = new com.grey.base.utils.ByteChars(POP3Protocol.STATUS_AUTH).toArray();

	private static final char[] HDR_MSGID = "Message-ID:".toUpperCase().toCharArray();
	private static final char[] HDR_FROM = "From:".toUpperCase().toCharArray();
	private static final char[] HDR_SENDER = "Sender:".toUpperCase().toCharArray();

	public static final class Results
	{
		public boolean completed_ok;
		public int msgcnt;
		public Results init() {completed_ok = false; msgcnt = 0; return this;}
	}

	static final class Common
	{
		final com.grey.base.sasl.PlainClient sasl_plain = new com.grey.base.sasl.PlainClient(true);
		final com.grey.base.sasl.CramMD5Client sasl_cmd5 = new com.grey.base.sasl.CramMD5Client(true);
		final com.grey.base.sasl.ExternalClient sasl_external = new com.grey.base.sasl.ExternalClient(true);
		final com.grey.mailismus.AppConfig appConfig;
		final com.grey.mailismus.ms.MessageStore ms;
		final com.grey.mailismus.directory.Directory dtory;
		final com.grey.mailismus.mta.queue.Manager qmgr;
		final com.grey.mailismus.Audit audit;
		final com.grey.mailismus.Transcript transcript;
		final com.grey.naf.BufferSpec bufspec;
		final java.security.MessageDigest md5proc;
		final java.io.File stagingDir;
		final boolean smtp_disabled;
		final long tmtprotocol;
		final long delay_chanclose; //has solved abort-on-close issues in the past

		// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned commands
		final java.nio.ByteBuffer reqbuf_quit;
		final java.nio.ByteBuffer reqbuf_stat;
		final java.nio.ByteBuffer reqbuf_stls;
		final java.nio.ByteBuffer reqbuf_capa;

		// temp work areas, pre-allocated for efficiency
		final StringBuilder tmpsb = new StringBuilder();
		final com.grey.base.utils.ByteChars tmpbc = new com.grey.base.utils.ByteChars();
		final com.grey.base.utils.ByteChars tmplightbc = new com.grey.base.utils.ByteChars(-1); //lightweight object without own storage
		final com.grey.base.utils.EmailAddress tmpemaddr = new com.grey.base.utils.EmailAddress();
		final com.grey.base.utils.TSAP tmptsap = new com.grey.base.utils.TSAP();
		final java.util.Calendar dtcal = TimeOps.getCalendar(null);
		java.nio.ByteBuffer tmpniobuf;

		public Common(XmlConfig cfg, com.grey.naf.reactor.Dispatcher dsptch, com.grey.mailismus.Task task)
			throws java.security.NoSuchAlgorithmException, java.io.IOException
		{
			appConfig = task.getAppConfig();
			tmtprotocol = cfg.getTime("timeout", com.grey.base.utils.TimeOps.parseMilliTime("4m"));
			delay_chanclose = cfg.getTime("delay_close", 0);
			bufspec = new com.grey.naf.BufferSpec(cfg, "niobuffers", 4*1024, 128); //always line-buffered
			audit = com.grey.mailismus.Audit.create("POP3-Download", "audit", dsptch, cfg);
			transcript = com.grey.mailismus.Transcript.create(dsptch, cfg, "transcript");
			ms = task.getMS();
			dtory = task.getDirectory();
			md5proc = java.security.MessageDigest.getInstance(com.grey.base.crypto.Defs.ALG_DIGEST_MD5);

			com.grey.base.config.XmlConfig[] smtpcfg = cfg.getSections("clients/client"+XmlConfig.XPATH_ENABLED+"/smtp"+XmlConfig.XPATH_ENABLED);
			smtp_disabled = (smtpcfg == null || smtpcfg.length == 0);
			qmgr = (smtp_disabled ? null : QueueFactory.init(dsptch, appConfig, task.getName()));

			reqbuf_quit = com.grey.mailismus.Task.constBuffer(POP3Protocol.CMDREQ_QUIT+POP3Protocol.EOL);
			reqbuf_stat = com.grey.mailismus.Task.constBuffer(POP3Protocol.CMDREQ_STAT+POP3Protocol.EOL);
			reqbuf_stls = com.grey.mailismus.Task.constBuffer(POP3Protocol.CMDREQ_STLS+POP3Protocol.EOL);
			reqbuf_capa = com.grey.mailismus.Task.constBuffer(POP3Protocol.CMDREQ_CAPA+POP3Protocol.EOL);

			String pthnam = dsptch.getApplicationContext().getConfig().getPathTemp()+"/pop3/downloadclient";
			stagingDir = new java.io.File(pthnam);

			String pfx = "POP3-Clients: ";
			dsptch.getLogger().info(pfx+"timeout="+com.grey.base.utils.TimeOps.expandMilliTime(tmtprotocol)
					+"; delay_close="+delay_chanclose);
			dsptch.getLogger().info(pfx+"staging-area="+stagingDir.getAbsolutePath());
			dsptch.getLogger().trace(pfx+bufspec);
		}

		public void stop(com.grey.naf.reactor.Dispatcher dsptch)
		{
			if (qmgr != null) qmgr.stop();
			if (transcript != null) transcript.close(System.currentTimeMillis());
			if (audit != null) audit.close();
		}
	}

	private static final int TMRTYPE_SESSIONTMT = 1;
	private static final int TMRTYPE_DISCON = 2;

	public final String client_id;
	public final long freq;
	public final int maxruns;

	private final DownloadClient.Common common;
	private final com.grey.base.utils.ByteChars localdest;
	private final String srvname;
	private final com.grey.base.utils.TSAP srvaddr;
	private final String remoteuser;
	private final com.grey.base.utils.ByteChars remotepass;
	private final POP3Protocol.AUTHTYPE[] authtypes;
	private final com.grey.naf.SSLConfig sslconfig;
	private final com.grey.base.utils.ByteChars smtp_sender;
	private final java.util.ArrayList<com.grey.base.utils.EmailAddress> smtp_recips;
	private final java.io.File dh_download; //destination directory, for download-to-file mode
	private final java.io.File fh_curmsg; //temp staging file into which we stream a downloaded message
	private final com.grey.base.utils.ByteChars greetmsg = new com.grey.base.utils.ByteChars();
	private final Results results;
	private final byte cfgflags;

	private PROTO_STATE pstate;
	private byte state2; //secondary-state, qualifying some of the pstate phases
	private int dataWait;
	private byte prevrspbyte; //guards against IOExecReader passing us incomplete lines, if receive buffer too small
	private int runcnt;
	private int msgcnt;
	private int actionseq;
	private byte authstep;
	private com.grey.naf.reactor.TimerNAF tmr_exit;
	private com.grey.naf.reactor.TimerNAF tmr_sesstmt;
	private java.io.FileOutputStream strm_curmsg;
	private com.grey.mailismus.mta.queue.SubmitHandle smtp_msgh;
	private int download_cnt; //counter for use with dh_download, to generate unique filenames
	private int cnxid; //increments with each incarnation - useful for distinguishing Transcript logs
	private String pfx_log;
	private String pfx_transcript;

	private String msghdr_msgid;
	private String msghdr_from;
	private String msghdr_sender;
	private boolean in_headers;
	private int rcvhdrsiz;

	private void setState2(byte f) {state2 |= f;}
	private void clearState2(byte f) {state2 &= ~f;}
	private boolean isState2(byte f) {return ((state2 & f) != 0);}

	private boolean isConfig(byte f) {return ((cfgflags & f) != 0);}
	public Results getResults() {return results;}
	public int getRunCount() {return runcnt;}

	@Override
	protected com.grey.naf.SSLConfig getSSLConfig() {return sslconfig;}

	public DownloadClient(com.grey.naf.reactor.Dispatcher d, DownloadClient.Common commondefs, XmlConfig cfg,
							String id, int srvport, boolean record_results) throws java.io.IOException
	{
		super(d, commondefs.bufspec, commondefs.bufspec);
		common = commondefs;
		client_id = id;
		results = (record_results ? new Results() : null);
		pfx_transcript = "E"+getCMID();
		pfx_log = "POP3-Client="+client_id+"/"+pfx_transcript;

		byte fcfg = 0;
		localdest = new com.grey.base.utils.ByteChars(cfg.getValue("@recip", true, null));
		freq = cfg.getTime("@freq", TimeOps.parseMilliTime("5m"));
		maxruns = cfg.getInt("@runs", false, freq == 0 ? 1 : 0);
		if (cfg.getBool("@capa", false)) fcfg |= CFG_SENDCAPA;
		if (cfg.getBool("@preserve", false)) fcfg |= CFG_PRESERVEMSGS;
		if (cfg.getBool("@omitreceivedheader", false)) fcfg |= CFG_OMITRCVHDR;
		if (cfg.getBool("@fulltrans", false)) fcfg |= CFG_FULLTRANS;

		String pthnam = d.getApplicationContext().getConfig().getPath(cfg, "downloads_directory", null, false, null, null);
		if (pthnam != null) pthnam = pthnam.replace("%U%", localdest);
		dh_download = (pthnam == null ? null : new java.io.File(pthnam));

		// get server details
		XmlConfig servercfg = cfg.getSection("server");
		srvname = servercfg.getValue("@address", true, null);
		remoteuser = servercfg.getValue("username", false, ""); //not required for SASL-External (in fact, ignored)
		remotepass = new com.grey.base.utils.ByteChars(servercfg.getValue("password", false, ""));
		// construct SSL config, which potentially depends on server details
		XmlConfig sslcfg = cfg.getSection("ssl");
		if (sslcfg == null || !sslcfg.exists()) {
			sslconfig = null;
		} else {
			sslconfig = new SSLConfig.Builder()
					.withPeerCertName(srvname)
					.withIsClient(true)
					.withXmlConfig(sslcfg, getDispatcher().getApplicationContext().getConfig())
					.build();
		}
		// and finalise the server address, which depends on our SSL mode
		srvaddr = com.grey.base.utils.TSAP.build(srvname, sslconfig == null || sslconfig.isLatent() ? POP3Protocol.TCP_PORT : POP3Protocol.TCP_SSLPORT, true);
		if (srvport != 0) srvaddr.set(srvaddr.ip, srvport, true);

		String dlm = "|";
		String[] atypes = servercfg.getTuple("authtypes", dlm, true, POP3Protocol.AUTHTYPE.USERPASS.toString()+dlm+POP3Protocol.AUTHTYPE.SASL_PLAIN.toString());
		java.util.ArrayList<POP3Protocol.AUTHTYPE> authlst = new java.util.ArrayList<POP3Protocol.AUTHTYPE>();
		for (int idx = 0; idx != atypes.length; idx++) {
			POP3Protocol.AUTHTYPE authtype = POP3Protocol.AUTHTYPE.valueOf(atypes[idx].toUpperCase());
			if (remoteuser.length() == 0 && authtype != POP3Protocol.AUTHTYPE.SASL_EXTERNAL) {
				getLogger().warn(pfx_log+": Discarding authtype="+authtype+" as no remote username specified");
				continue;
			}
			authlst.add(authtype);
		}
		if (authlst.size() == 0) {
			throw new MailismusConfigException("POP3 Download: No valid logon methods defined");
		}
		authtypes = authlst.toArray(new POP3Protocol.AUTHTYPE[authlst.size()]);
		boolean initrsp = servercfg.getBool("authtypes/@initrsp", false);
		if (initrsp) fcfg |= CFG_SASLINITRSP;

		XmlConfig smtpcfg = (common.smtp_disabled ? null : cfg.getSection("smtp"+XmlConfig.XPATH_ENABLED));
		boolean smtp_mode = (smtpcfg == null ? false : smtpcfg.exists());
		if (smtp_mode) fcfg |= CFG_SMTPMODE;

		if (smtp_mode) {
			String val = smtpcfg.getValue("sender", true, null);
			smtp_sender = new com.grey.base.utils.ByteChars(val);
			smtp_recips = new java.util.ArrayList<com.grey.base.utils.EmailAddress>();
			com.grey.base.utils.EmailAddress emaddr = new com.grey.base.utils.EmailAddress(localdest);
			emaddr.decompose();
			if (common.dtory != null && common.dtory.isLocalDomain(emaddr.domain)) {
				com.grey.base.utils.ByteChars alias = common.dtory.isLocalAlias(emaddr);
				if (alias != null) {
					emaddr = null;
					java.util.ArrayList<com.grey.base.utils.ByteChars> aliases = common.dtory.expandAlias(alias);
					java.util.Iterator<com.grey.base.utils.ByteChars> it = aliases.iterator();
					while (it.hasNext()) {
						com.grey.base.utils.EmailAddress addr = new com.grey.base.utils.EmailAddress(it.next());
						smtp_recips.add(addr);
					}
					if (smtp_recips.size() == 0) {
						throw new MailismusConfigException("POP3 Download: Recipient alias="+alias+" is empty");
					}
				} else {
					emaddr.set(emaddr.mailbox);
				}
			}
			if (emaddr != null) smtp_recips.add(emaddr);
		} else {
			if (dh_download == null) {
				if (common.ms == null || common.dtory == null) {
					throw new MailismusConfigException("POP3 Download: Message-Store and Directory must be configured,"
							+" unless in SMTP-injection mode or Download-to-file mode");
				}
				if (!common.dtory.isLocalUser(localdest)) {
					throw new MailismusConfigException("POP3 Download: Recipient="+localdest+" is not a local username");
				}
			}
			smtp_sender = null;
			smtp_recips = null;
		}
		cfgflags = fcfg;
		fh_curmsg = new java.io.File(common.stagingDir, "smtpmsg_e"+getCMID());
		String deststr = (dh_download == null ? "User="+localdest : "Directory="+dh_download.getCanonicalPath());
		getLogger().info(pfx_log+": Remote-User="+remoteuser+" on "+srvaddr+" => Local-"+deststr);
		getLogger().info(pfx_log+"Declare self as '"+common.appConfig.getProductName()+"' on "+common.appConfig.getAnnounceHost());
		getLogger().info(pfx_log+": freq="+TimeOps.expandMilliTime(freq)
				+"; runs="+(maxruns==0?"unlimited":maxruns)
				+"; auth="+authtypes.length+"/"+authlst+"/initrsp="+isConfig(CFG_SASLINITRSP));
		if (isConfig(CFG_SMTPMODE)) getLogger().info(pfx_log+": SMTP "+smtp_sender+" => "+smtp_recips);
		if (sslconfig != null) getLogger().info(pfx_log+": "+sslconfig);
		getLogger().info(pfx_log+": preserve-messages="+isConfig(CFG_PRESERVEMSGS)
				+"; omit-rcvhdr="+isConfig(CFG_OMITRCVHDR));
	}

	public void start(com.grey.naf.EntityReaper rpr) throws java.io.IOException
	{
		cnxid++;
		int pos = pfx_log.lastIndexOf('-');
		String stem = (cnxid == 1 ? pfx_log+"-" : pfx_log.substring(0, pos + 1));
		pfx_log = stem+cnxid;
		pos = pfx_log.lastIndexOf("/E");
		pfx_transcript = pfx_log.substring(pos+1);
		pstate = PROTO_STATE.S_DISCON;
		state2 = 0;
		dataWait = 0;
		greetmsg.clear();
		initChannelMonitor();
		setReaper(rpr);
		if (results != null) results.init();
		runcnt++;
		issueAction(PROTO_EVENT.E_CONNECT, PROTO_STATE.S_CONN, null, null);
	}

	public boolean stop()
	{
		if (pstate == PROTO_STATE.S_DISCON) return (tmr_exit == null); //we're already completely stopped
		issueDisconnect("Shutting down");
		return false; //we will call back to reaper explicitly in ChannelMonitor.disconnect()
	}

	@Override
	protected void connected(boolean success, CharSequence diag, Throwable exconn) throws java.io.IOException
	{
		if (tmr_exit != null) return; //we've been told to stop
		if (!success) {
			connectionFailed(diag==null?"connect-fail":diag, exconn);
			return;
		}
		Logger.LEVEL loglvl = Logger.LEVEL.TRC;

		if (getLogger().isActive(loglvl) || (common.transcript != null)) {
			com.grey.base.utils.TSAP local_tsap = com.grey.base.utils.TSAP.get(getLocalIP(), getLocalPort(), common.tmptsap, true);
			if (getLogger().isActive(loglvl)) {
				common.tmpsb.setLength(0);
				common.tmpsb.append(pfx_log).append(" connected to ");
				common.tmpsb.append(srvaddr.dotted_ip).append(':').append(srvaddr.port);
				common.tmpsb.append(" on ").append(local_tsap.dotted_ip).append(':').append(local_tsap.port);
				getLogger().log(loglvl, common.tmpsb);
			}
			if (common.transcript != null) common.transcript.connection_out(pfx_transcript, local_tsap.dotted_ip, local_tsap.port,
																srvaddr.dotted_ip, srvaddr.port, getSystemTime(), null, usingSSL());
		}
		eventRaised(PROTO_EVENT.E_CONNECTED, null, null);
	}

	private void connectionFailed(CharSequence diagnostic, Throwable exconn)
	{
		if (common.transcript != null) {
			common.transcript.connection_out(pfx_transcript, null, 0, srvaddr.dotted_ip, srvaddr.port, getSystemTime(), null, usingSSL());
		}
		LEVEL loglvl = (MailismusException.isError(exconn) ? LEVEL.WARN : LEVEL.TRC);

		if (getLogger().isActive(loglvl)) {
			common.tmpsb.setLength(0);
			common.tmpsb.append(pfx_log).append(" failed to connect to ");
			common.tmpsb.append(srvaddr.dotted_ip).append(':').append(srvaddr.port);
			if (diagnostic != null) common.tmpsb.append(" - ").append(diagnostic);
			if (exconn == null) {
				getLogger().log(loglvl, common.tmpsb);
			} else {
				getLogger().log(loglvl, exconn, loglvl == LEVEL.WARN, common.tmpsb);
			}
		}
		issueDisconnect(diagnostic);
	}

	private PROTO_STATE sessionFailed(CharSequence discmsg, CharSequence logmsg, Logger.LEVEL lvl)
	{
		if (getLogger().isActive(lvl)) {
			String context = " - state="+pstate+", step="+actionseq+"/"+authstep;
			logmsg = (logmsg == null ? "" : " - "+logmsg);
			getLogger().log(lvl, pfx_log+": Download session failed - "+discmsg+context+logmsg);
		}
		return issueDisconnect(discmsg);
	}

	private void endConnection(CharSequence discmsg)
	{
		if (pstate == PROTO_STATE.S_DISCON) return; //shutdown events criss-crossing each other - that's ok

		if (tmr_sesstmt != null) {
			tmr_sesstmt.cancel();
			tmr_sesstmt = null;
		}

		if (strm_curmsg != null) {
			try {
				strm_curmsg.close();
			} catch (Exception ex) {
				getLogger().error(pfx_log+": Failed to close temp staging file - "+fh_curmsg.getAbsolutePath()+" - "+ex);
			}
			strm_curmsg = null;
		}
		if (fh_curmsg.exists()) {
			if (!fh_curmsg.delete()) {
				getLogger().error(pfx_log+": Failed to clear up temp staging file - "+fh_curmsg.getAbsolutePath());
			}
		}
		
		if (smtp_msgh != null) {
			//if an SMTP injection was currently in progress, then it's obviously been aborted
			common.qmgr.endSubmit(smtp_msgh, true);
			smtp_msgh = null;
		}

		// don't call disconnect() till next Dispatcher callback, to prevent reentrancy issues
		if (common.transcript != null) {
			CharSequence reason = "Disconnect";
			if (discmsg != null) {
				common.tmpsb.setLength(0);
				common.tmpsb.append(reason).append(" - ").append(discmsg);
				reason = common.tmpsb;
			}
			common.transcript.event(pfx_transcript, reason, getSystemTime());
		}
		dataWait = 0;
		transitionState(PROTO_STATE.S_DISCON);
		tmr_exit = getDispatcher().setTimer(common.delay_chanclose, TMRTYPE_DISCON, this);
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
			tmr_sesstmt = null;		// this timer is now expired, so we must not access it again
			sessionFailed("Timeout", null, Logger.LEVEL.INFO);
			break;

		default:
			getLogger().error(pfx_log+": Unexpected timer-type="+tmr.getType());
			break;
		}
	}

	@Override
	public void ioReceived(ByteArrayRef rcvdata) throws java.io.IOException
	{
		if (pstate == PROTO_STATE.S_DISCON) return; // this method can be called in a loop, so skip it after a disconnect
		if (common.transcript != null && !isState2(S2_NOTRANSCRIPT)) common.transcript.data_in(pfx_transcript, rcvdata, getSystemTime());
		eventRaised(PROTO_EVENT.E_REPLY, rcvdata, null);
	}

	@Override
	public void ioDisconnected(CharSequence diagnostic)
	{
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, diagnostic);
	}

	private void transitionState(PROTO_STATE newstate)
	{
		pstate = newstate;
	}

	private PROTO_STATE issueAction(PROTO_EVENT evt, PROTO_STATE newstate, ByteArrayRef rspdata, CharSequence discmsg)
			throws java.io.IOException
	{
		if (newstate != null) transitionState(newstate);
		return eventRaised(evt, rspdata, discmsg);
	}

	private PROTO_STATE eventRaised(PROTO_EVENT evt, ByteArrayRef rspdata, CharSequence discmsg) throws java.io.IOException
	{
		switch (evt)
		{
		case E_CONNECT:
			try {
				connect(srvaddr.sockaddr);
			} catch (Throwable ex) {
				connectionFailed("connect-error", ex);
			}
			break;

		case E_CONNECTED:
			transitionState(PROTO_STATE.S_GREET);
			waitResponse(false);
			prevrspbyte = '\n';
			break;

		case E_DISCONNECT:
		case E_DISCONNECTED:
			endConnection(discmsg);
			break;

		case E_REPLY:
			handleReply(rspdata);
			break;

		case E_STLS:
			transmit(common.reqbuf_stls, true, false);
			break;

		case E_LOGIN:
			actionseq = 0;
			authstep = 0;
			sendAuth(rspdata);
			break;

		case E_CAPA:
			transmit(common.reqbuf_capa, true, true);
			break;

		case E_QUIT:
			transmit(common.reqbuf_quit, true, false);
			break;

		case E_COUNTMESSAGES:
			transmit(common.reqbuf_stat, true, false);
			break;

		case E_GETMESSAGE:
			// kick off the download and accumulate it in the temp staging file
			if (!isConfig(CFG_OMITRCVHDR)) {
				common.dtcal.setTimeInMillis(getSystemTime());
				common.tmpsb.setLength(0);
				TimeOps.makeTimeRFC822(common.dtcal, common.tmpsb);
				common.tmpbc.populate("Received: from ").append(srvname).append(" [").append(srvaddr.dotted_ip).append("]\r\n");
				common.tmpbc.append("\tby ").append(common.appConfig.getAnnounceHost()).append(" (").append(common.appConfig.getProductName());
				common.tmpbc.append(") with ").append(isConfig(CFG_SMTPMODE)?"POP-to-SMTP":"POP3").append("\r\n");
				common.tmpbc.append("\tfor <").append(localdest).append(">; ").append(common.tmpsb).append("\r\n");
				rcvhdrsiz = common.tmpbc.size();
			}
			if (isConfig(CFG_SMTPMODE)) {
				smtp_msgh = common.qmgr.startSubmit(smtp_sender, smtp_recips, null, IP.IP_LOCALHOST);
				if (!isConfig(CFG_OMITRCVHDR)) smtp_msgh.write(common.tmpbc);
			} else {
				try {
					strm_curmsg = new java.io.FileOutputStream(fh_curmsg);
				} catch (Exception ex) {
					// assume first failure is due to non-existence of directory - a repeat failure is genuine
					FileOps.ensureDirExists(common.stagingDir);
					strm_curmsg = new java.io.FileOutputStream(fh_curmsg);
				}
				if (!isConfig(CFG_OMITRCVHDR)) strm_curmsg.write(common.tmpbc.buffer(), common.tmpbc.offset(), common.tmpbc.size());
			}
			common.tmpbc.populate(POP3Protocol.CMDREQ_RETR).append(' ').append(actionseq, common.tmpsb);
			transmit(common.tmpbc, true, true);
			if (!isConfig(CFG_FULLTRANS)) setState2(S2_NOTRANSCRIPT);
			msghdr_msgid = null;
			msghdr_from = null;
			msghdr_sender = null;
			in_headers = true;
			break;

		case E_DELMESSAGE:
			common.tmpbc.populate(POP3Protocol.CMDREQ_DELE).append(' ').append(actionseq, common.tmpsb);
			transmit(common.tmpbc, true, false);
			break;

		case E_LOCALERROR:
			issueDisconnect("Local Error - "+discmsg);
			break;

		default:
			getLogger().error(pfx_log+": Unrecognised event="+evt+" in state="+pstate);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised event="+evt+" in state="+pstate);
			break;
		}

		if (dataWait == 0) {
			if (tmr_sesstmt != null) {
				tmr_sesstmt.cancel();
				tmr_sesstmt = null;
			}
			if (pstate != PROTO_STATE.S_STLS) getReader().endReceive();
		} else {
			if (tmr_sesstmt == null) {
				// we're in a state that requires the timer, so if it doesn't exist, that's because it's not created yet - create now
				tmr_sesstmt = getDispatcher().setTimer(common.tmtprotocol, TMRTYPE_SESSIONTMT, this);
			} else {
				if (tmr_sesstmt.age(getDispatcher()) > Task.MIN_RESET_PERIOD) tmr_sesstmt.reset();
			}
			getReader().receiveDelimited((byte)'\n');
		}
		return pstate;
	}

	private PROTO_STATE handleReply(ByteArrayRef rspdata) throws java.io.IOException
	{
		boolean status_ok = true;
		boolean firstline = !isState2(S2_HAVESTATUS);
		boolean at_line_start = (prevrspbyte == '\n');
		prevrspbyte = (byte)rspdata.byteAt(rspdata.size()-1);

		if (firstline) {
			if (matchesResponse(STATUS_OK, rspdata, false)) {
				//advance past status code and spaces
				int off = rspdata.offset() + STATUS_OK.length;
				while (rspdata.buffer()[off] == ' ') off++;
				rspdata.advance(off - rspdata.offset());
				//and strip trailing white space
				rspdata.incrementSize(-POP3Protocol.EOL.length());
				while (rspdata.byteAt(rspdata.size() - 1) == ' ') rspdata.incrementSize(-1);
			} else if (matchesResponse(STATUS_ERR, rspdata, false)) {
				if (pstate != PROTO_STATE.S_AUTH) {
					return sessionFailed("Command rejected", new String(rspdata.buffer(), rspdata.offset(), rspdata.size()), Logger.LEVEL.INFO);
				}
				status_ok = false;
			} else if (pstate == PROTO_STATE.S_AUTH && matchesResponse(STATUS_EMPTY, rspdata, true)) {
				//ok, continue
			} else {
				return sessionFailed("Bad response - expected Status=OK/ERR", null, Logger.LEVEL.INFO);
			}
			setState2(S2_HAVESTATUS);
		}
		boolean is_complete = at_line_start && (!isState2(S2_MULTILINE) || matchesResponse(ENDRSP, rspdata, false));
		if (is_complete) {
			dataWait--;
		} else {
			 //first line of multi-line response is just the status - already checked
			if (firstline) return pstate;
		}

		switch (pstate)
		{
		case S_GREET:
			greetmsg.populateBytes(rspdata); //remember the server's greeting
			if (isConfig(CFG_SENDCAPA)) {
				issueAction(PROTO_EVENT.E_CAPA, PROTO_STATE.S_CAPA, null, null);
				break;
			}
			if (getSSLConfig() != null && !usingSSL()) {
				issueAction(PROTO_EVENT.E_STLS, PROTO_STATE.S_STLS, null, null);
			} else {
				issueAction(PROTO_EVENT.E_LOGIN, PROTO_STATE.S_AUTH, null, null);
			}
			break;

		case S_CAPA:
			if (!is_complete) break;
			if (getSSLConfig() != null && !usingSSL()) {
				issueAction(PROTO_EVENT.E_STLS, PROTO_STATE.S_STLS, null, null);
			} else {
				issueAction(PROTO_EVENT.E_LOGIN, PROTO_STATE.S_AUTH, null, null);
			}
			break;

		case S_STLS:
			startSSL();
			break;

		case S_AUTH:
			if (!status_ok) {
				// try the next configured authentication mechanism (if any)
				if (++actionseq == authtypes.length) {
					return sessionFailed("Authentication failed", null, Logger.LEVEL.INFO);
				}
				authstep = 0;
			}
			sendAuth(rspdata);
			break;

		case S_STAT:
			// we're only interested in how many messages there are
			int off = ByteOps.indexOf(rspdata.buffer(), rspdata.offset(), rspdata.size(), (byte)' ');
			common.tmplightbc.set(rspdata.buffer(), rspdata.offset(), off - rspdata.offset());
			msgcnt = (int)common.tmplightbc.parseDecimal();
			// start going through all the messages
			actionseq = 0;
			getNextMessage();
			break;

		case S_RETR:
			if (is_complete) {
				java.io.File fh_download = null;
				long msgsiz;
				// Deliver the message to its configured destination
				if (isConfig(CFG_SMTPMODE)) {
					java.nio.file.Path pth = common.qmgr.getMessage(smtp_msgh.spid, 0);
					msgsiz = java.nio.file.Files.size(pth);
					common.qmgr.endSubmit(smtp_msgh, false);
					smtp_msgh = null;
				} else {
					strm_curmsg.close();
					strm_curmsg = null;
					msgsiz = fh_curmsg.length();
					if (dh_download == null) {
						common.ms.deliver(localdest, fh_curmsg);
					} else {
						FileOps.ensureDirExists(dh_download);
						common.tmpsb.setLength(0);
						common.tmpsb.append('E').append(getCMID()).append('_').append(getSystemTime()).append('_').append(++download_cnt).append(".msg");
						fh_download = new java.io.File(dh_download, common.tmpsb.toString());
						FileOps.moveFile(fh_curmsg, fh_download);
					}
				}
				msgsiz -= rcvhdrsiz;
				Logger.LEVEL loglvl = Logger.LEVEL.TRC;

				if (common.transcript != null) {
					clearState2(S2_NOTRANSCRIPT);
					common.tmpsb.setLength(0);
					common.tmpsb.append("Received MessageBody octets=").append(msgsiz);
					common.transcript.event(pfx_transcript, common.tmpsb, getSystemTime());
				}
				if (common.audit != null || getLogger().isActive(loglvl)) {
					common.tmpsb.setLength(0);
					common.tmpsb.append("Downloaded ").append(srvname).append(':').append(srvaddr.port);
					if (usingSSL()) common.tmpsb.append('-').append("SSL");
					common.tmpsb.append('/').append(remoteuser).append(" => ");
					if (isConfig(CFG_SMTPMODE)) {
						common.tmpsb.append("SMTP=").append(smtp_recips);
					} else if (fh_download != null) {
						common.tmpsb.append("File=").append(fh_download.getAbsolutePath());
					} else {
						common.tmpsb.append("Mailbox=").append(localdest);
					}
					common.tmpsb.append("; MsgID=").append(msghdr_msgid);
					common.tmpsb.append("; Size=").append(msgsiz);
					common.tmpsb.append("; Sender=").append(msghdr_from == null ? msghdr_sender : msghdr_from);
					if (common.audit != null) {
						common.audit.log(common.tmpsb);
					}
					if (getLogger().isActive(loglvl)) {
						common.tmpsb.insert(0, ": ");
						common.tmpsb.insert(0, pfx_log);
						getLogger().log(loglvl, common.tmpsb);
					}
				}
				// Delete the message from the remote server
				if (isConfig(CFG_PRESERVEMSGS)) return getNextMessage(); //... unless we're in preserve mode
				return issueAction(PROTO_EVENT.E_DELMESSAGE, PROTO_STATE.S_DELE, null, null);
			}

			if (in_headers && at_line_start) {
				int char1 = rspdata.byteAt(0);
				if (char1 == '\n' || char1 == '\r') {
					in_headers = false;
				} else {
					String hdrval;
					if (msghdr_msgid == null && ((hdrval = getHeader(HDR_MSGID, rspdata)) != null)) {
						msghdr_msgid = hdrval;
					} else if (msghdr_from == null && ((hdrval = getHeader(HDR_FROM, rspdata)) != null)) {
						msghdr_from = hdrval;
					} else if (msghdr_from == null && msghdr_sender == null && ((hdrval = getHeader(HDR_SENDER, rspdata)) != null)) {
						msghdr_sender = hdrval;
					}
				}
			}

			if (isConfig(CFG_SMTPMODE)) {
				// continue SMTP injection
				smtp_msgh.write(rspdata);
			} else {
				// append to temp staging file
				strm_curmsg.write(rspdata.buffer(), rspdata.offset(), rspdata.size());
			}
			break;

		case S_DELE:
			// current message now processed, move on to next one
			getNextMessage();
			break;

		case S_QUIT:
			if (results != null) {
				results.completed_ok = true;
				results.msgcnt = msgcnt;
			}
			issueDisconnect(null);
			break;

		default:
			// this is an internal bug whereby we're missing a state case
			getLogger().error(pfx_log+": Unrecognised state="+pstate);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised state="+pstate);
			break;
		}
		return pstate;
	}

	private PROTO_STATE sendAuth(ByteArrayRef rspdata) throws java.io.IOException
	{
		boolean auth_done = false;
		int step = authstep++;

		if (authtypes[actionseq] == POP3Protocol.AUTHTYPE.USERPASS) {
			if (step == 0) {
				common.tmpbc.populate(POP3Protocol.CMDREQ_USER);
				common.tmpbc.append(' ').append(remoteuser);
			} else if (step == 1) {
				common.tmpbc.populate(POP3Protocol.CMDREQ_PASS);
				common.tmpbc.append(' ').append(remotepass);
			} else {
				auth_done = true;
			}
		} else if (authtypes[actionseq] == POP3Protocol.AUTHTYPE.APOP) {
			if (step == 0) {
				//isolate the final term in the greeting and append the remote password to it, then hash the combo
				int off = greetmsg.limit() - 1;
				while (greetmsg.buffer()[off] != ' ') off--;
				common.tmpbc.populate(greetmsg.buffer(), off+1, greetmsg.size() - (off - greetmsg.offset() + 1));
				common.tmpbc.append(remotepass);
				common.md5proc.reset();
				char cdigest[] = com.grey.base.crypto.Ascii.digest(common.tmpbc, common.md5proc);
				common.tmpbc.populate(POP3Protocol.CMDREQ_APOP);
				common.tmpbc.append(' ').append(remoteuser).append(' ');
				common.tmpbc.append(cdigest);
			} else {
				auth_done = true;
			}
		} else if (authtypes[actionseq] == POP3Protocol.AUTHTYPE.SASL_PLAIN) {
			int finalstep = (isConfig(CFG_SASLINITRSP) ? 1 : 2);
			auth_done = (step == finalstep);
			if (step == 0) {
				common.tmpbc.populate(POP3Protocol.CMDREQ_SASL_PLAIN);
				if (isConfig(CFG_SASLINITRSP)) {
					common.tmpbc.append(' ');
					common.sasl_plain.init();
					common.sasl_plain.setResponse(null, remoteuser, remotepass, common.tmpbc);
				}
			} else if (!auth_done) {
				common.tmpbc.clear();
				common.sasl_plain.init();
				common.sasl_plain.setResponse(null, remoteuser, remotepass, common.tmpbc);
			}
		} else if (authtypes[actionseq] == POP3Protocol.AUTHTYPE.SASL_EXTERNAL) {
			// we send a zero-length response (whether initial or not), to assume the derived authorization ID
			int finalstep = (isConfig(CFG_SASLINITRSP) ? 1 : 2);
			auth_done = (step == finalstep);
			if (step == 0) {
				common.tmpbc.populate(POP3Protocol.CMDREQ_SASL_EXTERNAL);
				if (isConfig(CFG_SASLINITRSP)) {
					common.tmpbc.append(' ').append(POP3Protocol.AUTH_EMPTY);
				}
			} else if (!auth_done) {
				common.tmpbc.clear();
				common.sasl_external.init();
				common.sasl_external.setResponse(null, common.tmpbc);
			}
		} else if (authtypes[actionseq] == POP3Protocol.AUTHTYPE.SASL_CRAM_MD5) {
			if (step == 0) {
				common.tmpbc.populate(POP3Protocol.CMDREQ_SASL_CMD5);
			} else if (step == 1) {
				rspdata.advance(POP3Protocol.STATUS_AUTH.length());  //advance past prefix
				rspdata.incrementSize(-POP3Protocol.EOL.length());  //strip CRLF
				common.sasl_cmd5.setResponse(remoteuser, remotepass, rspdata, common.tmpbc.clear());
			} else {
				auth_done = true;
			}
		} else {
			getLogger().error(pfx_log+": Missing case for authtype #"+actionseq+"="+authtypes[actionseq]);
			return raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Internal error - see logs");
		}

		if (auth_done) {
			return issueAction(PROTO_EVENT.E_COUNTMESSAGES, PROTO_STATE.S_STAT, null, null);
		}
		transmit(common.tmpbc, true, false);
		return pstate;
	}

	@Override
	protected void startedSSL() throws java.io.IOException
	{
		if (pstate == PROTO_STATE.S_DISCON) return;  //we are about to close the connection
		if (common.transcript != null) {
			common.transcript.event(pfx_transcript, "Switched to SSL mode", getSystemTime());
		}
		if (isConfig(CFG_SENDCAPA)) {
			issueAction(PROTO_EVENT.E_CAPA, PROTO_STATE.S_CAPA, null, null);
		} else {
			issueAction(PROTO_EVENT.E_LOGIN, PROTO_STATE.S_AUTH, null, null);
		}
	}

	@Override
	protected void disconnectLingerDone(boolean ok, CharSequence info, Throwable ex)
	{
		if (common.transcript == null) return;
		common.tmpsb.setLength(0);
		common.tmpsb.append("Disconnect linger ");
		if (ok) {
			common.tmpsb.append("completed");
		} else {
			common.tmpsb.append("failed");
			if (info != null) common.tmpsb.append(" - ").append(info);
			if (ex != null) common.tmpsb.append(" - ").append(ex);
		}
		common.transcript.event(pfx_transcript, common.tmpsb, getSystemTime());
	}

	private PROTO_STATE getNextMessage() throws java.io.IOException
	{
		if (actionseq++ == msgcnt) {
			// we've now downloaded and deleted all the messages
			Logger.LEVEL loglvl = (msgcnt == 0 ? Logger.LEVEL.TRC2 : Logger.LEVEL.INFO);
			if (getLogger().isActive(loglvl)) {
				common.tmpsb.setLength(0);
				common.tmpsb.append(pfx_log).append(": Downloaded messages=").append(msgcnt);
				getLogger().log(loglvl, common.tmpsb);
			}
			return issueAction(PROTO_EVENT.E_QUIT, PROTO_STATE.S_QUIT, null, null);
		}
		return issueAction(PROTO_EVENT.E_GETMESSAGE, PROTO_STATE.S_RETR, null, null);
	}

	private void transmit(com.grey.base.utils.ByteChars data, boolean expectrsp, boolean multiline) throws java.io.IOException
	{
		common.tmpbc.append(POP3Protocol.EOL);
		common.tmpniobuf = common.bufspec.encode(data, common.tmpniobuf);
		transmit(common.tmpniobuf, expectrsp, multiline);
	}

	private void transmit(java.nio.ByteBuffer xmtbuf, boolean expectrsp, boolean multiline) throws java.io.IOException
	{
		if (common.transcript != null) common.transcript.data_out(pfx_transcript, xmtbuf, 0, getSystemTime());
		if (expectrsp) waitResponse(multiline);
		xmtbuf.position(0);
		getWriter().transmit(xmtbuf);
	}

	private void waitResponse(boolean multiline)
	{
		if (multiline) {
			setState2(S2_MULTILINE);
		} else {
			clearState2(S2_MULTILINE);
		}
		clearState2(S2_HAVESTATUS);
		dataWait++;
	}

	private PROTO_STATE issueDisconnect(CharSequence diagnostic)
	{
		return raiseSafeEvent(PROTO_EVENT.E_DISCONNECT, null, diagnostic);
	}

	// this eliminate the Throws declaration for events that are known not to throw
	private PROTO_STATE raiseSafeEvent(PROTO_EVENT evt, ByteArrayRef rspdata, CharSequence discmsg)
	{
		try {
			eventRaised(evt, rspdata, discmsg);
		} catch (Exception ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(Logger.LEVEL.ERR, ex, true, pfx_log+" failed to issue event="+evt);
			endConnection("Failed to issue event="+evt+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
		return pstate;
	}

	//cmd includes the header name's terminating colon
	private String getHeader(char[] keyword, ByteArrayRef data)
	{
		if (data.size() < keyword.length) return null;
		final byte[] databuf = data.buffer();
		int off = data.offset();

		for (int idx = 0; idx != keyword.length; idx++) {
			if (Character.toUpperCase(databuf[off++]) != keyword[idx]) return null;
		}
		off = data.offset() + keyword.length;
		while (databuf[off] == ' ') off++;
		int len = data.size() - (off - data.offset());
		while (len != 0 && databuf[off + len - 1] <= ' ') len--; //strip trailing white space
		common.tmplightbc.set(databuf, off, len);
		common.tmpemaddr.parse(common.tmplightbc);
		return common.tmpemaddr.full.toString();
	}

	private static boolean matchesResponse(byte[] keyword, ByteArrayRef data, boolean simple)
	{
		if (data.size() < keyword.length) return false;
		final byte[] databuf = data.buffer();
		int off = data.offset();

		for (int idx = 0; idx != keyword.length; idx++) {
			if (databuf[off++] != keyword[idx]) return false;
		}
		if (simple || keyword[keyword.length-1] == '\n') return true;
		return (databuf[keyword.length] <= ' ');
	}

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex)
	{
		raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "State="+pstate+" - "+com.grey.base.ExceptionUtils.summary(ex));
	}

	@Override
	public StringBuilder dumpAppState(StringBuilder sb)
	{
		if (sb == null) sb = new StringBuilder();
		sb.append(pfx_log).append('/').append(pstate).append("/0x").append(Integer.toHexString(state2));
		sb.append(": msgcnt=").append(msgcnt);
		sb.append("; step=").append(actionseq);
		return sb;
	}

	@Override
	public String toString() {
		return getClass().getName()+"="+client_id+"/E"+getCMID();
	}
}
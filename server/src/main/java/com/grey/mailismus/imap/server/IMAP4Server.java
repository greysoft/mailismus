/*
 * Copyright 2013-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.logging.Logger.LEVEL;
import com.grey.base.config.SysProps;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.collections.NumberList;
import com.grey.mailismus.Task;
import com.grey.mailismus.ms.maildir.MaildirStore;
import com.grey.mailismus.ms.maildir.MimePart;
import com.grey.mailismus.imap.IMAP4Protocol;
import com.grey.mailismus.imap.server.Defs.PROTO_STATE;
import com.grey.mailismus.imap.server.Defs.PROTO_EVENT;
import com.grey.mailismus.imap.server.Defs.EnvelopeHeader;
import com.grey.mailismus.imap.server.Defs.FetchOpDef;
import com.grey.mailismus.imap.server.Defs.FetchOp;
import com.grey.mailismus.imap.server.Defs.SearchKey;

public final class IMAP4Server
	extends com.grey.naf.reactor.CM_Server
	implements com.grey.naf.reactor.TimerNAF.Handler,
		com.grey.mailismus.ms.maildir.MailboxSession.MessageTransmitter,
		com.grey.mailismus.ms.maildir.MailboxSession.UpdatesListener
{
	// This class maps the new Listener.Server design to the original prototype scheme on which
	// this server is still based.
	public static final class Factory
		implements com.grey.naf.reactor.ConcurrentListener.ServerFactory
	{
		private final IMAP4Server prototype;

		public Factory(com.grey.naf.reactor.CM_Listener l, Object cfg)
			throws java.io.IOException {
			com.grey.base.config.XmlConfig xmlcfg = (com.grey.base.config.XmlConfig)cfg;
			prototype = new IMAP4Server(l, xmlcfg);
		}

		@Override
		public IMAP4Server factory_create() {return new IMAP4Server(prototype);}
		@Override
		public Class<IMAP4Server> getServerClass() {return IMAP4Server.class;}
		@Override
		public void shutdown() {prototype.abortServer();}
	}

	private static final boolean UNILATERAL_REPORTS = SysProps.get("grey.imap.unilateral_reports", true);
	private static final boolean REPORT_ON_APPEND = SysProps.get("grey.imap.report_on_append", true);
	private static final boolean REPORT_ON_EXPUNGE = SysProps.get("grey.imap.report_on_expunge", true);
	private static final boolean REPORT_NEWFLAGS = SysProps.get("grey.imap.report_newflags", true);
	private static final boolean REPORT_UID = SysProps.get("grey.imap.report_uid", false);
	private static final boolean IGNORE_SET_RECENT = SysProps.get("grey.imap.ignore_set_recent", false);
	private static final boolean IMPLICIT_TEXT = SysProps.get("grey.imap.assumetext", true);
	private static final boolean SQUIRREL_FRIENDLY = SysProps.get("grey.imap.squirrelmail", true);

	private static final class FSM_Trigger
	{
		final PROTO_STATE state;
		final PROTO_EVENT evt;
		final com.grey.base.utils.ByteChars cmd;
		final PROTO_STATE new_state;

		FSM_Trigger(PROTO_STATE s, PROTO_EVENT e, com.grey.base.utils.ByteChars c, PROTO_STATE snew) {
			state = s;
			evt = e;
			cmd = c;
			new_state = snew;
		}
	}

	private static final FSM_Trigger[] fsmTriggers = {
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_LOGIN, IMAP4Protocol.CMDREQ_LOGIN, PROTO_STATE.S_SELECT),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_AUTHSASL, IMAP4Protocol.CMDREQ_AUTH, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_STLS, IMAP4Protocol.CMDREQ_STLS, PROTO_STATE.S_STLS),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_NAMSPC, IMAP4Protocol.CMDREQ_NAMSPC, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_LIST, IMAP4Protocol.CMDREQ_LIST, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_SELECT, IMAP4Protocol.CMDREQ_SELECT, PROTO_STATE.S_MAILBOX),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_EXAMINE, IMAP4Protocol.CMDREQ_EXAMINE, PROTO_STATE.S_MAILBOX),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_STATUS, IMAP4Protocol.CMDREQ_STATUS, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_CLOSE, IMAP4Protocol.CMDREQ_CLOSE, PROTO_STATE.S_SELECT),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_EXPUNGE, IMAP4Protocol.CMDREQ_EXPUNGE, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_STORE, IMAP4Protocol.CMDREQ_STORE, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_FETCH, IMAP4Protocol.CMDREQ_FETCH, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_SRCH, IMAP4Protocol.CMDREQ_SRCH, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_COPY, IMAP4Protocol.CMDREQ_COPY, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_UID, IMAP4Protocol.CMDREQ_UID, null),
		new FSM_Trigger(PROTO_STATE.S_MAILBOX, PROTO_EVENT.E_CHECK, IMAP4Protocol.CMDREQ_CHECK, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_CREATE, IMAP4Protocol.CMDREQ_CREATE, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_DELETE, IMAP4Protocol.CMDREQ_DELETE, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_RENAME, IMAP4Protocol.CMDREQ_RENAME, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_IDLE, IMAP4Protocol.CMDREQ_IDLE, PROTO_STATE.S_IDLE),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_APPEND, IMAP4Protocol.CMDREQ_APPEND, PROTO_STATE.S_APPEND),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_LSUB, IMAP4Protocol.CMDREQ_LSUB, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_SUBSCRIBE, IMAP4Protocol.CMDREQ_SUBSCRIBE, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_UNSUBSCRIBE, IMAP4Protocol.CMDREQ_UNSUBSCRIBE, null),
		new FSM_Trigger(PROTO_STATE.S_SELECT, PROTO_EVENT.E_UNSELECT, IMAP4Protocol.CMDREQ_UNSELECT, PROTO_STATE.S_SELECT),
		new FSM_Trigger(null, PROTO_EVENT.E_QUIT, IMAP4Protocol.CMDREQ_QUIT, null),
		new FSM_Trigger(null, PROTO_EVENT.E_CAPA, IMAP4Protocol.CMDREQ_CAPA, null),
		new FSM_Trigger(null, PROTO_EVENT.E_NOOP, IMAP4Protocol.CMDREQ_NOOP, null)
	};

	private static final byte[] FETCHMACRO_FAST = "FLAGS INTERNALDATE RFC822.SIZE".getBytes();
	private static final byte[] FETCHMACRO_ALL = "FLAGS INTERNALDATE RFC822.SIZE ENVELOPE".getBytes();
	private static final byte[] FETCHMACRO_FULL = "FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODY".getBytes();

	// We treat SentOn as On, SentSince as Since and SentBefore as Before, even though the first part of
	// each of those pairs is meant to be the Date header within the message.
	// Note that "OR", "BODY" and "TEXT" are marked as ignored. All other terms are handled.
	// Even if we were prepared to contemplate the expense of scanning the messages for a BODY or TEXT
	// search, we would require the far greater expense of base-64 decoding etc, so this is clearly only
	// appropriate for servers which load the content of new messages into a free-text database.
	private static final SearchKey[] searchKeys = new SearchKey[] {new SearchKey("charset", true, 1),
		new SearchKey("or", true, 2),
		new SearchKey("body", true, 1), new SearchKey("text", true, 1),
		new SearchKey("header", SearchKey.ARGTYPE.HEADER, 2),
		new SearchKey("From", SearchKey.ARGTYPE.HEADER), new SearchKey("To", SearchKey.ARGTYPE.HEADER),
		new SearchKey("Cc", SearchKey.ARGTYPE.HEADER), new SearchKey("Bcc", SearchKey.ARGTYPE.HEADER),
		new SearchKey("Subject", SearchKey.ARGTYPE.HEADER),
		new SearchKey("all", false, 0), //no-op, this is the default starting point anyway
		new SearchKey("answered", MaildirStore.MSGFLAG_REPLIED, false), new SearchKey("unanswered", MaildirStore.MSGFLAG_REPLIED, true),
		new SearchKey("deleted", MaildirStore.MSGFLAG_DEL, false), new SearchKey("undeleted", MaildirStore.MSGFLAG_DEL, true),
		new SearchKey("draft", MaildirStore.MSGFLAG_DRAFT, false), new SearchKey("undraft", MaildirStore.MSGFLAG_DRAFT, true),
		new SearchKey("flagged", MaildirStore.MSGFLAG_FLAGGED, false), new SearchKey("unflagged", MaildirStore.MSGFLAG_FLAGGED, true),
		new SearchKey("seen", MaildirStore.MSGFLAG_SEEN, false), new SearchKey("unseen", MaildirStore.MSGFLAG_SEEN, true),
		new SearchKey("recent", MaildirStore.MSGFLAG_RECENT, false), new SearchKey("old", MaildirStore.MSGFLAG_RECENT, true),
		new SearchKey("new", false, 0),
		new SearchKey("keyword", SearchKey.ARGTYPE.KWORD), new SearchKey("unkeyword", SearchKey.ARGTYPE.KWORD),
		new SearchKey("smaller", SearchKey.ARGTYPE.NUMBER), new SearchKey("larger", SearchKey.ARGTYPE.NUMBER),
		new SearchKey("on", SearchKey.ARGTYPE.DATE), new SearchKey("senton", "on"),
		new SearchKey("before", SearchKey.ARGTYPE.DATE), new SearchKey("sentbefore", "before"),
		new SearchKey("since", SearchKey.ARGTYPE.DATE), new SearchKey("sentsince", "since"),
		new SearchKey("uid", false, 1)
	};

	private static final int TMRTYPE_SESSIONTMT = 1;
	private static final int TMRTYPE_DISCON = 2;
	private static final int TMRTYPE_BULKCMD = 3;
	private static final int TMRTYPE_NEWMAIL = 4;

	//state2 flags
	private static final int S2_ENDED = 1 << 0; //we have already called endConnection()
	private static final int S2_REQWAIT = 1 << 1; //we're waiting for the remote client to send a request
	private static final int S2_DATAWAIT = 1 << 2; //we're in the middle of receiving a data stream
	private static final int S2_NOTRANSCRIPT = 1 << 3;

	// flags arg to getNextTerm()
	private static final int TERM_EMPTY = 1 << 0; //blank term is ok
	private static final int TERM_COPY = 1 << 1;

	private final SharedFields shared;
	private final com.grey.base.utils.TSAP remote_tsap = new com.grey.base.utils.TSAP();
	private final com.grey.base.utils.ByteChars reqtag = new com.grey.base.utils.ByteChars();
	private final java.io.File append_fh; //temp staging file into which we stream an Append message
	private com.grey.mailismus.ms.maildir.MailboxSession sess;
	private com.grey.base.sasl.SaslServer saslmech;
	private PROTO_STATE pstate;
	private int state2; //secondary-state, qualifying some of the pstate phases
	private com.grey.naf.reactor.TimerNAF tmr_exit;
	private com.grey.naf.reactor.TimerNAF tmr_sesstmt;
	private com.grey.naf.reactor.TimerNAF tmr_bulkcmd;
	private com.grey.naf.reactor.TimerNAF tmr_newmail;
	private int mbxprop_msgtotal;
	private int mbxprop_recent;
	private int mbxprop_numflags;
	private int cnxid; //increments with each incarnation - useful for distinguishing Transcript logs
	private String pfx_log;
	private String pfx_transcript;

	private BulkCommand currentBulkCmd;
	private final BulkCommand.CommandFetch cmdFetch;
	private final BulkCommand.CommandStore cmdStore;
	private final BulkCommand.CommandCopy cmdCopy;
	private final BulkCommand.CommandSearch cmdSearch;
	private final NumberList bulkseqlst = new NumberList();

	private String append_mbx;
	private String append_flags;
	private java.io.FileOutputStream append_strm;
	private int append_remainbytes;

	private void setState2(int f) {state2 |= f;}
	private void clearState2(int f) {state2 &= ~f;}
	private boolean isState2(int f) {return Defs.isFlagSet(state2, f);}

	// This is the constructor for the prototype server object
	public IMAP4Server(com.grey.naf.reactor.CM_Listener l, com.grey.base.config.XmlConfig cfg)
			throws java.io.IOException
	{
		super(l, null, null);
		append_fh = null;
		cmdFetch = null;
		cmdStore = null;
		cmdCopy = null;
		cmdSearch = null;
		String stem = "IMAP-Server";
		pfx_log = stem+"/E"+getCMID();
		String pfx = stem+(getSSLConfig() == null || getSSLConfig().latent ? "" : "/SSL")+": ";
		Task task = Task.class.cast(getListener().getController());
		shared = new SharedFields(cfg, getDispatcher(), task, this, getSSLConfig(), pfx);
		String txt = (shared.authtypes_ssl.size() == 0 ? null : shared.authtypes_ssl.size()+"/"+shared.authtypes_ssl);
		if (txt != null && shared.authtypes_ssl.size() == shared.authtypes_enabled.size()) txt = "all";
		getLogger().info(pfx+"authtypes="+shared.authtypes_enabled.size()+"/"+shared.authtypes_enabled+(txt==null?"":"; SSL-only="+txt));
		getLogger().info(pfx+"keywords-map="+shared.msgFlags.getMapFile()+"; dynamic="+shared.msgFlags.permitDynamic());
		getLogger().info(pfx+"IDLE capability "+(shared.capa_idle ? "enabled" : "disabled"));
		getLogger().info(pfx+"timeout="+TimeOps.expandMilliTime(shared.tmtprotocol)+"/auth="+TimeOps.expandMilliTime(shared.tmtauth)
				+"; newmailfreq="+TimeOps.expandMilliTime(shared.interval_newmail)
				+"; delay_close="+shared.delay_chanclose);
		getLogger().info(pfx+"batchsizes: nodisk="+shared.batchsize_nodisk+"; renames="+shared.batchsize_renames+"; fileio="+shared.batchsize_fileio);
		getLogger().info(pfx+"staging-area="+shared.stagingDir.getAbsolutePath());
		getLogger().info(pfx+"Declare self as '"+task.getAppConfig().getProductName()+"' on "+task.getAppConfig().getAnnounceHost());
		getLogger().trace(pfx+shared.bufspec);
	}

	// This is the constructor for the active server objects, which actually participate in IMAP sessions
	IMAP4Server(IMAP4Server proto)
	{
		super(proto.getListener(), proto.shared.bufspec, proto.shared.bufspec);
		shared = proto.shared;
		setLogPrefix();
		append_fh = new java.io.File(shared.stagingDir, "append_e"+getCMID());
		cmdFetch = new BulkCommand.CommandFetch();
		cmdStore = new BulkCommand.CommandStore();
		cmdCopy = new BulkCommand.CommandCopy();
		cmdSearch = new BulkCommand.CommandSearch();
	}

	@Override
	public boolean abortServer()
	{
		if (this == shared.prototype_server) {
			if (shared.transcript != null)	shared.transcript.close(getSystemTime());
			return true;
		}
		if (pstate == PROTO_STATE.S_DISCON) return (tmr_exit == null);
		issueDisconnect("Shutting down");
		return false; //we will call back to reaper explicitly (may even have done so already)
	}

	@Override
	protected void connected() throws java.io.IOException
	{
		pstate = PROTO_STATE.S_DISCON; //we are actually connected now, but this was starting point
		currentBulkCmd = null;
		saslmech = null;
		sess = null;
		state2 = 0;
		cnxid++;
		setLogPrefix();
		if (getLogger().isActive(LEVEL.TRC2) || (shared.transcript != null)) recordConnection();
		raiseEvent(PROTO_EVENT.E_CONNECTED, PROTO_STATE.S_AUTH, null);
	}

	@Override
	public void ioDisconnected(CharSequence diagnostic)
	{
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, diagnostic);
	}

	private void endConnection(CharSequence diagnostic)
	{
		if (isState2(S2_ENDED)) return; //shutdown events criss-crossing each other - that's ok
		setState2(S2_ENDED);

		if (tmr_sesstmt != null) {
			tmr_sesstmt.cancel();
			tmr_sesstmt = null;
		}
		if (tmr_bulkcmd != null) {
			tmr_bulkcmd.cancel();
			tmr_bulkcmd = null;
		}
		if (tmr_newmail != null) {
			tmr_newmail.cancel();
			tmr_newmail = null;
		}

		if (append_strm != null) {
			try {
				append_strm.close();
			} catch (Exception ex) {
				getLogger().error(pfx_log+": Failed to close temp Append staging file - "+append_fh.getAbsolutePath()+" - "+ex);
			}
			append_strm = null;
		}
		if (append_fh.exists()) {
			if (!append_fh.delete()) {
				getLogger().warn(pfx_log+": Failed to clear up temp Append staging file - "+append_fh.getAbsolutePath());
			}
		}

		if (sess != null) {
			sess.endSession();
			sess = null;
		}
		if (saslmech != null) releaseSASL();

		if (shared.transcript != null) {
			CharSequence reason = "Disconnect";
			if (diagnostic != null) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append(reason).append(" - ").append(diagnostic);
				reason = shared.tmpsb;
			}
			shared.transcript.event(pfx_transcript, reason, getSystemTime());
		}
		remote_tsap.clear();
		clearState2(S2_REQWAIT | S2_DATAWAIT);
		transitionState(PROTO_STATE.S_DISCON);
		// don't call base disconnect() till next Dispatcher callback, to prevent reentrancy issues
		tmr_exit = getDispatcher().setTimer(shared.delay_chanclose, TMRTYPE_DISCON, this);
	}

	private PROTO_STATE issueDisconnect(CharSequence diagnostic)
	{
		return raiseSafeEvent(PROTO_EVENT.E_DISCONNECT, null, diagnostic);
	}

	@Override
	public void ioReceived(ByteArrayRef rcvdata) throws java.io.IOException
	{
		if (shared.transcript != null && !isState2(S2_NOTRANSCRIPT)) shared.transcript.data_in(pfx_transcript, rcvdata, getSystemTime());
		if (pstate == PROTO_STATE.S_DISCON) return; //this method can be called in a loop, so skip it after a disconnect
		clearState2(S2_REQWAIT);

		if (pstate == PROTO_STATE.S_SASL) {
			stripEOL(rcvdata, false);
			handleEvent(PROTO_EVENT.E_SASLRSP, pstate, rcvdata, null);
			return;
		}

		if (pstate == PROTO_STATE.S_APPEND) {
			handleEvent(PROTO_EVENT.E_APPEND, pstate, rcvdata, null);
			return;
		}
		stripEOL(rcvdata, true);

		if (pstate == PROTO_STATE.S_IDLE) {
			handleEvent(PROTO_EVENT.E_IDLE, pstate, rcvdata, null);
			return;
		}

		//parse the tag and the command
		boolean ok = getNextTerm(rcvdata, reqtag, TERM_COPY);
		if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc, 0);

		if (ok) {
			//match command against the state machine
			int triggercnt = fsmTriggers.length;
			for (int idx = 0; idx != triggercnt; idx++) {
				FSM_Trigger trigger = fsmTriggers[idx];
				if (trigger.state == null || trigger.state.ordinal() <= pstate.ordinal()) {
					if ((trigger.cmd == null) || matchesCommand(shared.tmplightbc, trigger.cmd)) {
						PROTO_STATE prevstate = pstate;
						if (trigger.new_state != null) transitionState(trigger.new_state);
						try {
							handleEvent(trigger.evt, prevstate, rcvdata, null);
						} catch (Throwable ex) {
							pstate = prevstate;
							throw ex;
						}
						return;
					}
				}
			}
		}
		raiseEvent(PROTO_EVENT.E_BADCMD, null, "Invalid request in state="+pstate);
	}

	private void setReceiveMode() throws java.io.IOException
	{
		if (isState2(S2_REQWAIT | S2_DATAWAIT)) {
			long tmt = (sess == null ? shared.tmtauth : shared.tmtprotocol);
			if (tmr_sesstmt == null) {
				// we're in a state that requires the timer, so if it doesn't exist, that's because it's not created yet - create now
				if (tmt != 0) tmr_sesstmt = getDispatcher().setTimer(tmt, TMRTYPE_SESSIONTMT, this);
			} else {
				if (tmt != tmr_sesstmt.getInterval()) {
					tmr_sesstmt.reset(tmt);
				} else {
					if (tmr_sesstmt.age(getDispatcher()) > Task.MIN_RESET_PERIOD) tmr_sesstmt.reset();
				}
			}
			if (isState2(S2_DATAWAIT)) {
				getReader().receive(append_remainbytes);
			} else {
				getReader().receiveDelimited((byte)'\n');
			}
		} else {
			if (tmr_sesstmt != null) {
				tmr_sesstmt.cancel();
				tmr_sesstmt = null;
			}
			getReader().endReceive();
		}
	}

	private void transitionState(PROTO_STATE newstate)
	{
		pstate = newstate;
	}

	private PROTO_STATE raiseEvent(PROTO_EVENT evt, PROTO_STATE newstate, CharSequence diagnostic)
			throws java.io.IOException
	{
		PROTO_STATE prevstate = pstate;
		if (newstate != null) transitionState(newstate);
		return handleEvent(evt, prevstate, null, diagnostic);
	}

	// this eliminate the Throws declaration for events that are known not to throw
	private PROTO_STATE raiseSafeEvent(PROTO_EVENT evt, PROTO_STATE newstate, CharSequence diagnostic)
	{
		try {
			raiseEvent(evt, newstate, diagnostic);
		} catch (Throwable ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed to issue event="+evt);
			endConnection("Failed to issue event="+evt+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
		return pstate;
	}

	private PROTO_STATE issueResponse(boolean ok, PROTO_EVENT errtype, PROTO_STATE errstate, CharSequence diagnostic)
			throws java.io.IOException
	{
		if (ok) {
			transmit(IMAP4Protocol.STATUS_OK, null, null);
		} else {
			raiseEvent(errtype, errstate, diagnostic);
		}
		return pstate;
	}

	private PROTO_STATE handleEvent(PROTO_EVENT evt, PROTO_STATE prevstate, ByteArrayRef rcvdata, CharSequence diagnostic)
			throws java.io.IOException
	{
		com.grey.base.utils.ByteChars xmtbuf = shared.tmpbc.clear();
		PROTO_EVENT errtype = PROTO_EVENT.E_BADCMD;
		LEVEL lvl_folderops = LEVEL.TRC;
		String errmsg = "Invalid arguments";
		IMAP4Protocol.AUTHTYPE authtype;
		boolean ok;
		boolean is_valid;

		switch (evt)
		{
		case E_CONNECTED:
			transmit(shared.imap4rsp_greet, true);
			break;

		case E_DISCONNECTED:
		case E_DISCONNECT:
			endConnection(diagnostic);
			break;

		case E_STLS:
			if (getSSLConfig() == null || usingSSL()) {
				return raiseEvent(PROTO_EVENT.E_REJCMD, prevstate, usingSSL() ? "Already in SSL mode" : "SSL not supported");
			}
			transmit(IMAP4Protocol.STATUS_OK, null, null);
			startSSL();
			break;

		case E_LOGIN:
			if ((diagnostic = isEnabled(IMAP4Protocol.AUTHTYPE.LOGIN)) != null) {
				return raiseEvent(PROTO_EVENT.E_REJCMD, prevstate, diagnostic);
			}
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, 0);
			if (!ok) return raiseEvent(errtype, PROTO_STATE.S_AUTH, "Invalid Login parameters");
			is_valid = shared.dtory.passwordVerify(shared.tmplightbc, shared.tmplightbc2);
			processLogin(is_valid, shared.tmplightbc);
			break;

		case E_AUTHSASL:
			if (!getNextTerm(rcvdata, shared.tmplightbc, 0)) {
				return raiseEvent(errtype, prevstate, errmsg);
			}
			authtype = getAuthType(shared.tmplightbc);
			if ((diagnostic = isEnabled(authtype)) != null) {
				return raiseEvent(PROTO_EVENT.E_REJCMD, prevstate, diagnostic);
			}
			if (authtype == IMAP4Protocol.AUTHTYPE.SASL_PLAIN) {
				saslmech = shared.saslmechs_plain.extract().init();
			} else if (authtype == IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5) {
				saslmech = shared.saslmechs_cmd5.extract().init("IMAP", getCMID(), shared.tmpsb);
			} else if (authtype == IMAP4Protocol.AUTHTYPE.SASL_EXTERNAL) {
				saslmech = shared.saslmechs_ext.extract().init(getPeerCertificate());
			} else {
				// this is an internal bug whereby we're missing an Else clause
				getLogger().error(pfx_log+": Unrecognised SASL type="+authtype);
				return raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, prevstate, "Unrecognised SASL="+authtype);
			}
			if (rcvdata.size() != 0) {
				//an initial response was included with the Auth command
				if (!saslmech.requiresInitialResponse()) return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, "SASL initial-response not allowed");
				rcvdata.advance(1); //skip separator space to seek to start of initial-response param
				return handleEvent(PROTO_EVENT.E_SASLRSP, prevstate, rcvdata, null);
			}
			if (saslmech.sendsChallenge()) {
				xmtbuf.append(IMAP4Protocol.STATUS_CONTD);
				saslmech.setChallenge(xmtbuf);
				xmtbuf.append(IMAP4Protocol.EOL);
				transmit(xmtbuf, true);
			} else {
				//send empty initial challenge
				transmit(shared.imap4rsp_emptychallenge, true);
			}
			break;

		case E_SASLRSP:
			if (rcvdata.size() == 1 && rcvdata.byteAt(0) == IMAP4Protocol.AUTH_EMPTY) {
				//initial response is empty
				rcvdata.clear();
			}
			is_valid = saslmech.verifyResponse(rcvdata);
			com.grey.base.utils.ByteChars usrnam = saslmech.getUser();
			releaseSASL();
			processLogin(is_valid, usrnam);
			break;

		case E_CAPA:
			// See www.iana.org/assignments/imap4-capabilities/imap4-capabilities.xml
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(IMAP4Protocol.CMDREQ_CAPA);
			xmtbuf.append(' ').append(IMAP4Protocol.VERSION);
			if (shared.capa_idle) xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_IDLE);
			xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_NAMSPC);
			xmtbuf.append(' ').append(IMAP4Protocol.CAPA_TAG_CHILDREN);
			xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_UNSELECT);
			xmtbuf.append(' ').append(IMAP4Protocol.CAPA_TAG_LITERALPLUS);
			if (pstate == PROTO_STATE.S_AUTH) {
				if (getSSLConfig() != null && !usingSSL()) xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_STLS);
				if (isEnabled(IMAP4Protocol.AUTHTYPE.LOGIN) != null) xmtbuf.append(" LOGINDISABLED");
				if (isEnabled(IMAP4Protocol.AUTHTYPE.SASL_PLAIN) == null) xmtbuf.append(" AUTH=PLAIN");
				if (isEnabled(IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5) == null) xmtbuf.append(" AUTH=CRAM-MD5");
				if (isEnabled(IMAP4Protocol.AUTHTYPE.SASL_EXTERNAL) == null) xmtbuf.append(" AUTH=EXTERNAL");
			}
			transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
			break;

		case E_QUIT:
			LEVEL loglvl = LEVEL.TRC2;
			if (sess != null && getLogger().isActive(loglvl)) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append(pfx_log).append(": User=").append(sess.getUsername()).append(" logged out");
				getLogger().log(loglvl, shared.tmpsb);
			}
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(IMAP4Protocol.STATUS_BYE);
			transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
			issueDisconnect(null);
			break;

		case E_NAMSPC:
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(IMAP4Protocol.CMDREQ_NAMSPC);
			xmtbuf.append(" ((").append(Defs.CHAR_QUOTE).append(Defs.CHAR_QUOTE).append(' ');
			xmtbuf.append(Defs.CHAR_QUOTE).append(sess.getHierarchyDelimiter()).append(Defs.CHAR_QUOTE).append("))");
			xmtbuf.append(' ').append(Defs.DATA_NIL).append(' ').append(Defs.DATA_NIL);
			transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
			break;

		case E_CREATE:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok && StringOps.sameSeqNoCase(IMAP4Protocol.MBXNAME_INBOX, shared.tmplightbc)) ok = false;
			if (ok) {
				errmsg = "Mailbox already exists";
				ok = sess.createMailbox(shared.tmplightbc);
				if (ok && getLogger().isActive(lvl_folderops)) {
					shared.tmpsb.setLength(0);
					shared.tmpsb.append(pfx_log).append(": Created folder=").append(shared.tmplightbc);
					getLogger().log(lvl_folderops, shared.tmpsb);
				}
			}
			issueResponse(ok, errtype, prevstate, errmsg);
			break;

		case E_DELETE:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok && StringOps.sameSeqNoCase(IMAP4Protocol.MBXNAME_INBOX, shared.tmplightbc)) ok = false;
			if (ok) {
				errtype = PROTO_EVENT.E_REJCMD;
				errmsg = "Failed";
				ok = sess.deleteMailbox(shared.tmplightbc);
				if (ok && getLogger().isActive(lvl_folderops)) {
					shared.tmpsb.setLength(0);
					shared.tmpsb.append(pfx_log).append(": Deleted folder=").append(shared.tmplightbc);
					getLogger().log(lvl_folderops, shared.tmpsb);
				}
			}
			issueResponse(ok, errtype, prevstate, errmsg);
			break;

		case E_RENAME:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, 0);
			if (!ok || StringOps.sameSeqNoCase(IMAP4Protocol.MBXNAME_INBOX, shared.tmplightbc)
					|| StringOps.sameSeqNoCase(IMAP4Protocol.MBXNAME_INBOX, shared.tmplightbc2)) {
				return issueResponse(false, errtype, prevstate, errmsg);
			}
			int cnt = sess.renameMailbox(shared.tmplightbc, shared.tmplightbc2);
			if (cnt != 0 && getLogger().isActive(lvl_folderops)) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append(pfx_log).append(": Renamed folder=").append(shared.tmplightbc).append(" => ");
				shared.tmpsb.append(shared.tmplightbc2).append(" - total=").append(cnt);
				getLogger().log(lvl_folderops, shared.tmpsb);
			}
			issueResponse(cnt != 0, PROTO_EVENT.E_REJCMD, prevstate, "Failed");
			break;

		case E_LIST:
		case E_LSUB:
			ok = getNextTerm(rcvdata, shared.tmplightbc, TERM_EMPTY);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, TERM_EMPTY);
			if (!ok) return issueResponse(false, errtype, prevstate, errmsg);
			execList(evt, shared.tmplightbc, shared.tmplightbc2, xmtbuf, shared.tmpbc2);
			break;

		case E_SELECT:
		case E_EXAMINE:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) {
				errtype = PROTO_EVENT.E_REJCMD;
				if (sess.currentMailbox() != null) execClose(true);
				prevstate = PROTO_STATE.S_SELECT; //can't get back to prevstate=MAILBOX, now that mailbox is closed
				errmsg = execSelect(evt == PROTO_EVENT.E_EXAMINE, shared.tmplightbc, xmtbuf);
			}
			if (errmsg != null) issueResponse(false, errtype, prevstate, errmsg);
			break;

		case E_STATUS:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, TERM_EMPTY);
			if (ok) errmsg = execStatus(shared.tmplightbc, shared.tmplightbc2, xmtbuf);
			if (errmsg != null) return issueResponse(false, errtype, prevstate, errmsg);
			break;

		case E_CLOSE:
			execClose(true);
			transmit(IMAP4Protocol.STATUS_OK, null, null);
			break;

		case E_UNSELECT:
			execClose(false);
			transmit(IMAP4Protocol.STATUS_OK, null, null);
			break;

		case E_EXPUNGE:
			if (!sess.writeableMailbox()) return issueResponse(false, PROTO_EVENT.E_REJCMD, prevstate, "read-only mode");
			if (REPORT_ON_EXPUNGE) reportUpdatesAll(xmtbuf);
			sess.expungeMailbox(this, xmtbuf);
			if (xmtbuf != null) transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
			break;

		case E_FETCH:
		case E_STORE:
		case E_COPY:
		case E_SRCH:
			handleBulkCommand(evt, false, prevstate, rcvdata);
			break;

		case E_UID:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) {
				if (StringOps.sameSeqNoCase(IMAP4Protocol.CMDREQ_FETCH, shared.tmplightbc)) {
					evt = PROTO_EVENT.E_FETCH;
				} else if (StringOps.sameSeqNoCase(IMAP4Protocol.CMDREQ_STORE, shared.tmplightbc)) {
					evt = PROTO_EVENT.E_STORE;
				} else if (StringOps.sameSeqNoCase(IMAP4Protocol.CMDREQ_COPY, shared.tmplightbc)) {
					evt = PROTO_EVENT.E_COPY;
				} else if (StringOps.sameSeqNoCase(IMAP4Protocol.CMDREQ_SRCH, shared.tmplightbc)) {
					evt = PROTO_EVENT.E_SRCH;
				} else {
					ok = false;
					errmsg = "Invalid UID sub-command";
				}
			}
			if (!ok) return issueResponse(false, errtype, prevstate, errmsg);
			handleBulkCommand(evt, true, prevstate, rcvdata);
			break;

		case E_APPEND:
			if (prevstate == PROTO_STATE.S_APPEND) {
				if (append_strm == null) {
					// we've finished receiving the message and are waiting for the terminating CRLF
					transitionState(sess.currentMailbox() == null ? PROTO_STATE.S_SELECT : PROTO_STATE.S_MAILBOX);
					stripEOL(rcvdata, false);
					if (rcvdata.size() != 0) {
						return issueResponse(false, errtype, pstate, "Expected Append CRLF");
					}
					sess.injectMessage(append_fh, append_mbx, append_flags);
					if (!append_fh.delete()) {
						getLogger().warn(pfx_log+": Failed to clear up temp Append staging file - "+append_fh.getAbsolutePath());
					}
					//this is a good point at which to report updates, even if Append op wasn't on current mailbox
					if (REPORT_ON_APPEND && pstate == PROTO_STATE.S_MAILBOX) reportUpdatesAll(xmtbuf);
					transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
					break;
				}
				append_strm.write(rcvdata.buffer(), rcvdata.offset(), rcvdata.size());
				append_remainbytes -= rcvdata.size();
				if (append_remainbytes != 0) break;
				// We have now finished receiving the literal's octets, but we still have to to wait for
				// the CRLF that terminates the Append command - see RFC-3501 section 7.5
				clearState2(S2_DATAWAIT | S2_NOTRANSCRIPT);
				setState2(S2_REQWAIT); //not sending a response yet, but re-enable line-mode receive
				append_strm.close();
				append_strm = null;
				break;
			}
			com.grey.base.utils.ByteChars destmbx = shared.tmplightbc;
			com.grey.base.utils.ByteChars flags = shared.tmplightbc2;
			com.grey.base.utils.ByteChars dt = shared.tmplightbc3;
			com.grey.base.utils.ByteChars octetcnt = shared.tmplightbc4;
			ok = getNextTerm(rcvdata, destmbx, 0);
			if (ok) ok = getNextTerm(rcvdata, flags, TERM_EMPTY);
			if (ok) ok = getNextTerm(rcvdata, dt, TERM_EMPTY);
			if (ok) ok = getNextTerm(rcvdata, octetcnt, TERM_EMPTY);
			if (ok) {
				// all 3 params were quoted, so need to look behind the buffer's first char
				if (flags.offset() != 0 && flags.buffer()[flags.offset()-1] != '(') {
					octetcnt = dt;
					dt = flags;
					flags = null;
				}
				if (dt.size() != 0 && dt.offset() != 0 && dt.buffer()[dt.offset()-1] != Defs.CHAR_QUOTE) {
					octetcnt = dt;
					dt = null;
				}
				errtype = PROTO_EVENT.E_REJCMD;
				errmsg = execAppend(destmbx, flags, dt, octetcnt);
			}
			if (errmsg != null) return issueResponse(false, errtype, prevstate, errmsg);
			break;

		case E_IDLE:
			if (prevstate == PROTO_STATE.S_IDLE) {
				if (tmr_newmail != null) {
					tmr_newmail.cancel();
					tmr_newmail = null;
				}
				shared.tmplightbc.set(rcvdata);
				ok = StringOps.sameSeqNoCase(IMAP4Protocol.IDLE_DONE, shared.tmplightbc);
				transitionState(sess.currentMailbox() == null ? PROTO_STATE.S_SELECT : PROTO_STATE.S_MAILBOX);
				issueResponse(ok, errtype, pstate, "Expect DONE in IDLE state");
				break;
			}
			if (rcvdata.size() != 0 || !shared.capa_idle) {
				if (!shared.capa_idle) errmsg = "IDLE not supported";
				return issueResponse(false, errtype, prevstate, errmsg);
			}
			if (shared.interval_newmail != 0 && prevstate == PROTO_STATE.S_MAILBOX && sess.writeableMailbox()) {
				tmr_newmail = getDispatcher().setTimer(shared.interval_newmail, TMRTYPE_NEWMAIL, this);
			}
			transmit(shared.imap4rsp_contd_ready, true);
			break;

		case E_CHECK:
		case E_NOOP:
			if (pstate == PROTO_STATE.S_MAILBOX) {
				reportUpdatesAll(xmtbuf);
			}
			transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
			break;

		case E_SUBSCRIBE:
		case E_UNSUBSCRIBE:
			// null op
			transmit(IMAP4Protocol.STATUS_OK, null, null);
			break;

		case E_REJCMD:
			transmit(IMAP4Protocol.STATUS_REJ, null, diagnostic);
			break;

		case E_BADCMD:
			transmit(IMAP4Protocol.STATUS_ERR, null, diagnostic);
			break;

		case E_LOCALERROR:
			issueDisconnect("Local Error - "+diagnostic);
			break;

		default:
			// this is an internal bug whereby we're missing a case label
			getLogger().error(pfx_log+": Unrecognised event="+evt);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised event="+evt);
			break;
		}
		setReceiveMode();
		return pstate;
	}

	private void handleBulkCommand(PROTO_EVENT evt, boolean uidmode, PROTO_STATE prevstate, ByteArrayRef rcvdata) throws java.io.IOException
	{
		PROTO_EVENT errtype = PROTO_EVENT.E_BADCMD;
		String errmsg = "Invalid arguments";
		boolean ok;

		switch (evt)
		{
		case E_FETCH:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, TERM_EMPTY);
			if (ok) errmsg = execFetch(uidmode, shared.tmplightbc, shared.tmplightbc2);
			if (errmsg != null) issueResponse(false, errtype, prevstate, errmsg);
			break;

		case E_STORE:
			if (!sess.writeableMailbox()) {
				issueResponse(false, PROTO_EVENT.E_REJCMD, prevstate, "read-only mode");
				break;
			}
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc3, TERM_EMPTY);
			if (ok) errmsg = execStore(uidmode, shared.tmplightbc, shared.tmplightbc2, shared.tmplightbc3);
			if (errmsg != null) issueResponse(false, errtype, prevstate, errmsg);
			break;

		case E_COPY:
			ok = getNextTerm(rcvdata, shared.tmplightbc, 0);
			if (ok) ok = getNextTerm(rcvdata, shared.tmplightbc2, 0);
			if (ok) errmsg = execCopy(uidmode, shared.tmplightbc, shared.tmplightbc2);
			if (errmsg != null) issueResponse(false, errtype, prevstate, errmsg);
			break;

		case E_SRCH:
			shared.tmplightbc.set(rcvdata);
			errmsg = execSearch(uidmode, shared.tmplightbc, shared.tmplightbc2, shared.tmplightbc3);
			if (errmsg != null) issueResponse(false, errtype, prevstate, errmsg);
			break;

		default:
			// this is an internal bug whereby we're missing a case label
			getLogger().error(pfx_log+": Unrecognised bulk-command="+evt);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised bulk-command="+evt);
			break;
		}
	}

	@Override
	protected void startedSSL()
	{
		// wait for next client command - the first to be issued over SSL
		if (pstate == PROTO_STATE.S_DISCON) return; //we are about to close the connection
		if (shared.transcript != null) shared.transcript.event(pfx_transcript, "Switched to SSL mode", getSystemTime());
		transitionState(PROTO_STATE.S_AUTH);
	}

	@Override
	protected void disconnectLingerDone(boolean ok, CharSequence info, Throwable ex)
	{
		if (shared.transcript == null) return;
		shared.tmpsb.setLength(0);
		shared.tmpsb.append("Disconnect linger ");
		if (ok) {
			shared.tmpsb.append("completed");
		} else {
			shared.tmpsb.append("failed");
			if (info != null) shared.tmpsb.append(" - ").append(info);
			if (ex != null) shared.tmpsb.append(" - ").append(ex);
		}
		shared.transcript.event(pfx_transcript, shared.tmpsb, getSystemTime());
	}

	private void transmit(java.nio.ByteBuffer xmtbuf, boolean finalrsp) throws java.io.IOException
	{
		if (shared.transcript != null && !isState2(S2_NOTRANSCRIPT)) {
			shared.transcript.data_out(pfx_transcript, xmtbuf, 0, getSystemTime());
		}
		xmtbuf.position(0);
		getWriter().transmit(xmtbuf);
		if (finalrsp) setState2(S2_REQWAIT);
	}

	private void transmit(CharSequence status, com.grey.base.utils.ByteChars xmtbuf, CharSequence info) throws java.io.IOException
	{
		if (xmtbuf == null) {
			xmtbuf = shared.xmtbuf.clear();
		} else {
			//buffer already contains some untagged data
			if (xmtbuf.size() != 0 && xmtbuf.byteAt(xmtbuf.size()-1) != '\n') {
				xmtbuf.append(IMAP4Protocol.EOL);
			}
		}
		if (info == null) info = "Done"; //MS-Outlook requires some free text after the status!
		xmtbuf.append(reqtag).append(' ').append(status).append(' ').append(info).append(IMAP4Protocol.EOL);
		transmit(xmtbuf, true);
	}

	private void transmit(com.grey.base.utils.ByteChars data, boolean finalrsp) throws java.io.IOException
	{
		shared.tmpniobuf = shared.bufspec.encode(data, shared.tmpniobuf);
		transmit(shared.tmpniobuf, finalrsp);
	}

	@Override
	public void transmitterSend(java.nio.channels.FileChannel chan, long off, long len) throws java.io.IOException
	{
		getWriter().transmitChunked(chan, off, off+len, 0, false);
	}

	@Override
	public void transmitterReportSize(int siz, Object arg) throws java.io.IOException
	{
		com.grey.base.utils.ByteChars xmtbuf = (com.grey.base.utils.ByteChars)arg;
		xmtbuf.append('{').append(siz, shared.tmpsb).append('}').append(IMAP4Protocol.EOL);
		transmit(xmtbuf, false);
		xmtbuf.clear();
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d)
			throws java.io.IOException
	{
		switch (tmr.getType())
		{
		case TMRTYPE_SESSIONTMT:
			tmr_sesstmt = null; //this timer is now expired, so we must not access it again
			if (!isBrokenPipe()) transmit(shared.imap4rsp_bye_timeout, false);
			issueDisconnect("Timeout");
			break;

		case TMRTYPE_DISCON:
			tmr_exit = null;
			disconnect();
			break;

		case TMRTYPE_BULKCMD:
			tmr_bulkcmd = null;
			execBulkCommand(currentBulkCmd);
			break;

		case TMRTYPE_NEWMAIL:
			// send untagged responses to notify any external updates - must be in S_IDLE
			tmr_newmail = getDispatcher().setTimer(shared.interval_newmail, TMRTYPE_NEWMAIL, this);
			com.grey.base.utils.ByteChars xmtbuf = shared.tmpbc.clear();
			reportUpdatesAll(xmtbuf);
			if (xmtbuf.size() != 0) transmit(xmtbuf, false);
			break;

		default:
			getLogger().error(pfx_log+": Unrecognised timer-type="+tmr.getType());
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised timer-type="+tmr.getType());
			break;
		}
	}

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex)
	{
		raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "State="+pstate+" - "+com.grey.base.ExceptionUtils.summary(ex));
	}

	private void processLogin(boolean is_valid, com.grey.base.utils.ByteChars usrnam) throws java.io.IOException
	{
		if (!is_valid) {
			transitionState(PROTO_STATE.S_AUTH);
			transmit(IMAP4Protocol.STATUS_REJ, null, "Authentication failed");
			return;
		}
		LEVEL loglvl = LEVEL.TRC2;
		if (getLogger().isActive(loglvl)) {
			shared.tmpsb.setLength(0);
			shared.tmpsb.append(pfx_log).append(": User=").append(usrnam).append(" logged in from ");
			shared.tmpsb.append("Remote=").append(remote_tsap.dotted_ip).append(':').append(remote_tsap.port);
			getLogger().log(loglvl, shared.tmpsb);
		}
		transmit(IMAP4Protocol.STATUS_OK, null, null);
		transitionState(PROTO_STATE.S_SELECT);
		sess = shared.ms.startMailboxSession(usrnam);
	}

	private void execList(PROTO_EVENT evt, com.grey.base.utils.ByteChars refname, com.grey.base.utils.ByteChars mbxname,
			com.grey.base.utils.ByteChars xmtbuf, com.grey.base.utils.ByteChars srchbuf)
		throws java.io.IOException
	{
		CharSequence untaggedrsp = (evt == PROTO_EVENT.E_LSUB ? IMAP4Protocol.CMDREQ_LSUB : IMAP4Protocol.CMDREQ_LIST);
		if (mbxname.size() == 0) {
			//we are merely returning delimiter info
			if (refname.size() != 0) {
				int pos = refname.indexOf((byte)sess.getHierarchyDelimiter());
				if (pos != -1) refname.setSize(pos + 1); //include trailing DLM in response
			}
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(untaggedrsp);
			xmtbuf.append(" (").append(IMAP4Protocol.BOXFLAG_NOSELECT).append(") ");
			xmtbuf.append(Defs.CHAR_QUOTE).append(sess.getHierarchyDelimiter()).append(Defs.CHAR_QUOTE);
			xmtbuf.append(' ').append(Defs.CHAR_QUOTE).append(refname).append(Defs.CHAR_QUOTE);
			transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
			return;
		}
		boolean wild_partial = false;
		boolean wild_full = false;
		int pos = mbxname.indexOf(IMAP4Protocol.WILDCARD_PARTIAL);
		if (pos != -1) {
			mbxname.setSize(pos);
			wild_partial = true;
		} else {
			pos = mbxname.indexOf(IMAP4Protocol.WILDCARD_FULL);
			if (pos != -1) {
				mbxname.setSize(pos);
				wild_full = true;
			}
		}
		srchbuf.clear();
		if (refname.size() == 0 || refname.charAt(0) != sess.getHierarchyDelimiter()) srchbuf.append(sess.getHierarchyDelimiter());
		srchbuf.append(refname);
		int reflen = srchbuf.length();
		if (mbxname.size() != 0) {
			if (srchbuf.charAt(srchbuf.size()-1) != sess.getHierarchyDelimiter()) srchbuf.append(sess.getHierarchyDelimiter());
			srchbuf.append(mbxname);
		}
		boolean found_matches = false;
		int mbxcnt = sess.getMailboxCount();

		for (int idx = 0; idx != mbxcnt; idx++) {
			final String mbx = sess.getMailboxName(idx);
			int mbxlen = mbx.length();
			if (mbxlen < srchbuf.size() || mbxlen == reflen) continue;
			if (!StringOps.sameSeqNoCase(srchbuf, 0, srchbuf.size(), mbx, 0, srchbuf.size())) {
				if (found_matches) break; //list is sorted, so we've advanced past all the matches
				continue;
			}
			found_matches = true;
			if (wild_partial) {
				pos = srchbuf.size();
				if (mbxname.size() == 0 && mbxlen > pos && mbx.charAt(pos) == sess.getHierarchyDelimiter()) pos++; //want the next node
				pos = mbx.indexOf(sess.getHierarchyDelimiter(), pos);
				if (pos != -1) continue; //ignore children of the matching node
			} else if (wild_full) {
				//match entire mailbox name, ie. all children of the matching node are also matches
			} else {
				//requires exact match on a leaf node
				if (mbxlen != srchbuf.size()) continue; //must be child node of the specified mailbox
			}
			boolean is_parent = (idx != mbxcnt -1 && sess.getMailboxName(idx+1).startsWith(mbx));
			if (xmtbuf.size() != 0) xmtbuf.append(IMAP4Protocol.EOL);
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(untaggedrsp);
			xmtbuf.append(" (").append(is_parent ? IMAP4Protocol.BOXFLAG_CHILD : IMAP4Protocol.BOXFLAG_NOCHILD).append(") ");
			xmtbuf.append(Defs.CHAR_QUOTE).append(sess.getHierarchyDelimiter()).append(Defs.CHAR_QUOTE);
			xmtbuf.append(' ').append(Defs.CHAR_QUOTE).append(mbx, 1, mbxlen - 1).append(Defs.CHAR_QUOTE);
		}
		if (mbxname.size() == 0 || StringOps.sameSeqNoCase(mbxname, IMAP4Protocol.MBXNAME_INBOX)) {
			if (xmtbuf.size() != 0) xmtbuf.append(IMAP4Protocol.EOL);
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(untaggedrsp).append(" ("+IMAP4Protocol.BOXFLAG_NOCHILD+") ");
			xmtbuf.append(Defs.CHAR_QUOTE).append(sess.getHierarchyDelimiter()).append(Defs.CHAR_QUOTE);
			xmtbuf.append(' ').append(Defs.CHAR_QUOTE).append(IMAP4Protocol.MBXNAME_INBOX).append(Defs.CHAR_QUOTE);
		}
		transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
	}

	// Dovecot's imaptest tool insisted on seeing some free-text after PERMANENTFLAGS, etc
	private String execSelect(boolean rdonly, CharSequence mbxname, com.grey.base.utils.ByteChars xmtbuf)
			throws java.io.IOException
	{
		StringBuilder tmpsb = shared.tmpsb;
		mbxname = mapMailboxNameToMS(mbxname);
		if (mbxname == null) return "No such mailbox";
		com.grey.mailismus.ms.maildir.MailboxView props = sess.openMailbox(mbxname, rdonly);
		if (props == null) return "Failed to open mailbox";
		mbxprop_msgtotal = props.getMsgCount();
		mbxprop_recent = props.getRecentCount();
		mbxprop_numflags = -1;
		int unseen1 = props.getFirstUnseen();

		reportMailboxFlags(xmtbuf, true);
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(mbxprop_msgtotal, tmpsb).append(" EXISTS");
		xmtbuf.append(IMAP4Protocol.EOL);
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(mbxprop_recent, tmpsb).append(" RECENT");
		xmtbuf.append(IMAP4Protocol.EOL);
		if (unseen1 != 0) {
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append("OK [UNSEEN ").append(unseen1, tmpsb).append("] first unseen");
			xmtbuf.append(IMAP4Protocol.EOL);
		}
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append("OK [UIDVALIDITY ").append(props.getUIDValidity(), tmpsb).append("] generation");
		xmtbuf.append(IMAP4Protocol.EOL);
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append("OK [UIDNEXT ").append(props.getNextUID(), tmpsb).append("] predicted");
		transmit(IMAP4Protocol.STATUS_OK, xmtbuf, rdonly ? "[READ-ONLY]" : "[READ-WRITE] Done");
		return null;
	}

	private String execStatus(CharSequence mbxname, com.grey.base.utils.ByteChars attrs, com.grey.base.utils.ByteChars xmtbuf) throws java.io.IOException
	{
		StringBuilder tmpsb = shared.tmpsb;
		CharSequence orig_mbxname = mbxname;
		mbxname = mapMailboxNameToMS(mbxname);
		if (mbxname == null) return "No such mailbox";

		// Have confirmed that in Dovecot, Status on selected mailbox sees same view (ie. recent count)
		com.grey.mailismus.ms.maildir.MailboxView props = sess.currentView();
		if (props == null || !mbxname.equals(props.mbxname)) {
			props = sess.statMailbox(mbxname);
		}
		if (props == null) return "Failed to stat mailbox";

		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(IMAP4Protocol.CMDREQ_STATUS);
		xmtbuf.append(' ').append(orig_mbxname).append(' ');
		char dlm = '(';
		int lmt = attrs.limit();

		while (attrs.size() != 0) {
			int pos = attrs.offset();
			while (pos != lmt && attrs.buffer()[pos] == ' ') pos++;
			if (pos == lmt) break;
			int pos2 = pos;
			while (pos2 != lmt && attrs.buffer()[pos2] != ' ') pos2++;
			int len = pos2 - pos;
			if (StringOps.sameSeqNoCase(attrs, pos - attrs.offset(), len, "MESSAGES")) {
				xmtbuf.append(dlm).append("MESSAGES ").append(props.getMsgCount(), tmpsb);
			} else if (StringOps.sameSeqNoCase(attrs, pos - attrs.offset(), len, "RECENT")) {
				xmtbuf.append(dlm).append("RECENT ").append(props.getRecentCount(), tmpsb);
			} else if (StringOps.sameSeqNoCase(attrs, pos - attrs.offset(), len, "UNSEEN")) {
				xmtbuf.append(dlm).append("UNSEEN ").append(props.getMsgCount() - props.getSeenCount(), tmpsb); //NB: differs to SELECT
			} else if (StringOps.sameSeqNoCase(attrs, pos - attrs.offset(), len, "UIDNEXT")) {
				xmtbuf.append(dlm).append("UIDNEXT ").append(props.getNextUID(), tmpsb);
			} else if (StringOps.sameSeqNoCase(attrs, pos - attrs.offset(), len, "UIDVALIDITY")) {
				xmtbuf.append(dlm).append("UIDVALIDITY ").append(props.getUIDValidity(), tmpsb);
			} else {
				//ignore unrecognised items
			}
			dlm = ' ';
			attrs.advance(pos2 - attrs.offset());
		}
		if (dlm == '(') xmtbuf.append(dlm); //empty list, so we never opened the parentheses
		xmtbuf.append(')');
		transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
		return null;
	}

	private void execClose(boolean expunge) throws java.io.IOException
	{
		if (expunge && sess.writeableMailbox()) sess.expungeMailbox(null, null);
		sess.closeMailbox();
	}

	private String execFetch(boolean uidmode, com.grey.base.utils.ByteChars seq, com.grey.base.utils.ByteChars attrs)
		throws java.io.IOException
	{
		NumberList seqlst = parseSequenceSet(seq, bulkseqlst, uidmode, false);
		if (seqlst == null) return "Invalid sequence-set";

		if (StringOps.sameSeqNoCase(attrs, "FAST")) {
			attrs.set(FETCHMACRO_FAST);
		} else if (StringOps.sameSeqNoCase(attrs, "ALL")) {
			attrs.set(FETCHMACRO_ALL);
		} else if (StringOps.sameSeqNoCase(attrs, "FULL")) {
			attrs.set(FETCHMACRO_FULL);
		}
		cmdFetch.reset(seqlst);
		boolean add_uid = (uidmode || REPORT_UID);

		while (attrs.size() != 0) {
			Object result = parseFetchAttribute(attrs);
			if (result.getClass() == String.class) return (String)result;
			FetchOp op = (FetchOp)result;
			if (op.def.code == FetchOpDef.OPCODE.UID) add_uid = false;
			cmdFetch.addOp(op);
			while (attrs.size() != 0 && attrs.byteAt(0) == ' ') attrs.advance(1);
		}
		if (add_uid) cmdFetch.addOp(shared.fopImmutable.get("UID"));
		cmdFetch.prime(shared.batchsize_nodisk, shared.batchsize_renames);
		execBulkCommand(cmdFetch);
		return null;
	}

	private String execStore(boolean uidmode, com.grey.base.utils.ByteChars seq, com.grey.base.utils.ByteChars op,
			com.grey.base.utils.ByteChars imapflags) throws java.io.IOException
	{
		int mode = 0;
		boolean silent = false;
		NumberList seqlst = parseSequenceSet(seq, bulkseqlst, uidmode, false);
		if (seqlst == null) return "Invalid sequence-set";

		int pos = 0;
		if (op.charAt(0) == '+') {
			mode = 1;
			pos++;
		} if (op.charAt(0) == '-') {
			mode = -1;
			pos++;
		}
		int pos2 = op.indexOf(pos, (byte)'.');
		if (pos2 != -1) {
			if (!StringOps.sameSeqNoCase(op, pos2+1, op.size() - pos2 - 1, "SILENT")) return "Expected SILENT keyword after dot";
			silent = true;
		} else {
			pos2 = op.size();
		}
		if (!StringOps.sameSeqNoCase(op, pos, pos2 - pos, "FLAGS")) return "Expected FLAGS keyword";

		StringBuilder sb = shared.tmpsb;
		sb.setLength(0);
		if (imapflags.size() != 0) {
			//ImapTest expects us to create keywords here even for -FLAGS, so keep it happy
			String errmsg = parseFlags(imapflags, sb, true);
			if (errmsg != null) return errmsg;
		}
		String msflags = sb.toString();

		cmdStore.reset(seqlst, shared.batchsize_renames, msflags, mode, silent, uidmode);
		execBulkCommand(cmdStore);
		return null;
	}

	private String execCopy(boolean uidmode, com.grey.base.utils.ByteChars seq, CharSequence dest_mbxname)
			throws java.io.IOException
	{
		NumberList seqlst = parseSequenceSet(seq, bulkseqlst, uidmode, false);
		if (seqlst == null) return "Invalid sequence-set";

		dest_mbxname = mapMailboxNameToMS(dest_mbxname);
		if (dest_mbxname == null) return "[TRYCREATE] No such mailbox";

		cmdCopy.reset(seqlst, shared.batchsize_fileio, dest_mbxname.toString());
		execBulkCommand(cmdCopy);
		return null;
	}

	// We respect the Flags arg, but ignore the Date-Time - RFC-3501 merely stipulates them both as SHOULD rather than MUST
	private String execAppend(CharSequence dest_mbxname, com.grey.base.utils.ByteChars imapflags, CharSequence dt, CharSequence octetcnt)
			throws java.io.IOException
	{
		append_mbx = mapMailboxNameToMS(dest_mbxname);
		if (append_mbx == null) return "[TRYCREATE] No such mailbox";

		StringBuilder msflags = shared.tmpsb;
		msflags.setLength(0);
		if (imapflags != null) {
			String errmsg = parseFlags(imapflags, msflags, true);
			if (errmsg != null) return errmsg;
		}
		append_flags = msflags.toString();

		if ((append_remainbytes = parseLiteralSize(octetcnt)) == -1) {
			return "Invalid literal octet-count - non-numeric";
		}
		if (append_remainbytes == 0) return "Invalid literal octet-count - zero";

		try {
			append_strm = new java.io.FileOutputStream(append_fh);
		} catch (Exception ex) {
			// assume first failure is due to non-existence of directory - a repeat failure is genuine
			FileOps.ensureDirExists(shared.stagingDir);
			append_strm = new java.io.FileOutputStream(append_fh);
		}

		// we don't issue a final response yet, so prepare to receive the message
		if (!isNonSynchLiteral(octetcnt)) transmit(shared.imap4rsp_contd_ready, false);
		setState2(S2_DATAWAIT);
		if (!shared.full_transcript) setState2(S2_NOTRANSCRIPT);
		return null;
	}

	private String execSearch(boolean uidmode, com.grey.base.utils.ByteChars expr, com.grey.base.utils.ByteChars termbuf,
			com.grey.base.utils.ByteChars argbuf)
		throws java.io.IOException
	{
		cmdSearch.reset(bulkseqlst);
		StringBuilder flags_incl_buf = shared.tmpsb;
		StringBuilder flags_excl_buf = shared.tmpsb2;
		flags_incl_buf.setLength(0);
		flags_excl_buf.setLength(0);
		int batchsiz = shared.batchsize_nodisk;
		boolean idsearch = false;
		boolean have_srchterms = false;
		long mintime = 0;
		long maxtime = 0;
		int minsize = 0;
		int maxsize = 0;

		// We don't handle any boolean expressions, or any operators other than not, so any
		// brackets etc will screw us up.
		while (expr.size() != 0) {
			if (!getNextTerm(expr, termbuf, 0)) break;
			boolean neg = false;
			java.util.HashMap<String, String> hdrs = cmdSearch.hdrs_incl;
			StringBuilder flags_incl = flags_incl_buf;
			StringBuilder flags_excl = flags_excl_buf;
			SearchKey skey = null;
			char arg_msflag = 0;
			long arg_time = 0;
			int arg_num = 0;

			if (StringOps.sameSeqNoCase("not", termbuf)) {
				if (!getNextTerm(expr, termbuf, 0)) return "Invalid search - truncated on NOT";
				neg = true;
				hdrs = cmdSearch.hdrs_excl;
				flags_incl = flags_excl_buf;
				flags_excl = flags_incl_buf;
			}
			for (int idx = 0; idx != searchKeys.length; idx++) {
				if (StringOps.sameSeqNoCase(searchKeys[idx].token, termbuf)) {
					skey = searchKeys[idx];
					break;
				}
			}
			if (skey == null) {
				if (Character.isDigit(termbuf.charAt(0))) {
					//try parsing it as a sequence-set
					if (parseSequenceSet(termbuf, cmdSearch.seqlst, false, true) == null) {
						return "Invalid search - bad sequence numbers at "+termbuf;
					}
					have_srchterms = true;
					idsearch = true;
					continue;
				}
				return "Invalid search - unrecognised term at "+termbuf;
			}
			if (skey.alias != null) {
				for (int idx = 0; idx != searchKeys.length; idx++) {
					if (skey.alias.equals(searchKeys[idx].token)) {
						skey = searchKeys[idx];
						break;
					}
					if (idx == searchKeys.length - 1) {
						//this is a bug - must have mistyped an alias above
						throw new Error("Unmatched Search alias="+skey.alias);
					}
				}
			}
			if (skey.ignored) {
				for (int loop = 0; loop != skey.argcnt; loop++) {
					//discard the args
					if (!getNextTerm(expr, argbuf, TERM_EMPTY)) {
						return "Invalid search - truncated on "+termbuf+", arg="+(loop+1);
					}
				}
				continue;
			}
			have_srchterms = true;

			if (skey.msflag != 0) {
				if (skey.excl_flag) {
					flags_excl.append(skey.msflag);
				} else {
					flags_incl.append(skey.msflag);
				}
				continue;
			}

			if (skey.argcnt != 0) {
				// extract the first arg
				if (!getNextTerm(expr, argbuf, TERM_EMPTY)) return "Invalid search - truncated on "+termbuf+" arg=1";
				// and now parse it, if it's one of the standard types
				if (skey.argtype == SearchKey.ARGTYPE.KWORD) {
					arg_msflag = shared.msgFlags.getFlagMS(argbuf, 0, argbuf.length(), false);
					if (arg_msflag == MessageFlags.NOFLAG) continue; //ignore unrecognised keywords
				} else if (skey.argtype == SearchKey.ARGTYPE.DATE) {
					arg_time = parseSearchDate(argbuf);
					if (arg_time == -1) return "Invalid search - bad message-date";
				} else if (skey.argtype == SearchKey.ARGTYPE.NUMBER) {
					arg_num = (int)parseDecimal(argbuf);
					if (arg_num == -1) return "Invalid search - bad message-size";
				}
			}

			if (skey.token.equals("all")) {
				//null search term, but ensures we do have at least one non-ignored term
			} else if (skey.token.equals("header")) {
				//we know 1st char should always be upper-case, and just accept the rest as is
				if (argbuf.size() == 0) return "Invalid search - truncated on "+termbuf+" - missing header name";
				argbuf.setByte(0, Character.toUpperCase((char)argbuf.byteAt(0)));
				String hdrname = argbuf.toString();
				if (!getNextTerm(expr, argbuf, TERM_EMPTY)) return "Invalid search - truncated on "+termbuf+", hdr="+hdrname;
				hdrs.put(hdrname, argbuf.toString().toLowerCase());
			} else if (skey.argtype == SearchKey.ARGTYPE.HEADER) {
				hdrs.put(skey.token, argbuf.toString().toLowerCase());
			} else if (skey.token.equals("new")) {
				flags_incl.append(MaildirStore.MSGFLAG_RECENT);
				flags_excl.append(MaildirStore.MSGFLAG_SEEN);
			} else if (skey.token.equals("keyword")) {
				flags_incl.append(arg_msflag);
			} else if (skey.token.equals("unkeyword")) {
				flags_excl.append(arg_msflag);
			} else if (skey.token.equals("smaller")) {
				if (neg) {
					minsize = arg_num;
				} else {
					maxsize = arg_num;
				}
			} else if (skey.token.equals("larger")) {
				if (neg) {
					maxsize = arg_num;
				} else {
					minsize = arg_num;
				}
			} else if (skey.token.equals("on")) {
				mintime = arg_time;
				maxtime = arg_time + TimeOps.MSECS_PER_DAY;
			} else if (skey.token.equals("before")) {
				if (neg) {
					mintime = arg_time;
				} else {
					maxtime = arg_time;
				}
			} else if (skey.token.equals("since")) {
				if (neg) {
					maxtime = arg_time;
				} else {
					mintime = arg_time;
				}
			} else if (skey.token.equals("uid")) {
				if (parseSequenceSet(argbuf, shared.tmpnumlst, true, true) == null) {
					return "Invalid search - bad UID sequence at "+argbuf;
				}
				idsearch = true;
				cmdSearch.seqlst.append(shared.tmpnumlst); //duplicates do no harm
			} else {
				// this is a bug - we're missing a case for a SearchKey item which we've defined above
				throw new Error("Missing case for SearchKey.token="+skey.token);
			}
		}

		// execute the search
		if (cmdSearch.hdrs_incl.size() + cmdSearch.hdrs_excl.size() != 0) batchsiz = shared.batchsize_fileio;
		String fi = (flags_incl_buf.length() == 0 ? null : flags_incl_buf.toString());
		String fe = (flags_excl_buf.length() == 0 ? null : flags_excl_buf.toString());
		cmdSearch.prime(uidmode, fi, fe, mintime, maxtime, minsize, maxsize, batchsiz);

		if (!have_srchterms || (idsearch && cmdSearch.seqlst.size() == 0)) {
			// no matches - avoid bothering with the search
			cmdSearch.setNoOp();
		}
		execBulkCommand(cmdSearch);
		return null;
	}

	private void execBulkCommand(BulkCommand cmd) throws java.io.IOException
	{
		com.grey.base.utils.ByteChars xmtbuf = shared.tmpbc.clear();
		currentBulkCmd = cmd;

		//synch and report flags updates - Fetch might report updated flags again below, but so what
		cmd.report_at_end = reportUpdates(xmtbuf, MaildirStore.RPT_FLAGS_ONLY);

		if (!cmd.isNoOp()) {
			int msglmt = Math.min(cmd.seqlst.size(), cmd.batch_off + cmd.batch_siz);
			boolean final_batch = (msglmt == cmd.seqlst.size());
			String errmsg = null;

			if (cmd.cmd == PROTO_EVENT.E_FETCH) {
				errmsg = execBulkFetch((BulkCommand.CommandFetch)cmd, msglmt, xmtbuf, shared.tmpbc2, shared.tmpsb);
			} else if (cmd.cmd == PROTO_EVENT.E_STORE) {
				errmsg = execBulkStore((BulkCommand.CommandStore)cmd, msglmt, xmtbuf);
			} else if (cmd.cmd == PROTO_EVENT.E_COPY) {
				errmsg = execBulkCopy((BulkCommand.CommandCopy)cmd, msglmt);
			} else if (cmd.cmd == PROTO_EVENT.E_SRCH) {
				msglmt = cmd.batch_off + cmd.batch_siz; //because we're looping on all messages, not seqlst (which could be empty)
				final_batch = execBulkSearch((BulkCommand.CommandSearch)cmd, msglmt, xmtbuf);
			} else {
				getLogger().error(pfx_log+": Unrecognised bulk-command="+cmd.cmd);
				raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised bulk-command="+cmd.cmd);
			}

			if (errmsg != null) {
				transmit(IMAP4Protocol.STATUS_ERR, xmtbuf, errmsg);
				setReceiveMode();
				return;
			}

			if (!final_batch) {
				if (xmtbuf.size() != 0) {
					transmit(xmtbuf, false);
					xmtbuf.clear();
				}
				scheduleBulkCommand(cmd, msglmt);
				return;
			}
		}
		transmit(IMAP4Protocol.STATUS_OK, xmtbuf, null);
		currentBulkCmd.clear();
		currentBulkCmd = null;

		if (cmd.report_at_end && UNILATERAL_REPORTS) {
			// Clients are expected to support spontaneous updates (see RFC-3501 section 2.2.2) but
			// Expunge responses cannot be sent when no command is in progress (see section 7.4.1).
			xmtbuf.clear();
			reportUpdates(xmtbuf, MaildirStore.RPT_EXCL_EXPUNGE);
			if (xmtbuf.size() != 0) transmit(xmtbuf, false);
		}
		setReceiveMode();
	}

	private void scheduleBulkCommand(BulkCommand cmd, int next_offset)
	{
		currentBulkCmd = cmd;
		cmd.batch_off = next_offset;
		tmr_bulkcmd = getDispatcher().setTimer(0, TMRTYPE_BULKCMD, this);
	}

	private String execBulkFetch(BulkCommand.CommandFetch cmd, int msglmt, com.grey.base.utils.ByteChars xmtbuf, com.grey.base.utils.ByteChars databuf,
			StringBuilder tmpsb) throws java.io.IOException
	{
		final com.grey.mailismus.ms.maildir.MailboxView mbxview = sess.currentView();
		final int opslmt = cmd.ops.size();

		for (int msgidx = cmd.batch_off; msgidx != msglmt; msgidx++) {
			if (xmtbuf.size() > shared.maximapbuf) {
				transmit(xmtbuf, false);
				xmtbuf.clear();
			}
			final int seqnum = cmd.seqlst.get(msgidx);
			final boolean initial_seen = mbxview.hasFlag(seqnum, MaildirStore.MSGFLAG_SEEN);
			boolean sent_seen = false;
			MimePart mime_tree = null;
			boolean skipmsg = false;
			String dlm_ops = "";
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(seqnum, tmpsb);
			xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_FETCH).append(" (");

			for (int opidx = 0; opidx != opslmt; opidx++) {
				final FetchOp op = cmd.ops.get(opidx);
				final int prevlen = xmtbuf.size();
				FetchOpDef.OPCODE opcode = op.def.code;
				xmtbuf.append(dlm_ops).append(op.replytag).append(' ');
				if (op.def.parenth != null) xmtbuf.append(op.def.parenth.charAt(0));
				MimePart mime_node = null;

				if (op.mime_coords != null) {
					if (mime_tree == null) mime_tree = sess.getMimeStructure(seqnum);
					if (mime_tree == null) {
						opcode = FetchOpDef.OPCODE.DUMMY;
						skipmsg = true;
					} else {
						mime_node = mapMimeNode(mime_tree, op.mime_coords, opcode);
						if (mime_node == null) {
							xmtbuf.append("NIL");
							opcode = FetchOpDef.OPCODE.DUMMY;
						}
					}
				}

				switch (opcode) {
				case DUMMY:
					break;
				case UID:
					int uid = mbxview.getMessageUID(seqnum);
					xmtbuf.append(uid, tmpsb);
					break;
				case SIZE:
					int msgsiz = mbxview.getMessageSize(seqnum);
					xmtbuf.append(msgsiz, tmpsb);
					break;
				case TIMESTAMP:
					long systime = mbxview.getMessageTime(seqnum);
					makeTime(systime, shared.dtcal, tmpsb);
					xmtbuf.append(tmpsb);
					break;
				case FLAGS:
					getMessageFlags(seqnum, mbxview, xmtbuf, tmpsb);
					sent_seen = mbxview.hasFlag(seqnum, MaildirStore.MSGFLAG_SEEN);
					break;
				case ENVELOPE:
					if (!getEnvelope(seqnum, mime_node, xmtbuf, tmpsb)) skipmsg = true;
					break;
				case HEADERS:
				case MIME:
					databuf.clear();
					boolean excl = op.def.isSet(FetchOpDef.F_EXCL) || !op.def.isSet(FetchOpDef.F_HASFLDS); //return all headers if !HASFLDS
					if (op.hdrs != null || excl) {
						if (!sess.getHeaders(seqnum, op.peek, op.hdrs, mime_node, excl, databuf, null)) {
							skipmsg = true;
							break;
						}
					}
					if (op.partial_off > databuf.size()) {
						databuf.clear();
					} else {
						databuf.advance(op.partial_off);
					}
					if (op.partial_len != 0 && op.partial_len < databuf.size()) databuf.setSize(op.partial_len);
					xmtbuf.append('{').append(databuf.size(), tmpsb).append('}').append(IMAP4Protocol.EOL);
					transmit(xmtbuf, false);
					xmtbuf.clear();
					if (!shared.full_transcript) setState2(S2_NOTRANSCRIPT);
					transmit(databuf, false);
					clearState2(S2_NOTRANSCRIPT);
					break;
				case ALL:
				case TEXT:
					if (!sess.getMessage(seqnum, op.peek, mime_node, opcode==FetchOpDef.OPCODE.TEXT, op.partial_off, op.partial_len, this, xmtbuf)) {
						skipmsg = true;
					}
					break;
				case BODY:
				case BODYSTRUCTURE:
					if (mime_tree == null) mime_tree = sess.getMimeStructure(seqnum);
					if (mime_tree == null) {
						skipmsg = true;
						break;
					}
					formatMimeStructure(mime_tree, opcode == FetchOpDef.OPCODE.BODYSTRUCTURE, seqnum, xmtbuf, tmpsb);
					break;
				default:
					throw new Error("Missing Fetch case for op="+opcode);
				}
				if (skipmsg) {
					// erase anything we've output for this op, and skip the remaining ops
					xmtbuf.setSize(prevlen);
					break;
				}
				if (op.def.parenth != null) xmtbuf.append(op.def.parenth.charAt(1));
				dlm_ops = " ";
			}
			xmtbuf.append(')').append(IMAP4Protocol.EOL);

			if (!initial_seen && !sent_seen && mbxview.hasFlag(seqnum, MaildirStore.MSGFLAG_SEEN)) {
				FetchOp op = shared.fopImmutable.get("FLAGS");
				xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(seqnum, tmpsb);
				xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_FETCH).append(" (");
				xmtbuf.append(op.replytag).append(' ');
				if (op.def.parenth != null) xmtbuf.append(op.def.parenth.charAt(0));
				getMessageFlags(seqnum, mbxview, xmtbuf, tmpsb);
				if (op.def.parenth != null) xmtbuf.append(op.def.parenth.charAt(1));
				xmtbuf.append(')').append(IMAP4Protocol.EOL);
			}
		}
		return null;
	}

	private String execBulkStore(BulkCommand.CommandStore cmd, int msglmt, com.grey.base.utils.ByteChars xmtbuf) throws java.io.IOException
	{
		sess.setMessageFlags(cmd.mode, cmd.seqlst, cmd.msflags, cmd.report_uid, cmd.batch_off, msglmt,
				cmd.silent ? null : this, xmtbuf);
		return null;
	}

	private String execBulkCopy(BulkCommand.CommandCopy cmd, int msglmt) throws java.io.IOException
	{
		for (int msgidx = cmd.batch_off; msgidx != msglmt; msgidx++) {
			int seqnum = cmd.seqlst.get(msgidx);
			sess.copyMessage(seqnum, cmd.dstmbx);
		}
		return null;
	}

	private boolean execBulkSearch(BulkCommand.CommandSearch cmd, int msglmt, com.grey.base.utils.ByteChars xmtbuf)
	{
		boolean done = sess.searchMessages(cmd.results, cmd.uidmode, cmd.seqlst,
												cmd.hdrs_incl, cmd.hdrs_excl, cmd.hdrnames, cmd.flags_incl, cmd.flags_excl,
												cmd.mintime, cmd.maxtime, cmd.minsize, cmd.maxsize, cmd.batch_off, msglmt);
		if (done) {
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(IMAP4Protocol.CMDREQ_SRCH);
			for (int idx = 0; idx != cmd.results.size(); idx++) {
				xmtbuf.append(' ').append(cmd.results.get(idx), shared.tmpsb);
			}
		}
		return done;
	}

	// EXISTS response must never reduce mbxsize (see RFC-3501 section 5.2), so we populate the the updates buffer with any
	// untagged EXPUNGE responses before appending EXISTS and RECENT.
	// Also forbidden to send Expunged response during non-UID Fetch, Store or Search (see RFC-3501 section 5.5).
	private boolean reportUpdates(com.grey.base.utils.ByteChars xmtbuf, int opts)
			throws java.io.IOException
	{
		reportMailboxFlags(xmtbuf, REPORT_NEWFLAGS);
		boolean have_more = sess.loadUpdates(this, xmtbuf, opts);
		com.grey.mailismus.ms.maildir.MailboxView props = sess.currentView();

		if (mbxprop_msgtotal != props.getMsgCount()) {
			mbxprop_msgtotal = props.getMsgCount();
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(mbxprop_msgtotal, shared.tmpsb).append(" EXISTS");
			xmtbuf.append(IMAP4Protocol.EOL);
		}
		if (mbxprop_recent != props.getRecentCount()) {
			mbxprop_recent = props.getRecentCount();
			xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(mbxprop_recent, shared.tmpsb).append(" RECENT");
			xmtbuf.append(IMAP4Protocol.EOL);
		}
		return have_more;
	}

	private void reportUpdatesAll(com.grey.base.utils.ByteChars xmtbuf) throws java.io.IOException
	{
		reportUpdates(xmtbuf, 0);
	}

	@Override
	public void reportExpunge(int seqnum, Object arg)
	{
		com.grey.base.utils.ByteChars xmtbuf = (com.grey.base.utils.ByteChars)arg;
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(seqnum, shared.tmpsb);
		xmtbuf.append(' ').append(IMAP4Protocol.CMDREQ_EXPUNGE).append(IMAP4Protocol.EOL);
		//this untagged response constitutes an advertised change to the message count
		mbxprop_msgtotal--;
	}

	@Override
	public void reportMessageFlags(int seqnum, CharSequence msflags, boolean with_uid, Object arg)
	{
		com.grey.base.utils.ByteChars xmtbuf = (com.grey.base.utils.ByteChars)arg;
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append(seqnum, shared.tmpsb).append(' ');
		xmtbuf.append(IMAP4Protocol.CMDREQ_FETCH).append(" (FLAGS (");
		for (int idx = 0; idx != msflags.length(); idx++) {
			String imapflag = shared.msgFlags.getFlagIMAP(msflags.charAt(idx));
			if (imapflag == null) continue;
			if (idx != 0) xmtbuf.append(' ');
			xmtbuf.append(imapflag);
		}
		xmtbuf.append(')');
		if (with_uid) xmtbuf.append(" UID ").append(sess.currentView().getMessageUID(seqnum), shared.tmpsb);
		xmtbuf.append(')').append(IMAP4Protocol.EOL);
	}

	private void reportMailboxFlags(com.grey.base.utils.ByteChars xmtbuf, boolean report) throws java.io.IOException
	{
		int numflags = shared.msgFlags.getNumFlags();
		if (!report || numflags == mbxprop_numflags) return;
		mbxprop_numflags = numflags;
		boolean send = (xmtbuf == null);

		if (xmtbuf == null) xmtbuf = shared.tmpbc.clear();
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append("FLAGS (").append(shared.msgFlags.getResponseFlags()).append(')').append(IMAP4Protocol.EOL);
		xmtbuf.append(IMAP4Protocol.STATUS_UNTAGGED).append("OK [PERMANENTFLAGS (");
		if (sess.writeableMailbox()) xmtbuf.append(shared.msgFlags.getResponsePermFlags());
		xmtbuf.append(")] perm").append(IMAP4Protocol.EOL);
		if (send) transmit(xmtbuf, false);
	}

	private void getMessageFlags(int seqnum, com.grey.mailismus.ms.maildir.MailboxView mbxview,
			com.grey.base.utils.ByteChars outbuf, StringBuilder tmpsb)
	{
		tmpsb.setLength(0);
		mbxview.getMessageFlags(seqnum, tmpsb);
		for (int idx = 0; idx != tmpsb.length(); idx++) {
			String imapflag = shared.msgFlags.getFlagIMAP(tmpsb.charAt(idx));
			if (imapflag == null) continue;
			if (idx != 0) outbuf.append(' ');
			outbuf.append(imapflag);
		}
	}

	private boolean getEnvelope(int seqnum, MimePart mime, com.grey.base.utils.ByteChars outbuf, StringBuilder tmpsb) throws java.io.IOException
	{
		shared.tmpstrmap.clear();
		String[] hdrs = shared.envHeaderNames;
		if (!sess.getHeaders(seqnum, true, hdrs, mime, false, null, shared.tmpstrmap)) return false;

		for (int idx = 0; idx != hdrs.length; idx++) {
			if (idx != 0) outbuf.append(' ');
			String val = shared.tmpstrmap.get(hdrs[idx]);
			if (val == null) {
				if (shared.envHeaders[idx].dflt != null) val = shared.tmpstrmap.get(shared.envHeaders[idx].dflt);
			}
			if (val != null) {
				if (shared.envHeaders[idx].trait(EnvelopeHeader.F_ADDR)) {
					// address field, requires special format
					outbuf.append("(("); //address ABNF specifies one brace, and so does ABNF for each address type
					formatMailAddress(val, outbuf);
					outbuf.append("))");
				} else if (shared.envHeaders[idx].trait(EnvelopeHeader.F_ANGBRACE)) {
					int pos_open = val.indexOf('<');
					int pos_close = (pos_open == -1 ? -1 : val.indexOf('>', pos_open));
					if (pos_close == -1) {
						// take the whole value as is
						appendQuoted(val, outbuf);
					} else {
						appendQuoted(val, pos_open, pos_close+1, outbuf);
					}
				} else {
					if (shared.envHeaders[idx].trait(EnvelopeHeader.F_ESC) && val.indexOf(Defs.CHAR_QUOTE) != -1) {
						outbuf.append('{').append(val.length(), tmpsb).append('}').append(IMAP4Protocol.EOL);
						outbuf.append(val);
					} else {
						appendQuoted(val, outbuf);
					}
				}
			} else {
				outbuf.append("NIL");
			}
		}
		shared.tmpstrmap.clear();
		return true;
	}

	private MimePart mapMimeNode(MimePart tree, NumberList coords, FetchOpDef.OPCODE op)
	{
		MimePart node = tree;
		int idx = 0;
		while (idx != coords.size()) {
			int ptr = coords.get(idx++);
			if (ptr == 0) return null; //illegal
			if (node.childCount() < ptr) {
				//This initial check allows BODY[1] refs on a plain message to return the whole message.
				//It was introduced as a bodge to interoperate with SquirrelMail, but maybe SquirrelMail is
				//right and I misunderstood ... in which case this is a workaround for Mailismus itself.
				if (SQUIRREL_FRIENDLY && idx == 1 && coords.size() == 1 && ptr == 1) return tree;
				return null;
			}
			node = node.getChild(ptr - 1);
		}

		switch (op)
		{
		case ALL:
			if (node.isNestedMessage()) node = node.getMessage();
			break;
		case HEADERS:
		case TEXT:
			//this is a continuation of the above SquirrelMail workaround
			if (!SQUIRREL_FRIENDLY || node.getMessage() != null) node = node.getMessage();
			break;
		case MIME:
			break;
		default:
			throw new Error("Missing MIME case for op="+op);
		}
		return node;
	}

	private void formatMimeStructure(MimePart mime, boolean withExtensions, int seqnum, com.grey.base.utils.ByteChars outbuf,
			StringBuilder tmpsb) throws java.io.IOException
	{
		outbuf.append('(');
		if (mime.ctype.equalsIgnoreCase("multipart")) {
			for (int idx = 0; idx != mime.childCount(); idx++) {
				formatMimeStructure(mime.getChild(idx), withExtensions, seqnum, outbuf, tmpsb);
			}
			outbuf.append(' ');
			appendQuoted(mime.subtype, outbuf);
			if (withExtensions) {
				outbuf.append(" (");
				appendQuoted(MimePart.ATTR_BNDRY, outbuf).append(' ');
				appendQuoted(mime.bndry, outbuf);
				outbuf.append(')');
				formatMimeCommonExtensions(mime, outbuf);
			}
		} else {
			appendQuoted(mime.ctype, outbuf).append(' ');
			appendQuoted(mime.subtype, outbuf).append(" (");
			if (mime.charset != null) {
				appendQuoted(MimePart.ATTR_CHARSET, outbuf).append(' ');
				appendQuoted(mime.charset, outbuf);
			}
			if (mime.name != null) {
				appendQuoted(MimePart.ATTR_NAME, outbuf).append(' ');
				appendQuoted(mime.name, outbuf);
			}
			outbuf.append(") ");
			appendQuoted(mime.contid, outbuf).append(' ');
			appendQuoted(mime.contdesc, outbuf).append(' ');
			appendQuoted(mime.encoding, outbuf).append(' ');
			outbuf.append(mime.bodysiz, tmpsb).append(' ');
			if (mime.ctype.equalsIgnoreCase("text")) {
				outbuf.append(mime.linecnt, tmpsb);
			} else if (mime.isNestedMessage()) {
				MimePart msgnode = mime.getMessage();
				outbuf.append('(');
				getEnvelope(seqnum, msgnode, outbuf, tmpsb);
				outbuf.append(')');
				formatMimeStructure(msgnode, withExtensions, seqnum, outbuf, tmpsb);
				outbuf.append(' ').append(msgnode.linecnt, tmpsb);
			}
			if (withExtensions) {
				outbuf.append(" NIL"); //our MIME parser doesn't record MD5 headers
				formatMimeCommonExtensions(mime, outbuf);
			}
		}
		outbuf.append(')');
	}

	private static void formatMimeDisposition(MimePart mime, com.grey.base.utils.ByteChars outbuf)
	{
		outbuf.append('(');
		appendQuoted(mime.disposition_type, outbuf);
		outbuf.append(" (");
		boolean first = true;
		if (mime.disposition_filename != null) {
			appendQuoted(MimePart.ATTR_FILENAME, outbuf).append(' ');
			appendQuoted(mime.disposition_filename, outbuf);
			first = false;
		}
		if (mime.disposition_size != null) {
			if (!first) outbuf.append(' ');
			appendQuoted(MimePart.ATTR_SIZE, outbuf).append(' ');
			appendQuoted(mime.disposition_size, outbuf);
		}
		outbuf.append("))");
	}

	private static void formatMimeCommonExtensions(MimePart mime, com.grey.base.utils.ByteChars outbuf)
	{
		if (mime.disposition_type == null) {
			outbuf.append(" NIL");
		} else {
			outbuf.append(' ');
			formatMimeDisposition(mime, outbuf);
		}
		if (mime.language == null) {
			outbuf.append(" NIL");
		} else {
			outbuf.append(" (");
			appendQuoted(mime.language, outbuf);
			outbuf.append(')');
		}
		//don't bother with location
	}

	// As per the ABNF for 'address' in RFC-3501 section 9. The enclosing parentheses are added by caller.
	private static void formatMailAddress(String emaddr, com.grey.base.utils.ByteChars outbuf)
	{
		final int pos_open = emaddr.indexOf('<');
		final int pos_close = (pos_open == -1 ? -1 : emaddr.indexOf('>', pos_open));
		final int pos_dlm = emaddr.indexOf(com.grey.base.utils.EmailAddress.DLM_DOM, pos_open == -1 ? 0 : pos_open);
		int pos1 = 0;
		int pos2 = 0;

		// first comes the free-style personal name
		if (pos_open > 0) {
			while (emaddr.charAt(pos1) == ' ') pos1++;
			if (emaddr.charAt(pos1) == Defs.CHAR_QUOTE) pos1++;
			if (pos1 == pos_open) {
				pos2 = pos1;
			} else {
				// we do have a personal name part
				pos2 = pos_open - 1;
				while (pos2 > pos1 && emaddr.charAt(pos2) == ' ') pos2--;
				if (pos2 != pos1 && emaddr.charAt(pos2) != Defs.CHAR_QUOTE) pos2++;
			}
		}
		appendQuoted(emaddr, pos1, pos2, outbuf);

		// don't even bother looking for a source address
		outbuf.append(" NIL ");

		// now the mailbox part - treat entire address as local if ampersand is absent
		pos1 = (pos_open == -1 ? 0 : pos_open+1);
		pos2 = (pos_dlm == -1 ? pos_close : pos_dlm);
		appendQuoted(emaddr, pos1, pos2 == -1 ? emaddr.length() : pos2, outbuf);
		outbuf.append(' ');

		// finally, the domain part
		if (pos_dlm == -1) {
			outbuf.append("NIL");
		} else {
			pos1 = pos_dlm + 1;
			pos2 = pos_close;
			appendQuoted(emaddr, pos1, pos2 == -1 ? emaddr.length() : pos2, outbuf);
		}
	}

	/*
	 * See RFC-3501 section 9 for the full ABNF, but some of the relevant defs are:
		fetch="FETCH" SP sequence-set SP ("ALL" / "FULL" / "FAST" / fetch-att / "(" fetch-att *(SP fetch-att) ")")
		fetch-att="ENVELOPE" / "FLAGS" / "INTERNALDATE" / "RFC822" [".HEADER" / ".SIZE" / ".TEXT"] / "BODY" ["STRUCTURE"] / "UID"
						/ "BODY" section ["<" number "." nz-number ">"] / "BODY.PEEK" section ["<" number "." nz-number ">"]
		section = "[" [section-spec] "]"
		section-spec = section-msgtext / (section-part ["." section-text])
		section-text = section-msgtext / "MIME" ; text other than actual body part (headers, etc.)
		section-msgtext = "HEADER" / "HEADER.FIELDS" [".NOT"] SP header-list / "TEXT" ; top-level or MESSAGE/RFC822 part
		section-part = nz-number *("." nz-number) ; body part nesting
		header-list = "(" header-fld-name *(SP header-fld-name) ")"
		header-fld-name = astring
	 * So commands which utilise complex or multiple attributes might look like this:
	 * tag1 fetch 4 (BODY.Peek[2.1.header.fields.not (hdr1 hdr2)]<3.74>)
	 * tag2 fetch 4 (flags uid)
	 */
	private Object parseFetchAttribute(com.grey.base.utils.ByteChars attrs)
	{
		int pos = attrs.offset();
		int lmt = pos + attrs.size();
		while (pos != lmt && attrs.buffer()[pos] != ' ') {
			if (attrs.buffer()[pos] == '[') {
				pos = attrs.indexOf(pos - attrs.offset(), (byte)']');
				if (pos == -1) return "Unterminated [";
				pos += attrs.offset(); //restore raw offset - we will advance past the bracket below
			}
			pos++;
		}
		String attrname = new String(attrs.buffer(), attrs.offset(), pos - attrs.offset()).toUpperCase();
		attrs.advance(pos - attrs.offset());
		NumberList mime_coords = null;
		String[] hdrnames = null;
		int partial_off = 0;
		int partial_len = 0;
		boolean peek = false;

		// extract any Peek qualifier
		String part = ".PEEK";
		pos = attrname.indexOf(part);
		if (pos != -1) {
			peek = true;
			attrname = attrname.substring(0, pos) + attrname.substring(pos + part.length());
		}
		String replytag = attrname; //wait till now, because reply tag must also exclude the ".PEEK" bit

		// parse and extract BODY qualifiers
		part = "BODY[";
		if (attrname.startsWith(part)) {
			pos = part.length();
			int pos_close = attrname.indexOf(']', pos);
			if (pos_close == -1) return "Missing ] after section-spec";
			// parse any MIME-section specifier - eg. tag FETCH 2 BODY[1.1.MIME]
			if (Character.isDigit(attrname.charAt(pos))) {
				if (IMPLICIT_TEXT && Character.isDigit(attrname.charAt(pos_close-1))) {
					int pos_spc = attrname.indexOf(' ', pos);
					if (pos_spc == -1 || pos_spc > pos_close) {
						//This is an all-numeric section-spec, which I initially understood to mean the whole MIME part
						//matching those MIME coordinates, but both Thunderbird and Squirrelmail tend to say things like
						//BODY[1], BODY[2] (and even BODY[1.2] etc) when they just want the body.
						//It seems they're right, but allow this behaviour to be overriden via config/properties.
						attrname = attrname.substring(0, pos_close)+".TEXT"+attrname.substring(pos_close);
					}
				}
				while (Character.isDigit(attrname.charAt(pos))) {
					int adjust = 0;
					int pos2 = attrname.indexOf('.', pos);
					if (pos2 == -1) {
						pos2 = pos_close;
						adjust = -1;
					}
					int val = (int)parseDecimal(attrname, pos, pos2-pos);
					if (val == -1) return "Non-numeric section-part";
					if (mime_coords == null) mime_coords = new NumberList();
					mime_coords.append(val);
					pos = pos2 + 1 + adjust;
				}
				if (mime_coords != null) attrname = attrname.substring(0, part.length()) + attrname.substring(pos);
			}

			// parse any partial-sequence specifier, eg. BODY[]<0.2048>
			pos = attrname.indexOf(']');
			if (pos == -1) return "Error seeking close-brackets"; //can't happen, we already verified ']' above
			if (++pos != attrname.length()) {
				int pos2 = attrname.indexOf('.', pos);
				if (attrname.charAt(pos) != '<' || attrname.charAt(attrname.length() - 1) != '>' || pos2 == -1) return "Invalid partial sequence";
				try {
					partial_off = (int)StringOps.parseNumber(attrname, pos+1, pos2-pos-1, 10);
					partial_len = (int)StringOps.parseNumber(attrname, pos2+1, attrname.length()-pos2-2, 10);
				} catch (NumberFormatException ex) {
					return "Non-numeric partial-sequence";
				}
				attrname = attrname.substring(0, pos);
				//need to update the form of the echoed data item as well
				pos = replytag.indexOf("]<");
				pos = replytag.indexOf('.', pos);
				replytag = replytag.substring(0, pos)+">";
			}

			//parse any field names, eg. BODY[HEADER.FIELDS (FROM)]
			lmt = attrname.length() - 2;
			if (attrname.charAt(lmt) == ')') {
				int opn = attrname.indexOf('(');
				if (opn == -1) return "Unterminated )";
				pos = opn + 1;
				int cnt = (pos == lmt ? 0 : StringOps.count(attrname, pos, lmt-pos, ' ') + 1);
				if (cnt != 0) {
					hdrnames = new String[cnt];
					cnt = 0;
					while (pos < lmt) {
						int pos2 = pos;
						while (pos2 != lmt && attrname.charAt(pos2) != ' ') pos2++;
						hdrnames[cnt++] = attrname.substring(pos, pos2);
						pos = pos2 + 1;
					}
				}
				attrname = attrname.substring(0, opn - 1)+"]"; //strip the space before the open-bracket as well
			}
		}
		FetchOpDef def = shared.fopdefs.get(attrname);
		if (def == null) return "Unrecognised FETCH item="+attrname;
		FetchOp op = shared.fopImmutable.get(attrname);
		if (op == null) op = new FetchOp(def, replytag, peek); //parameterised op

		if (def.isSet(FetchOpDef.F_HASFLDS)) {
			op.hdrs = hdrnames;
		} else {
			if (hdrnames != null) return "Header names not supported for item="+attrname;
		}
		if (def.isSet(FetchOpDef.F_HASMIME)) {
			if ((op.mime_coords = mime_coords) == null) {
				if (def.isSet(FetchOpDef.F_MDTYMIME)) return "MIME coords are required for item="+attrname;
			}
		} else {
			if (mime_coords != null) return "MIME coords not supported for item="+attrname;
		}
		op.partial_off = partial_off;
		op.partial_len = partial_len;
		return op;
	}

	// map IMAP's string flags (\Seen, etc) to Maildir's single-char flags (S, etc)
	private String parseFlags(com.grey.base.utils.ByteChars imapflags, StringBuilder msflags,
			boolean create) throws java.io.IOException
	{
		int pos = 0;
		int pos2;
		do {
			//get next flag from imapflags argument (an IMAP string)
			pos2 = imapflags.indexOf(pos, (byte)' ');
			if (pos2 == -1) pos2 = imapflags.size();

			//loop through supported flags to match it to a Maildir flag (single char)
			char msflag = shared.msgFlags.getFlagMS(imapflags, pos, pos2 - pos, create);
			if (msflag != 0) {
				//found a matching Maildir flag, so append it if non-duplicate
				if (msflag == MaildirStore.MSGFLAG_RECENT) {
					if (!IGNORE_SET_RECENT) return "Cannot set "+IMAP4Protocol.MSGFLAG_RECENT;
				} else {
					if (StringOps.indexOf(msflags, msflag) == -1) msflags.append(msflag);
				}
			}
			pos = pos2 + 1;
		} while (pos2 != imapflags.size());

		//not strictly required by the RFC, but it makes ImapTest happy
		reportMailboxFlags(null, REPORT_NEWFLAGS && sess.currentMailbox() != null);
		return null;
	}

	// Format is dd-mon-yyyy
	// - dd: 1 or 2 digit day of month
	// - mon: 3-letter month name
	// - yyyy: 4-digit year
	private long parseSearchDate(CharSequence term)
	{
		int pos1 = StringOps.indexOf(term, '-');
		int pos2 = (pos1 == -1 ? -1 : StringOps.indexOf(term, pos1+1, '-'));
		int mlen = pos2 - pos1 - 1;
		if (pos1 <= 0 || pos2 == -1 || mlen != 3 || pos2 == term.length() - 1) return -1;
		int d = (int)parseDecimal(term, 0, pos1);
		int y = (int)parseDecimal(term, pos2+1, term.length() - pos2 - 1);
		int m = 0;
		for (int idx = 0; idx != TimeOps.shortmonths.length; idx++) {
			if (StringOps.sameSeqNoCase(term, pos1+1, mlen, TimeOps.shortmonths[idx])) {
				m = idx;
				break;
			}
		}
		shared.dtcal.clear();
		shared.dtcal.set(java.util.Calendar.YEAR, y);
		shared.dtcal.set(java.util.Calendar.MONTH, m);
		shared.dtcal.set(java.util.Calendar.DAY_OF_MONTH, d);
		return shared.dtcal.getTimeInMillis();
	}

	// See definition of sequence-set formal syntax in RFC-3501 section 9
	private NumberList parseSequenceSet(com.grey.base.utils.ByteChars spec, NumberList lst,
			boolean uidmode, boolean srchmode)
	{
		lst.clear();
		int msgcnt = sess.currentView().getMsgCount();
		if (msgcnt == 0) {
			if (srchmode || StringOps.sameSeq("*", spec) || StringOps.sameSeq("1:*", spec)) return lst; //empty list
			return null; //illegal access on empty mailbox
		}

		int pos = 0;
		int lmt;
		do {
			lmt = spec.indexOf(pos, (byte)',');
			if (lmt == -1) lmt = spec.size();
			if (!parseSequenceRange(spec, pos, lmt, lst, uidmode, srchmode, msgcnt)) return null;
			pos = lmt + 1;
		} while (lmt != spec.size());

		if (!srchmode) lst.sort();
		return lst;
	}

	private boolean parseSequenceRange(com.grey.base.utils.ByteChars spec, int off, int lmt, NumberList lst,
			boolean uidmode, boolean srchmode, int msgcnt)
	{
		int pos = spec.indexOf(off, (byte)':');
		int min;
		int max;
		if (pos == -1 || pos >= lmt) {
			min = parseSequenceNumber(spec, off, lmt, uidmode, msgcnt);
			max = min;
		} else {
			min = parseSequenceNumber(spec, off, pos, uidmode, msgcnt);
			max = parseSequenceNumber(spec, pos+1, lmt, uidmode, msgcnt);
		}

		if (max < min) {
			int swap = max;
			max = min;
			min = swap;
		}
		int hardmax = (uidmode ? sess.currentView().getMessageUID(msgcnt) : msgcnt);
		if (min <= 0) return false;
		if (max > hardmax) {
			if (srchmode || uidmode) {
				//silently truncate out-of-range sequences
				max = hardmax;
			} else {
				return false;
			}
		}

		for (int id = min; id <= max; id++) {
			int seqnum = id;
			boolean uniq = true;
			if (uidmode) {
				//non-existent UID is ignored without any error - see RFC-3501 6.4.8
				seqnum = sess.currentView().getMessageSequence(id);
				if (seqnum == 0) continue;
			}
			for (int idx = 0; idx != lst.size(); idx++) {
				if (lst.get(idx) == seqnum) {
					uniq = false;
					break;
				}
			}
			if (uniq) lst.append(seqnum);
		}
		return true;
	}

	private int parseSequenceNumber(com.grey.base.utils.ByteChars spec, int off, int lmt, boolean uidmode, int msgcnt)
	{
		if (spec.charAt(off) == '*') {
			if (lmt - off != 1) return -1;
			if (!uidmode) return msgcnt;
			return sess.currentView().getMessageUID(msgcnt);
		}
		return (int)parseDecimal(spec, off, lmt - off);
	}

	private String mapMailboxNameToMS(CharSequence mbxname)
	{
		String strval;
		if (StringOps.sameSeqNoCase(IMAP4Protocol.MBXNAME_INBOX, mbxname)) {
			strval = String.valueOf(sess.getHierarchyDelimiter());
		} else {
			strval = sess.existsMailbox(mbxname);
		}
		return strval;
	}

	private void releaseSASL()
	{
		switch (saslmech.mechanism)
		{
		case PLAIN:
			shared.saslmechs_plain.store((com.grey.base.sasl.PlainServer)saslmech);
			break;
		case CRAM_MD5:
			shared.saslmechs_cmd5.store((com.grey.base.sasl.CramMD5Server)saslmech);
			break;
		case EXTERNAL:
			shared.saslmechs_ext.store((com.grey.base.sasl.ExternalServer)saslmech);
			break;
		default:
			getLogger().error(pfx_log+": Unrecognised SASL mechanism="+saslmech.mechanism);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised SASL mechanism="+saslmech.mechanism);
			break;
		}
		saslmech = null;
	}

	private String isEnabled(IMAP4Protocol.AUTHTYPE auth)
	{
		if (!shared.authtypes_enabled.contains(auth)) {
			return "Unsupported authentication method";
		}
		if (!usingSSL() && shared.authtypes_ssl.contains(auth)) {
			return "Requires SSL";
		}
		return null;
	}

	private void recordConnection()
	{
		com.grey.base.utils.TSAP.get(getRemoteIP(), getRemotePort(), remote_tsap, true);
		if (shared.transcript != null) {
			com.grey.base.utils.TSAP local_tsap = com.grey.base.utils.TSAP.get(getLocalIP(), getLocalPort(), shared.tmptsap, true);
			shared.transcript.connection_in(pfx_transcript, remote_tsap.dotted_ip, remote_tsap.port, local_tsap.dotted_ip, local_tsap.port, getSystemTime(), usingSSL());
		}
	}

	private void setLogPrefix() {
		pfx_log = shared.prototype_server.pfx_log+"-"+cnxid;
		int pos = pfx_log.lastIndexOf("/E");
		pfx_transcript = pfx_log.substring(pos+1);
	}

	private static boolean matchesCommand(com.grey.base.utils.ByteChars reqcmd, com.grey.base.utils.ByteChars targetcmd)
	{
		final int cmdlen = targetcmd.size();
		if (reqcmd.size() != cmdlen) return false;
		for (int idx = 0; idx != cmdlen; idx++) {
			if (Character.toUpperCase(reqcmd.byteAt(idx)) != targetcmd.byteAt(idx)) return false;
		}
		return true;
	}

	// RFC spec mandates one space between terms, but be more tolerant
	private static boolean getNextTerm(ByteArrayRef line, com.grey.base.utils.ByteChars term, int flags)
	{
		//seek to start of term
		int off = line.offset();
		int lmt = off + line.size();
		while (line.buffer()[off] == ' ') off++;
		int off1 = off;
		int opncnt = 0;
		byte parenth_open = 0;
		byte parenth_close = 0;
		byte chval;

		while (((chval = line.buffer()[off]) > ' ') || opncnt != 0) {
			if (opncnt == 0) {
				if (chval == Defs.CHAR_QUOTE) {
					opncnt = 1;
					parenth_open = chval;
					parenth_close = chval;
				} else if (chval == '(') {
					opncnt = 1;
					parenth_open = chval;
					parenth_close = ')';
				} else if (chval == '[') {
					opncnt = 1;
					parenth_open = chval;
					parenth_close = ']';
				} else if (chval == '{') {
					opncnt = 1;
					parenth_open = chval;
					parenth_close = '}';
				}
			} else {
				//check closing symbol first, in case open/close are one and the same
				if (chval == parenth_close) {
					opncnt--;
				} else if (chval == parenth_open) {
					opncnt++;
				}
			}
			if (++off == lmt) {
				if (opncnt != 0) return false; //must be unterminated
				break; //arg extended to end of buffer - that's ok
			}
		}
		line.advance(off - line.offset()); //advance past the term
		int len = off - off1;

		if (line.buffer()[off1] == parenth_open) {
			// strip enclosing parentheses from the term
			off1++;
			len -= 2;
		}
		if (len == 0 && !Defs.isFlagSet(flags, TERM_EMPTY)) return false;

		// don't just clear() for len==0 because caller might need to check quoting delimiter of blank terms
		if (Defs.isFlagSet(flags, TERM_COPY)) {
			term.populate(line.buffer(), off1, len);
		} else {
			term.set(line.buffer(), off1, len);
		}
		return true;
	}

	private static int parseLiteralSize(CharSequence octetcnt)
	{
		int len = octetcnt.length();
		if (octetcnt.charAt(len - 1) == IMAP4Protocol.LITERALPLUS) len--;
		return (int)parseDecimal(octetcnt, 0, len);
	}

	private static boolean isNonSynchLiteral(CharSequence octetcnt)
	{
		return (octetcnt.charAt(octetcnt.length() - 1) == IMAP4Protocol.LITERALPLUS);
	}

	private static long parseDecimal(CharSequence cs, int off, int len)
	{
		try {
			return StringOps.parseNumber(cs, off, len, 10);
		} catch (NumberFormatException ex) {
			return -1;
		}
	}

	private static long parseDecimal(CharSequence cs)
	{
		return parseDecimal(cs, 0, cs.length());
	}

	private static com.grey.base.utils.ByteChars appendQuoted(CharSequence cs, int off, int lmt, com.grey.base.utils.ByteChars outbuf)
	{
		if (off == lmt) return outbuf.append("NIL");
		return outbuf.append(Defs.CHAR_QUOTE).append(cs, off, lmt-off).append(Defs.CHAR_QUOTE);
	}

	private static com.grey.base.utils.ByteChars appendQuoted(CharSequence cs, com.grey.base.utils.ByteChars outbuf)
	{
		return appendQuoted(cs, 0, cs == null ? 0 : cs.length(), outbuf);
	}

	// The end-of-line sequence is canonically a CR-LF, but we are prepared to handle just an LF
	private static void stripEOL(ByteArrayRef rcvdata, boolean trailspace)
	{
		rcvdata.incrementSize(-1); //EOL sequence contains at least one character
		if (trailspace) {
			//strip all trailing white space, not just the EOL
			while (rcvdata.size() != 0 && rcvdata.byteAt(rcvdata.size() - 1) <= ' ') rcvdata.incrementSize(-1);
		} else {
			// strip only the EOL
			while (rcvdata.size() != 0 && rcvdata.byteAt(rcvdata.size() - 1) < ' ') rcvdata.incrementSize(-1);
		}
	}

	private static IMAP4Protocol.AUTHTYPE getAuthType(com.grey.base.utils.ByteChars name)
	{
		IMAP4Protocol.AUTHTYPE authtype;
		if (name.equalsIgnoreCase(com.grey.base.sasl.SaslEntity.MECHNAME_PLAIN)) {
			authtype = IMAP4Protocol.AUTHTYPE.SASL_PLAIN;
		} else if (name.equalsIgnoreCase(com.grey.base.sasl.SaslEntity.MECHNAME_CMD5)) {
			authtype = IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5;
		} else if (name.equalsIgnoreCase(com.grey.base.sasl.SaslEntity.MECHNAME_EXTERNAL)) {
			authtype = IMAP4Protocol.AUTHTYPE.SASL_EXTERNAL;
		} else {
			authtype = null;
		}
		return authtype;
	}

	@SuppressWarnings("static-access")
	private static void makeTime(long systime, java.util.Calendar dtcal, StringBuilder sb)
	{
		sb.setLength(0);
		dtcal.setTimeInMillis(systime);
		StringOps.zeroPad(sb, dtcal.get(dtcal.DAY_OF_MONTH), 2);
		sb.append('-').append(TimeOps.shortmonths[dtcal.get(dtcal.MONTH)]);
		sb.append('-').append(dtcal.get(dtcal.YEAR));
		sb.append(' ');
		StringOps.zeroPad(sb, dtcal.get(dtcal.HOUR_OF_DAY), 2).append(':');
		StringOps.zeroPad(sb, dtcal.get(dtcal.MINUTE), 2).append(':');
		StringOps.zeroPad(sb, dtcal.get(dtcal.SECOND), 2);
		sb.append(' ');
		TimeOps.withDiffZone(dtcal, sb);
	}

	@Override
	public StringBuilder dumpAppState(StringBuilder sb)
	{
		if (sb == null) sb = new StringBuilder();
		sb.append(pfx_log).append('/').append(pstate).append("/0x").append(Integer.toHexString(state2));
		if (sess != null) {
			sb.append(": User=").append(sess.getUsername());
			String mbxname = sess.currentMailbox();
			if (mbxname != null) {
				if (mbxname.length() == 1) mbxname = IMAP4Protocol.MBXNAME_INBOX;
				sb.append("/Mailbox=").append(mbxname);
			}
		}
		return sb;
	}

	@Override
	public String toString() {
		return getClass().getName()+"= E"+getCMID();
	}
}

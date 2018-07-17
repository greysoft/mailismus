/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.pop3.server;

import com.grey.mailismus.Task;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.pop3.POP3Protocol;
import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.ByteOps;
import com.grey.logging.Logger.LEVEL;

public final class POP3Server
	extends com.grey.naf.reactor.CM_Server
	implements com.grey.naf.reactor.TimerNAF.Handler
{
	private static final String TOKEN_HOSTNAME = "%H%";
	private static final String RSPMSG_GREET = "Mailismus POP3 Ready";
	private static final String RSPMSG_INVAUTH = "Authentication failed";
	private static final String RSPMSG_DUPAUTH = "This user is already connected";
	private static final String RSPMSG_NOSUCHMSG = "No such message";
	private static final String RSPMSG_ERRPROTO = "Bad command";
	private static final String RSPMSG_NEEDSSL = "Requires SSL mode";
	private static final String RSPMSG_TOP = "top of message follows";
	private static final String RSPMSG_EMPTYCHALLENGE = POP3Protocol.STATUS_AUTH;
	private static final String RSPMSG_OK = "";

	private enum PROTO_STATE {S_DISCON, S_AUTH, S_AUTHPASS, S_SASL, S_TRANSACT, S_UPDATE, S_STLS}
	private enum PROTO_EVENT {E_CONNECTED, E_DISCONNECT, E_DISCONNECTED, E_QUIT, E_STLS,
								E_USERNAME, E_USERPASS, E_APOP, E_SASL_PLAIN, E_SASL_CMD5, E_SASL_EXTERNAL, E_SASLRSP,
								E_STAT, E_LIST, E_RETR, E_DELE, E_CAPA, E_TOP, E_UIDL,
								E_NOOP, E_RSET, E_BADCMD, E_LOCALERROR}

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
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_USERNAME, POP3Protocol.CMDREQ_USER, PROTO_STATE.S_AUTHPASS),
		new FSM_Trigger(PROTO_STATE.S_AUTHPASS, PROTO_EVENT.E_USERPASS, POP3Protocol.CMDREQ_PASS, null),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_APOP, POP3Protocol.CMDREQ_APOP, null),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_SASL_PLAIN, POP3Protocol.CMDREQ_SASL_PLAIN, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_SASL_CMD5, POP3Protocol.CMDREQ_SASL_CMD5, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_SASL_EXTERNAL, POP3Protocol.CMDREQ_SASL_EXTERNAL, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_RSET, POP3Protocol.CMDREQ_RSET, null),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_STAT, POP3Protocol.CMDREQ_STAT, null),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_LIST, POP3Protocol.CMDREQ_LIST, null),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_RETR, POP3Protocol.CMDREQ_RETR, null),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_DELE, POP3Protocol.CMDREQ_DELE, null),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_TOP, POP3Protocol.CMDREQ_TOP, null),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_UIDL, POP3Protocol.CMDREQ_UIDL, null),
		new FSM_Trigger(null, PROTO_EVENT.E_QUIT, POP3Protocol.CMDREQ_QUIT, null),
		new FSM_Trigger(null, PROTO_EVENT.E_CAPA, POP3Protocol.CMDREQ_CAPA, null),
		new FSM_Trigger(PROTO_STATE.S_AUTH, PROTO_EVENT.E_STLS, POP3Protocol.CMDREQ_STLS, PROTO_STATE.S_STLS),
		new FSM_Trigger(PROTO_STATE.S_TRANSACT, PROTO_EVENT.E_NOOP, POP3Protocol.CMDREQ_NOOP, null)
	};

	private static final int TMRTYPE_SESSIONTMT = 1;
	private static final int TMRTYPE_DISCON = 2;

	private static final byte S2_ENDED = 1 << 0; //we have already called endConnection()
	private static final byte S2_REQWAIT = 1 << 1; //we're waiting for the remote client to send a request

	// Define fields which can be shared by all instances of this class that were created from the same prototype,
	// secure in the knowledge that they are all running in the same thread, and can therefore share any objects
	// whose value or state does not persist across invocations of this object.
	private static final class SharedFields
	{
		// assorted shared settings and objects
		final com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars> currentUsers = new com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars>();
		final com.grey.naf.BufferSpec bufspec;
		final com.grey.mailismus.ms.maildir.MaildirStore ms;
		final com.grey.mailismus.directory.Directory dtory;
		final SaslAuthenticator saslauth;
		final java.security.MessageDigest md5proc;
		final com.grey.mailismus.Transcript transcript;
		final java.util.HashSet<POP3Protocol.AUTHTYPE> authtypes_enabled;
		final java.util.HashSet<POP3Protocol.AUTHTYPE> authtypes_ssl; //only allowed in SSL mode
		final com.grey.base.collections.ObjectWell<com.grey.base.sasl.PlainServer> saslmechs_plain;
		final com.grey.base.collections.ObjectWell<com.grey.base.sasl.CramMD5Server> saslmechs_cmd5;
		final com.grey.base.collections.ObjectWell<com.grey.base.sasl.ExternalServer> saslmechs_ext;
		final String capa_sasl; //SASL CAPA string for non-SSL mode
		final String capa_sasl_ssl; //SASL CAPA string for SSL mode
		final int expire; //we only enforce expire=0, finite limits are simply a notice of externally enforced policies
		final long tmtprotocol;
		final long delay_chanclose; //has solved abort-on-close issues in the past
		final String greetcore;
		final POP3Server prototype_server;
		final com.grey.mailismus.AppConfig appConfig;

		// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned replies
		final java.nio.ByteBuffer pop3rsp_ok;
		final java.nio.ByteBuffer pop3rsp_greet;
		final java.nio.ByteBuffer pop3rsp_top;
		final java.nio.ByteBuffer pop3rsp_invauth;
		final java.nio.ByteBuffer pop3rsp_dupauth;
		final java.nio.ByteBuffer pop3rsp_nosuchmsg;
		final java.nio.ByteBuffer pop3rsp_errproto;
		final java.nio.ByteBuffer pop3rsp_needssl;
		final java.nio.ByteBuffer pop3rsp_emptychallenge;
		final java.nio.ByteBuffer pop3rsp_endrsp;
		final java.nio.ByteBuffer pop3rsp_endmsg;
		java.nio.ByteBuffer altrsp_badcmd;

		// temp work areas, pre-allocated for efficiency
		final com.grey.base.utils.TSAP tmptsap = new com.grey.base.utils.TSAP();
		final StringBuilder tmpsb = new StringBuilder();
		final com.grey.base.utils.ByteChars tmpbc = new com.grey.base.utils.ByteChars();
		final com.grey.base.utils.ByteChars tmplightbc = new com.grey.base.utils.ByteChars(-1); //lightweight object without own storage
		java.nio.ByteBuffer tmpniobuf;

		public SharedFields(com.grey.base.config.XmlConfig cfg, com.grey.naf.reactor.Dispatcher dsptch, com.grey.mailismus.Task task,
				POP3Server proto, com.grey.naf.SSLConfig sslcfg, String logpfx)
			throws java.security.GeneralSecurityException
		{
			if (task.getMS() == null || task.getDirectory() == null) {
				throw new MailismusConfigException(logpfx+"MessageStore and Directory must be configured");
			}
			appConfig = task.getAppConfig();
			ms = (com.grey.mailismus.ms.maildir.MaildirStore)task.getMS();
			dtory = task.getDirectory();
			saslauth = new SaslAuthenticator(dtory);
			prototype_server = proto;
			expire = cfg.getInt("expire", true, -1);
			tmtprotocol = cfg.getTime("timeout", com.grey.base.utils.TimeOps.parseMilliTime("2m")); //NB: RFC-1939 says at least 10 mins
			delay_chanclose = cfg.getTime("delay_close", 0);
			bufspec = new com.grey.naf.BufferSpec(cfg, "niobuffers", 256, 128);
			transcript = com.grey.mailismus.Transcript.create(dsptch, cfg, "transcript");

			authtypes_enabled = configureAuthTypes("authtypes", cfg, true, POP3Protocol.AUTHTYPE.values(), null, logpfx);
			if (!dtory.supportsPasswordLookup()) {
				authtypes_enabled.remove(POP3Protocol.AUTHTYPE.SASL_CRAM_MD5);
				authtypes_enabled.remove(POP3Protocol.AUTHTYPE.APOP);
			}
			if (sslcfg == null || (sslcfg.latent && !sslcfg.mdty)) {
				POP3Protocol.AUTHTYPE[] sslonly = new POP3Protocol.AUTHTYPE[]{POP3Protocol.AUTHTYPE.SASL_EXTERNAL, POP3Protocol.AUTHTYPE.SASL_PLAIN};
				if (sslcfg == null) sslonly[1] = null;
				authtypes_ssl = configureAuthTypes("authtypes_ssl", cfg, false, sslonly, authtypes_enabled, logpfx);
			} else {
				authtypes_ssl = authtypes_enabled;
			}
			capa_sasl = setCapaSASL(authtypes_enabled, authtypes_ssl);
			capa_sasl_ssl = setCapaSASL(authtypes_enabled, null);

			if (authtypes_enabled.contains(POP3Protocol.AUTHTYPE.SASL_PLAIN)) {
				com.grey.base.sasl.ServerFactory fact = new com.grey.base.sasl.ServerFactory(com.grey.base.sasl.SaslEntity.MECH.PLAIN, saslauth, true);
				saslmechs_plain = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.PlainServer>(null, fact, "POP3S_SaslPlain", 0, 0, 1);
			} else {
				saslmechs_plain = null;
			}
			if (authtypes_enabled.contains(POP3Protocol.AUTHTYPE.SASL_CRAM_MD5)) {
				com.grey.base.sasl.ServerFactory fact = new com.grey.base.sasl.ServerFactory(com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5, saslauth, true);
				saslmechs_cmd5 = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.CramMD5Server>(null, fact, "POP3S_SaslCMD5", 0, 0, 1);
			} else {
				saslmechs_cmd5 = null;
			}
			if (authtypes_enabled.contains(POP3Protocol.AUTHTYPE.SASL_EXTERNAL)) {
				com.grey.base.sasl.ServerFactory fact = new com.grey.base.sasl.ServerFactory(com.grey.base.sasl.SaslEntity.MECH.EXTERNAL, saslauth, true);
				saslmechs_ext = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.ExternalServer>(null, fact, "POP3S_SaslExternal", 0, 0, 1);
			} else {
				saslmechs_ext = null;
			}

			greetcore = cfg.getValue("greet", true, RSPMSG_GREET).replace(TOKEN_HOSTNAME, appConfig.getAnnounceHost());
			String greetmsg = POP3Protocol.STATUS_OK+" "+greetcore+POP3Protocol.EOL;
			if (authtypes_enabled.contains(POP3Protocol.AUTHTYPE.APOP)) {
				md5proc = java.security.MessageDigest.getInstance(com.grey.base.crypto.Defs.ALG_DIGEST_MD5);
				pop3rsp_greet = null;
			} else {
				pop3rsp_greet = com.grey.mailismus.Task.constBuffer(greetmsg);
				md5proc = null;
			}

			pop3rsp_ok = responseBuffer(true, RSPMSG_OK);
			pop3rsp_top = responseBuffer(true, RSPMSG_TOP);
			pop3rsp_invauth = responseBuffer(false, RSPMSG_INVAUTH);
			pop3rsp_dupauth = responseBuffer(false, RSPMSG_DUPAUTH);
			pop3rsp_nosuchmsg = responseBuffer(false, RSPMSG_NOSUCHMSG);
			pop3rsp_errproto = responseBuffer(false, RSPMSG_ERRPROTO);
			pop3rsp_needssl = responseBuffer(false, RSPMSG_NEEDSSL);
			pop3rsp_emptychallenge = com.grey.mailismus.Task.constBuffer(RSPMSG_EMPTYCHALLENGE+POP3Protocol.EOL);
			pop3rsp_endrsp = com.grey.mailismus.Task.constBuffer(POP3Protocol.ENDRSP);
			pop3rsp_endmsg = com.grey.mailismus.Task.constBuffer(POP3Protocol.EOL+POP3Protocol.ENDRSP);
		}

		private static java.nio.ByteBuffer responseBuffer(boolean ok, CharSequence msg)
		{
			msg = (msg == null || msg.length() == 0 ? "" : " "+msg);
			msg = (ok ? POP3Protocol.STATUS_OK : POP3Protocol.STATUS_ERR)+msg+POP3Protocol.EOL;
			return com.grey.mailismus.Task.constBuffer(msg);
		}
	}

	private static final class SaslAuthenticator
		extends com.grey.base.sasl.SaslServer.Authenticator
	{
		private final com.grey.mailismus.directory.Directory dtory;
		SaslAuthenticator(com.grey.mailismus.directory.Directory d) {dtory=d;}
		@Override
		public boolean saslAuthenticate(com.grey.base.utils.ByteChars usrnam, com.grey.base.utils.ByteChars passwd) {
			if (passwd == null) return dtory.isLocalUser(usrnam);
			return dtory.passwordVerify(usrnam, passwd);
		}
		@Override
		public com.grey.base.utils.ByteChars saslPasswordLookup(com.grey.base.utils.ByteChars usrnam) {
			return dtory.passwordLookup(usrnam);
		}
	}

	// This class maps the new Listener.Server design to the original prototype scheme on which
	// this server is still based.
	public static final class Factory
		implements com.grey.naf.reactor.ConcurrentListener.ServerFactory
	{
		private final POP3Server prototype;

		public Factory(com.grey.naf.reactor.CM_Listener l, com.grey.base.config.XmlConfig cfg)
				throws java.security.GeneralSecurityException {
			prototype = new POP3Server(l, cfg);
		}

		@Override
		public POP3Server factory_create() {return new POP3Server(prototype);}
		@Override
		public Class<POP3Server> getServerClass() {return POP3Server.class;}
		@Override
		public void shutdown() {prototype.abortServer();}
	}

	private final SharedFields shared;
	private final com.grey.base.utils.TSAP remote_tsap = new com.grey.base.utils.TSAP();
	private final com.grey.base.utils.ByteChars username = new com.grey.base.utils.ByteChars();
	private final com.grey.base.utils.ByteChars apopToken;
	private com.grey.mailismus.ms.maildir.InboxSession sess;
	private com.grey.base.sasl.SaslServer saslmech;
	private PROTO_STATE pstate;
	private byte state2; //secondary-state, qualifying some of the pstate phases
	private int msgdelcnt;
	private boolean[] msgdeletes = new boolean[0];
	private com.grey.naf.reactor.TimerNAF tmr_exit;
	private com.grey.naf.reactor.TimerNAF tmr_sesstmt;
	private int curmsgnum; //message being acted on by current command, numbered from 1 (-1 means no current msg)
	private int cnxid; //increments with each incarnation - useful for distinguishing Transcript logs
	private String pfx_log;
	private String pfx_transcript;

	private void setState2(byte f) {state2 |= f;}
	private void clearState2(byte f) {state2 &= ~f;}
	private boolean isState2(byte f) {return ((state2 & f) != 0);}

	// This is the constructor for the prototype server object
	public POP3Server(com.grey.naf.reactor.CM_Listener l, com.grey.base.config.XmlConfig cfg)
			throws java.security.GeneralSecurityException
	{
		super(l, null, null);
		String stem = "POP3-Server";
		pfx_log = stem+"/E"+getCMID();
		String pfx = stem+(getSSLConfig() == null || getSSLConfig().latent ? "" : "/SSL")+": ";
		shared = new SharedFields(cfg, getDispatcher(), com.grey.mailismus.Task.class.cast(getListener().getController()), this, getSSLConfig(), pfx);
		apopToken = null;
		String txt = (shared.authtypes_ssl.size() == 0 ? null : shared.authtypes_ssl.size()+"/"+shared.authtypes_ssl);
		if (txt != null && shared.authtypes_ssl.size() == shared.authtypes_enabled.size()) txt = "all";
		getLogger().info(pfx+"authtypes="+shared.authtypes_enabled.size()+"/"+shared.authtypes_enabled+(txt==null?"":"; SSL-only="+txt));
		getLogger().info(pfx+"expire="+(shared.expire==-1?"NEVER":(shared.expire==0?"IMMED":shared.expire+" days")));
		getLogger().info(pfx+"timeout="+com.grey.base.utils.TimeOps.expandMilliTime(shared.tmtprotocol)
				+"; delay_close="+shared.delay_chanclose);
		getLogger().info(pfx+"Declare self as '"+shared.appConfig.getProductName()+"' on "+shared.appConfig.getAnnounceHost());
		getLogger().trace(pfx+shared.bufspec);
	}

	// This is the constructor for the active server objects, which actually participate in POP3 sessions
	POP3Server(POP3Server proto)
	{
		super(proto.getListener(), proto.shared.bufspec, proto.shared.bufspec);
		shared = proto.shared;
		apopToken = (shared.authtypes_enabled.contains(POP3Protocol.AUTHTYPE.APOP) ? new com.grey.base.utils.ByteChars() : null);
		setLogPrefix();
	}

	@Override
	protected void connected() throws java.io.IOException
	{
		cnxid++;
		pstate = PROTO_STATE.S_AUTH;
		state2 = 0;
		username.clear();
		setLogPrefix();
		if (getLogger().isActive(LEVEL.TRC) || (shared.transcript != null)) recordConnection();
		raiseEvent(PROTO_EVENT.E_CONNECTED, null, null);
	}

	@Override
	public void ioDisconnected(CharSequence diagnostic)
	{
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, diagnostic);
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

	private void endConnection(CharSequence discmsg)
	{
		if (isState2(S2_ENDED)) return; //shutdown events criss-crossing each other - that's ok
		setState2(S2_ENDED);

		if (tmr_sesstmt != null) {
			tmr_sesstmt.cancel();
			tmr_sesstmt = null;
		}

		if (shared.transcript != null) {
			CharSequence reason = "Disconnect";
			if (discmsg != null) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append(reason).append(" - ").append(discmsg);
				reason = shared.tmpsb;
			}
			shared.transcript.event(pfx_transcript, reason, getSystemTime());
		}
		shared.currentUsers.remove(username);
		if (sess != null) sess.endSession();
		sess = null;
		if (saslmech != null) releaseSASL();
		remote_tsap.clear();
		clearState2(S2_REQWAIT);
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
		if (shared.transcript != null) shared.transcript.data_in(pfx_transcript, rcvdata, getSystemTime());
		if (pstate == PROTO_STATE.S_DISCON) return; //this method can be called in a loop, so skip it after a disconnect
		clearState2(S2_REQWAIT);

		if (pstate == PROTO_STATE.S_SASL) {
			//strip End-of-Line sequence, but not any trailing spaces
			while (rcvdata.size() != 0 && rcvdata.byteAt(rcvdata.size() - 1) < ' ') rcvdata.incrementSize(-1);
			handleEvent(PROTO_EVENT.E_SASLRSP, rcvdata, null);
			return;
		}
		int triggercnt = fsmTriggers.length;

		for (int idx = 0; idx != triggercnt; idx++) {
			FSM_Trigger trigger = fsmTriggers[idx];
			if (trigger.state == null || trigger.state == pstate) {
				if ((trigger.cmd == null) || matchesCommand(rcvdata, trigger.cmd)) {
					if (trigger.new_state != null) transitionState(trigger.new_state);
					handleEvent(trigger.evt, rcvdata, null);
					return;
				}
			}
		}
		raiseEvent(PROTO_EVENT.E_BADCMD, null, null);
	}

	private void transitionState(PROTO_STATE newstate)
	{
		pstate = newstate;
	}

	private PROTO_STATE raiseEvent(PROTO_EVENT evt, PROTO_STATE newstate, CharSequence discmsg)
			throws java.io.IOException
	{
		if (newstate != null) transitionState(newstate);
		return handleEvent(evt, null, discmsg);
	}

	// this eliminate the Throws declaration for events that are known not to throw
	private PROTO_STATE raiseSafeEvent(PROTO_EVENT evt, PROTO_STATE newstate, CharSequence discmsg)
	{
		try {
			raiseEvent(evt, newstate, discmsg);
		} catch (Throwable ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed to issue event="+evt);
			endConnection("Failed to issue event="+evt+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
		return pstate;
	}

	private PROTO_STATE handleEvent(PROTO_EVENT evt, ByteArrayRef rcvdata, CharSequence discmsg) throws java.io.IOException
	{
		int msgnum = curmsgnum;
		curmsgnum = -1;
		com.grey.base.utils.ByteChars xmtbc;
		boolean is_valid;
		int off;

		switch (evt)
		{
		case E_CONNECTED:
			if (shared.authtypes_enabled.contains(POP3Protocol.AUTHTYPE.APOP)) {
				com.grey.base.sasl.SaslEntity.setNonce(apopToken.clear(), "POP3", getCMID(), shared.tmpsb);
				xmtbc = shared.tmpbc.clear();
				xmtbc.append(POP3Protocol.STATUS_OK).append(' ').append(shared.greetcore);
				xmtbc.append(' ').append(apopToken).append(POP3Protocol.EOL);
				transmit(xmtbc);
			} else {
				transmit(shared.pop3rsp_greet);
			}
			break;

		case E_DISCONNECTED:
		case E_DISCONNECT:
			endConnection(discmsg);
			break;

		case E_STLS:
			if (getSSLConfig() == null || usingSSL()) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			transmit(shared.pop3rsp_ok);
			startSSL();
			break;

		case E_USERNAME:
			if (!isEnabled(POP3Protocol.AUTHTYPE.USERPASS)) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			username.populateBytes(rcvdata);
			transmit(shared.pop3rsp_ok);
			break;

		case E_USERPASS:
			shared.tmplightbc.set(rcvdata);
			is_valid = shared.dtory.passwordVerify(username, shared.tmplightbc);
			processLogin(is_valid);
			break;

		case E_APOP:
			username.populateBytes(rcvdata);
			off = username.indexOf((byte)' ');
			if (!isEnabled(POP3Protocol.AUTHTYPE.APOP) || off == -1) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			username.setSize(off - username.offset());
			com.grey.base.utils.ByteChars passwd = shared.dtory.passwordLookup(username);
			if (passwd == null) {
				is_valid = false;
			} else {
				apopToken.append(passwd);
				shared.md5proc.reset();
				char cdigest[] = com.grey.base.crypto.Ascii.digest(apopToken, shared.md5proc);
				shared.tmplightbc.set(username.buffer(), off + 1, rcvdata.size() - username.size() - 1);
				is_valid = shared.tmplightbc.equalsChars(cdigest, 0, cdigest.length);
			}
			processLogin(is_valid);
			break;

		case E_SASL_PLAIN:
			if (!isEnabled(POP3Protocol.AUTHTYPE.SASL_PLAIN)) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			saslmech = shared.saslmechs_plain.extract().init();
			if (rcvdata.size() == 0) {
				//The Auth command didn't include an initial response, so send an empty initial challenge.
				transmit(shared.pop3rsp_emptychallenge);
				break;
			}
			return handleEvent(PROTO_EVENT.E_SASLRSP, rcvdata, null);

		case E_SASL_CMD5:
			if (!isEnabled(POP3Protocol.AUTHTYPE.SASL_CRAM_MD5)) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			if (rcvdata.size() != 0) {
				//initial response not allowed for this mechanism
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			com.grey.base.sasl.CramMD5Server cmd5 = shared.saslmechs_cmd5.extract().init("POP3", getCMID(), shared.tmpsb);
			saslmech = cmd5;
			xmtbc = shared.tmpbc.clear().append(POP3Protocol.STATUS_AUTH);
			cmd5.setChallenge(xmtbc);
			xmtbc.append(POP3Protocol.EOL);
			transmit(xmtbc);
			break;

		case E_SASL_EXTERNAL:
			if (!isEnabled(POP3Protocol.AUTHTYPE.SASL_EXTERNAL)) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_AUTH, null);
			}
			saslmech = shared.saslmechs_ext.extract().init(getPeerCertificate());
			if (rcvdata.size() == 0) {
				//The Auth command didn't include an initial response, so send an empty initial challenge.
				transmit(shared.pop3rsp_emptychallenge);
				break;
			}
			if (rcvdata.size() == 1 && rcvdata.byteAt(0) == POP3Protocol.AUTH_EMPTY) {
				rcvdata.clear();
			}
			return handleEvent(PROTO_EVENT.E_SASLRSP, rcvdata, null);

		case E_SASLRSP:
			is_valid = saslmech.verifyResponse(rcvdata);
			if (is_valid) username.populate(saslmech.getUser());
			releaseSASL();
			processLogin(is_valid);
			break;

		case E_STAT:
			int totalsize = 0;
			for (int idx = 0; idx != sess.newMessageCount(); idx++) {
				if (!msgdeletes[idx]) totalsize += getSendSize(idx);
			}
			xmtbc = shared.tmpbc.clear();
			xmtbc.append(POP3Protocol.STATUS_OK).append(' ').append(sess.newMessageCount() - msgdelcnt, shared.tmpsb);
			xmtbc.append(' ').append(totalsize, shared.tmpsb);
			xmtbc.append(POP3Protocol.EOL);
			transmit(xmtbc);
			break;

		case E_LIST:
			xmtbc = shared.tmpbc.clear();
			if (rcvdata.size() == 0) {
				xmtbc.append(POP3Protocol.STATUS_OK).append(" messages=").append(sess.newMessageCount() - msgdelcnt, shared.tmpsb);
				xmtbc.append(POP3Protocol.EOL);
				for (int idx = 0; idx != sess.newMessageCount(); idx++) {
					if (msgdeletes[idx]) continue;
					int msgsiz = getSendSize(idx);
					xmtbc.append(idx+1, shared.tmpsb).append(' ').append(msgsiz, shared.tmpsb).append(POP3Protocol.EOL);
				}
				xmtbc.append(POP3Protocol.ENDRSP);
			} else {
				if ((curmsgnum = parseMessageNumber(rcvdata)) == -1) {
					transmit(shared.pop3rsp_nosuchmsg);
					break;
				}
				int msgsiz = getSendSize(curmsgnum-1);
				xmtbc.append(POP3Protocol.STATUS_OK).append(' ').append(curmsgnum, shared.tmpsb).append(' ').append(msgsiz, shared.tmpsb);
				xmtbc.append(POP3Protocol.EOL);
			}
			transmit(xmtbc);
			break;

		case E_DELE:
			// don't report bad msg-number as an error, as it may have been auto-deleted by an Expire=0 setting
			if ((curmsgnum = parseMessageNumber(rcvdata)) != -1) {
				msgdeletes[curmsgnum-1] = true;
				msgdelcnt++;
			}
			transmit(shared.pop3rsp_ok);
			break;

		case E_UIDL:
			xmtbc = shared.tmpbc.clear();
			if (rcvdata.size() == 0) {
				xmtbc.append(POP3Protocol.STATUS_OK).append(" messages=").append(sess.newMessageCount() - msgdelcnt, shared.tmpsb);
				xmtbc.append(POP3Protocol.EOL);
				for (int idx = 0; idx != sess.newMessageCount(); idx++) {
					if (msgdeletes[idx]) continue;
					String uidl = sess.getUIDL(idx);
					xmtbc.append(idx+1, shared.tmpsb).append(' ').append(uidl).append(POP3Protocol.EOL);
				}
				xmtbc.append(POP3Protocol.ENDRSP);
			} else {
				if ((curmsgnum = parseMessageNumber(rcvdata)) == -1) {
					transmit(shared.pop3rsp_nosuchmsg);
					break;
				}
				String uidl = sess.getUIDL(curmsgnum-1);
				xmtbc.append(POP3Protocol.STATUS_OK).append(' ').append(curmsgnum, shared.tmpsb).append(' ').append(uidl);
				xmtbc.append(POP3Protocol.EOL);
			}
			transmit(xmtbc);
			break;

		case E_RETR:
			if ((curmsgnum = parseMessageNumber(rcvdata)) == -1) {
				transmit(shared.pop3rsp_nosuchmsg);
				break;
			}
			xmtbc = shared.tmpbc.clear();
			xmtbc.append(POP3Protocol.STATUS_OK).append(" message follows").append(POP3Protocol.EOL);
			transmit(xmtbc);
			sess.sendMessage(curmsgnum-1, 0, getWriter());
			transmit(sess.lineModeSend() ? shared.pop3rsp_endrsp : shared.pop3rsp_endmsg);
			if (shared.expire == 0) {
				// RETR implies DELE
				msgdeletes[msgnum-1] = true;
				msgdelcnt++;
			}
			break;

		case E_TOP:
			int datalen = rcvdata.size();
			off = ByteOps.indexOf(rcvdata.buffer(), rcvdata.offset(), datalen, (byte)' ');
			if (off == -1) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, null, null);
			}
			rcvdata.setSize(off - rcvdata.offset());
			if ((curmsgnum = parseMessageNumber(rcvdata)) == -1) {
				transmit(shared.pop3rsp_nosuchmsg);
				break;
			}
			while (rcvdata.buffer()[off] == (byte)' ') off++;
			shared.tmplightbc.set(rcvdata.buffer(), off, rcvdata.offset() + datalen - off);
			int numlines = (int)shared.tmplightbc.parseDecimal();
			transmit(shared.pop3rsp_top);
			sess.sendMessage(curmsgnum-1, numlines, getWriter());
			transmit(shared.pop3rsp_endrsp);
			break;

		case E_QUIT:
			if (pstate == PROTO_STATE.S_TRANSACT) {
				transitionState(PROTO_STATE.S_AUTH);
				for (int idx = 0; idx != sess.newMessageCount(); idx++) {
					if (msgdeletes[idx]) {
						sess.deleteMessage(idx);
					}
				}
				LEVEL loglvl = (msgdelcnt == 0 ? LEVEL.TRC2 : LEVEL.INFO);
				if (getLogger().isActive(loglvl)) {
					StringBuilder sb = shared.tmpsb;
					sb.setLength(0);
					sb.append(pfx_log).append(": User=").append(username).append(" deleted ");
					sb.append(msgdelcnt).append('/').append(sess.newMessageCount()).append(" messages on logout");
					getLogger().log(loglvl, sb);
				}
			}
			if (sess != null) sess.endSession();
			sess = null;
			transmit(shared.pop3rsp_ok);
			issueDisconnect(null);
			break;

		case E_RSET:
			java.util.Arrays.fill(msgdeletes, false);
			msgdelcnt = 0;
			transmit(shared.pop3rsp_ok);
			break;

		case E_NOOP:
			transmit(shared.pop3rsp_ok);
			break;

		case E_CAPA:
			xmtbc = shared.tmpbc.clear();
			xmtbc.append(POP3Protocol.STATUS_OK).append(" Capability list follows:").append(POP3Protocol.EOL);
			if (pstate == PROTO_STATE.S_AUTH) {
				if (isEnabled(POP3Protocol.AUTHTYPE.USERPASS)) xmtbc.append(POP3Protocol.CMDREQ_USER).append(POP3Protocol.EOL);
				if (isEnabled(POP3Protocol.AUTHTYPE.APOP)) xmtbc.append(POP3Protocol.CMDREQ_APOP).append(POP3Protocol.EOL);
				String sasl = null;
				if (usingSSL()) {
					sasl = shared.capa_sasl_ssl;
				} else {
					if (getSSLConfig() != null) xmtbc.append(POP3Protocol.CMDREQ_STLS).append(POP3Protocol.EOL);
					sasl = shared.capa_sasl;
				}
				if (sasl != null) xmtbc.append(sasl).append(POP3Protocol.EOL);
			}
			xmtbc.append(POP3Protocol.CMDREQ_TOP).append(POP3Protocol.EOL);
			xmtbc.append(POP3Protocol.CMDREQ_UIDL).append(POP3Protocol.EOL);
			xmtbc.append(POP3Protocol.CAPA_TAG_PIPE).append(POP3Protocol.EOL);
			xmtbc.append(POP3Protocol.CAPA_TAG_EXPIRE).append(' ');
			if (shared.expire == -1) {
				xmtbc.append("NEVER");
			} else {
				xmtbc.append(shared.expire, shared.tmpsb);
			}
			xmtbc.append(POP3Protocol.EOL);
			xmtbc.append(POP3Protocol.CAPA_TAG_IMPL).append(' ').append(shared.appConfig.getProductName()).append(POP3Protocol.EOL);
			xmtbc.append(POP3Protocol.ENDRSP);
			transmit(xmtbc);
			break;

		case E_BADCMD:
			java.nio.ByteBuffer buf = (shared.altrsp_badcmd == null ? shared.pop3rsp_errproto : shared.altrsp_badcmd);
			transmit(buf);
			break;

		case E_LOCALERROR:
			issueDisconnect("Local Error - "+discmsg);
			break;

		default:
			// this is an internal bug whereby we're missing a case label
			getLogger().error(pfx_log+": Unrecognised event="+evt);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised event="+evt);
			break;
		}

		if (isState2(S2_REQWAIT)) {
			if (tmr_sesstmt == null) {
				// we're in a state that requires the timer, so if it doesn't exist, that's because it's not created yet - create now
				if (shared.tmtprotocol != 0) tmr_sesstmt = getDispatcher().setTimer(shared.tmtprotocol, TMRTYPE_SESSIONTMT, this);
			} else {
				if (tmr_sesstmt.age(getDispatcher()) > Task.MIN_RESET_PERIOD) tmr_sesstmt.reset();
			}
			getReader().receiveDelimited((byte)'\n');
		} else {
			if (tmr_sesstmt != null) {
				tmr_sesstmt.cancel();
				tmr_sesstmt = null;
			}
			getReader().endReceive();
		}
		return pstate;
	}

	private void processLogin(boolean is_valid) throws java.io.IOException
	{
		java.nio.ByteBuffer rejmsg = shared.pop3rsp_invauth;
		if (is_valid) {
			if (!shared.currentUsers.add(username)) {
				is_valid = false;
				rejmsg = shared.pop3rsp_dupauth;
			}
		}

		if (!is_valid) {
			username.clear();
			transitionState(PROTO_STATE.S_AUTH);
			transmit(rejmsg);
			return;
		}
		LEVEL loglvl = LEVEL.TRC;
		if (getLogger().isActive(loglvl)) {
			StringBuilder sb = shared.tmpsb;
			sb.setLength(0);
			sb.append(pfx_log).append(": User=").append(username).append(" logged in from ");
			sb.append("Remote=").append(remote_tsap.dotted_ip).append(':').append(remote_tsap.port);
			getLogger().log(loglvl, sb);
		}
		transmit(shared.pop3rsp_ok);
		transitionState(PROTO_STATE.S_TRANSACT);
		sess = shared.ms.startInboxSession(username);
		if (sess.newMessageCount() > msgdeletes.length) msgdeletes = new boolean[sess.newMessageCount()];
		java.util.Arrays.fill(msgdeletes, false);
		msgdelcnt = 0;
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

	// this must only be called from handleEvent() so that the REQWAIT flag can be handled
	private void transmit(java.nio.ByteBuffer xmtbuf) throws java.io.IOException
	{
		if (shared.transcript != null) shared.transcript.data_out(pfx_transcript, xmtbuf, 0, getSystemTime());
		xmtbuf.position(0);
		getWriter().transmit(xmtbuf);
		setState2(S2_REQWAIT);
		shared.altrsp_badcmd = null;
	}

	private void transmit(com.grey.base.utils.ByteChars data) throws java.io.IOException
	{
		shared.tmpniobuf = shared.bufspec.encode(data, shared.tmpniobuf);
		transmit(shared.tmpniobuf);
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d)
			throws java.io.IOException
	{
		switch (tmr.getType())
		{
		case TMRTYPE_SESSIONTMT:
			tmr_sesstmt = null;		// this timer is now expired, so we must not access it again
			issueDisconnect("Timeout");
			break;

		case TMRTYPE_DISCON:
			tmr_exit = null;
			disconnect();
			break;

		default:
			getLogger().error(pfx_log+": Unexpected timer-type="+tmr.getType());
			break;
		}
	}

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex)
	{
		raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "State="+pstate+" - "+com.grey.base.ExceptionUtils.summary(ex));
	}

	private boolean matchesCommand(ByteArrayRef data, com.grey.base.utils.ByteChars cmd)
	{
		final int cmdlen = cmd.size();
		if (data.size() < cmdlen) return false;
		final byte[] cmdbuf = cmd.buffer();
		final byte[] databuf = data.buffer();
		int off_cmd = cmd.offset();
		int off_data = data.offset();

		for (int idx = 0; idx != cmdlen; idx++) {
			if (Character.toUpperCase(databuf[off_data++]) != cmdbuf[off_cmd+idx]) return false;
		}
		if (databuf[off_data] != ' ' && databuf[off_data] != '\r' && databuf[off_data] != '\n') return false; //false match - at the start of longer string

		// strip trailing white space
		while (data.size() != 0 && databuf[data.limit() - 1] <= ' ') data.incrementSize(-1);
		// Advance to start of arg, if any. RFC-1939 mandates one space before arg, but be more tolerant
		while (databuf[off_data] == ' ') off_data++;
		data.advance(off_data - data.offset());
		return true;
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

	private int parseMessageNumber(ByteArrayRef rcvdata)
	{
		int msgnum = -1;
		if (rcvdata.size() != 0) {
			shared.tmplightbc.set(rcvdata);
			try {
				msgnum = (int)shared.tmplightbc.parseDecimal();
			} catch (NumberFormatException ex) {} //leave msgnum as -1
		}
		if (msgnum < 1 || msgnum > sess.newMessageCount() || msgdeletes[msgnum-1]) {
			msgnum = -1;
		}
		return msgnum;
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

	private boolean isEnabled(POP3Protocol.AUTHTYPE auth)
	{
		if (!shared.authtypes_enabled.contains(auth)) {
			return false;
		}
		if (!usingSSL() && shared.authtypes_ssl.contains(auth)) {
			shared.altrsp_badcmd = shared.pop3rsp_needssl;
			return false;
		}
		return true;
	}

	// If not in line-mode, we send an extra EOL after a message to make sure the subsequent ENDRSP is properly framed,
	// in case message doesn't end in EOL
	private int getSendSize(int msgnum)
	{
		int len = sess.getMessageSize(msgnum);
		if (!sess.lineModeSend()) len += POP3Protocol.EOL.length();
		return len;
	}

	static java.util.HashSet<POP3Protocol.AUTHTYPE> configureAuthTypes(String cfgitem, com.grey.base.config.XmlConfig cfg, boolean mdty,
			POP3Protocol.AUTHTYPE[] dflt, java.util.HashSet<POP3Protocol.AUTHTYPE> full, String logpfx)
	{
		java.util.HashSet<POP3Protocol.AUTHTYPE> authtypes = new java.util.HashSet<POP3Protocol.AUTHTYPE>();
		String dlm = "|";
		String txt = "";
		if (dflt != null) {
			for (int idx = 0; idx != dflt.length; idx++) {
				if (dflt[idx] != null) txt += (idx==0?"":dlm) + dflt[idx];
			}
		}
		String[] authtypes_cfg = cfg.getTuple(cfgitem, dlm, mdty, txt);
		if (authtypes_cfg == null) authtypes_cfg = new String[0]; //avoids aggravation below

		if (authtypes_cfg.length == 1 && authtypes_cfg[0].equalsIgnoreCase("all")) {
			if (full == null) full = new java.util.HashSet<POP3Protocol.AUTHTYPE>(java.util.Arrays.asList(dflt));
			return full;
		}

		for (int idx = 0; idx != authtypes_cfg.length; idx++) {
			authtypes_cfg[idx] = authtypes_cfg[idx].toUpperCase();
			POP3Protocol.AUTHTYPE authtype = null;
			try {
				authtype = POP3Protocol.AUTHTYPE.valueOf(authtypes_cfg[idx]);
			} catch (java.lang.IllegalArgumentException ex) {
				throw new MailismusConfigException(logpfx+"Unsupported Authentication type="+authtypes_cfg[idx]
						+" - Supported="+java.util.Arrays.asList(POP3Protocol.AUTHTYPE.values()));
			}
			// authtypes_ssl must be a subset of authtypes
			if (full == null || full.contains(authtype)) authtypes.add(authtype);
		}
		return authtypes;
	}

	static String setCapaSASL(java.util.HashSet<POP3Protocol.AUTHTYPE> enabled, java.util.HashSet<POP3Protocol.AUTHTYPE> excl)
	{
		String saslprefix = "SASL_";
		String capa_txt = null;
		java.util.Iterator<POP3Protocol.AUTHTYPE> it = enabled.iterator();
		while (it.hasNext()) {
			POP3Protocol.AUTHTYPE authtype = it.next();
			if (excl != null && excl.contains(authtype)) continue;
			String txt = authtype.toString();
			String mech = (txt.startsWith(saslprefix) ? txt.substring(saslprefix.length()).replace('_', '-') : null);
			if (mech != null) capa_txt = (capa_txt == null ? "SASL" : capa_txt)+" "+mech;
		}
		return capa_txt;
	}

	@Override
	public StringBuilder dumpAppState(StringBuilder sb)
	{
		if (sb == null) sb = new StringBuilder();
		sb.append(pfx_log).append('/').append(pstate).append("/0x").append(Integer.toHexString(state2));
		if (username.length() != 0) sb.append("/auth=").append(username);
		if (sess != null) sb.append(": msgcnt").append(sess.newMessageCount());
		return sb;
	}

	@Override
	public String toString() {
		return getClass().getName()+"=E"+getCMID();
	}
}

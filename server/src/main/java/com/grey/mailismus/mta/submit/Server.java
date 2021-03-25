/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit;

import com.grey.mailismus.AppConfig;
import com.grey.mailismus.Task;
import com.grey.mailismus.mta.MTA_Task;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.deliver.Routing;
import com.grey.mailismus.mta.submit.filter.FilterManager;
import com.grey.mailismus.mta.submit.filter.FilterExecutor;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.reactor.CM_Listener;
import com.grey.naf.reactor.Dispatcher;
import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.TimeOps;
import com.grey.logging.Logger.LEVEL;

public final class Server
	extends com.grey.naf.reactor.CM_Server
	implements com.grey.naf.reactor.TimerNAF.Handler,
		com.grey.naf.dns.resolver.ResolverDNS.Client,
		com.grey.naf.nafman.NafManCommand.Handler
{
	private static final boolean REQUIRE_CRLF = SysProps.get("grey.mta.smtpserver.needcrlf", true);
	private static final boolean TRANSCRIPTBODY = SysProps.get("grey.mta.smtpserver.transcriptbody", false);

	private enum PROTO_STATE {S_DISCON, S_HELO, S_IDLE, S_MAILRECIPS, S_MAILBODY, S_FILTER, S_STLS, S_SASL}
	private enum PROTO_EVENT {E_CONNECTED, E_DISCONNECT, E_DISCONNECTED, E_STLS,
								E_GREET, E_HELO, E_EHLO, E_MAILFROM, E_MAILTO, E_QUIT,
								E_BODYSTART, E_BODYDATA, E_ACCEPTMSG, E_REJECTMSG, E_FILTERMSG,
								E_SASL_PLAIN, E_SASL_CMD5, E_SASL_EXTERNAL, E_SASLRSP,
								E_NOOP, E_RESET, E_NULLRECIPS, E_BADCMD, E_LOCALERROR}

	// see RFC 821 section 4.2.2 for recommended reply codes (and RFC-4954 section 6 for SMTP-Auth)
	private static final String TOKEN_HOSTNAME = "%H%";
	private static final String DFLTRSP_GREET = TOKEN_HOSTNAME+" ("+AppConfig.TOKEN_PRODNAME+") ESMTP Ready";
	private static final String DFLTRSP_QUIT = "221 Closing connection" + Protocol.EOL;
	private static final String DFLTRSP_AUTHOK = "235 Authentication OK" + Protocol.EOL;
	private static final String DFLTRSP_OK = "250 OK" + Protocol.EOL;
	private static final String DFLTRSP_EHLO = "250-"+TOKEN_HOSTNAME+" Hello" + Protocol.EOL; //will append ESMTP extensions to this
	private static final com.grey.base.utils.ByteChars DFLTRSP_BODY = new com.grey.base.utils.ByteChars("250 Message accepted as ");
	private static final String DFLTRSP_DATA = "354 Start mail input; end with <CRLF>.<CRLF>" + Protocol.EOL;
	private static final String DFLTRSP_SHUTDOWN = "421 Shutting down" + Protocol.EOL;
	private static final String DFLTRSP_BUSY = "421 Busy" + Protocol.EOL;
	private static final String DFLTRSP_GREYLISTED = Protocol.REPLYCODE_GREYLIST+" Please try again later" + Protocol.EOL;
	private static final String DFLTRSP_ERRLOCAL = "451 Aborted: local processing or I/O error" + Protocol.EOL;
	private static final String DFLTRSP_EXCESSRECIPS = "452 Too many recipients" + Protocol.EOL;
	private static final String DFLTRSP_EXCESSMSGS = "452 Too many messages for session - connect again" + Protocol.EOL;
	private static final String DFLTRSP_BADHELLO = "501 Please say Hello properly" + Protocol.EOL;
	private static final String DFLTRSP_NULLRECIPS = "503 No valid recipients specified" + Protocol.EOL;
	private static final String DFLTRSP_ERRPROTO = "503 Invalid command" + Protocol.EOL;
	private static final String DFLTRSP_NEEDSSL = "503 Requires SSL mode" + Protocol.EOL;
	private static final String DFLTRSP_PREMATURE = "503 Bit premature" + Protocol.EOL;
	private static final String DFLTRSP_FORGED = "503 Address appears to be forged" + Protocol.EOL;
	private static final String DFLTRSP_NUISANCE = "503 You're just being a nuisance" + Protocol.EOL;
	private static final String DFLTRSP_NEEDAUTH = "530 You must authenticate first" + Protocol.EOL;
	private static final String DFLTRSP_INVAUTH = "535 Authentication failed" + Protocol.EOL;
	private static final String DFLTRSP_BADSENDER = "550 Invalid Sender address" + Protocol.EOL;
	private static final String DFLTRSP_BADRECIP = "550 Invalid Recipient address" + Protocol.EOL;
	private static final String DFLTRSP_RELAYDENIED = "550 Relaying denied" + Protocol.EOL;
	private static final String DFLTRSP_BLACKLISTED = Protocol.REPLYCODE_BLACKLIST+" Service refused - your IP is on a blacklist" + Protocol.EOL;
	private static final String DFLTRSP_MSGSIZE = "552 Message too large" + Protocol.EOL;

	// pre-built elements of the Received header line
	private static final com.grey.base.utils.ByteChars RCVHDR_PFX = new com.grey.base.utils.ByteChars("Received: from [");
	private static final com.grey.base.utils.ByteChars RCVHDR_HELO = new com.grey.base.utils.ByteChars("] (helo=");
	private static final com.grey.base.utils.ByteChars RCVHDR_BY = new com.grey.base.utils.ByteChars(")" + Protocol.EOL + "\tby ");
	private static final com.grey.base.utils.ByteChars RCVHDR_PREPKGNAM = new com.grey.base.utils.ByteChars(" (");
	private static final com.grey.base.utils.ByteChars RCVHDR_WITH1 = new com.grey.base.utils.ByteChars(") with ");
	private static final com.grey.base.utils.ByteChars RCVHDR_WITH2 = new com.grey.base.utils.ByteChars(" id ");
	private static final com.grey.base.utils.ByteChars RCVHDR_WITH3 = new com.grey.base.utils.ByteChars(";" + Protocol.EOL + "\t");
	private static final com.grey.base.utils.ByteChars RCVHDR_FOR1 = new com.grey.base.utils.ByteChars("for <");
	private static final com.grey.base.utils.ByteChars RCVHDR_FOR2 = new com.grey.base.utils.ByteChars(">; ");

	private static final byte[] EOMSEQ_CRLF = {'\r', '\n', '.', '\r', '\n'};
	private static final byte[] EOMSEQ_LF = {'\n', '.', '\n'};

	private static final char[] SMTPREQ_HELO = Protocol.CMDREQ_HELO.toCharArray();
	private static final char[] SMTPREQ_EHLO = Protocol.CMDREQ_EHLO.toCharArray();
	private static final char[] SMTPREQ_MAILFROM = Protocol.CMDREQ_MAILFROM.toCharArray();
	private static final char[] SMTPREQ_MAILTO = Protocol.CMDREQ_MAILTO.toCharArray();
	private static final char[] SMTPREQ_DATA = Protocol.CMDREQ_DATA.toCharArray();
	private static final char[] SMTPREQ_QUIT = Protocol.CMDREQ_QUIT.toCharArray();
	private static final char[] SMTPREQ_NOOP = Protocol.CMDREQ_NOOP.toCharArray();
	private static final char[] SMTPREQ_RESET = Protocol.CMDREQ_RESET.toCharArray();
	private static final char[] SMTPREQ_STLS = Protocol.CMDREQ_STLS.toCharArray();
	private static final char[] SMTPREQ_SASL_PLAIN = Protocol.CMDREQ_SASL_PLAIN.toCharArray();
	private static final char[] SMTPREQ_SASL_CMD5 = Protocol.CMDREQ_SASL_CMD5.toCharArray();
	private static final char[] SMTPREQ_SASL_EXTERNAL = Protocol.CMDREQ_SASL_EXTERNAL.toCharArray();

	private static final class FSM_Trigger
	{
		final PROTO_STATE state;
		final PROTO_EVENT evt;
		final char[] cmd;
		final PROTO_STATE new_state;

		FSM_Trigger(PROTO_STATE state_p, PROTO_EVENT evt_p, char[] cmd_p, PROTO_STATE newstate_p)
		{
			state = state_p;
			evt = evt_p;
			cmd = cmd_p;
			new_state = newstate_p;
		}
	}

	private static final FSM_Trigger[] fsmTriggers = {
		new FSM_Trigger(PROTO_STATE.S_MAILBODY, PROTO_EVENT.E_BODYDATA, null, null), //put this first as most common
		new FSM_Trigger(PROTO_STATE.S_MAILRECIPS, PROTO_EVENT.E_MAILTO, SMTPREQ_MAILTO, null), //this is the next most common event
		new FSM_Trigger(PROTO_STATE.S_IDLE, PROTO_EVENT.E_MAILFROM, SMTPREQ_MAILFROM, PROTO_STATE.S_MAILRECIPS),
		new FSM_Trigger(PROTO_STATE.S_MAILRECIPS, PROTO_EVENT.E_BODYSTART, SMTPREQ_DATA, PROTO_STATE.S_MAILBODY),
		new FSM_Trigger(PROTO_STATE.S_HELO, PROTO_EVENT.E_HELO, SMTPREQ_HELO, PROTO_STATE.S_IDLE),
		new FSM_Trigger(PROTO_STATE.S_HELO, PROTO_EVENT.E_EHLO, SMTPREQ_EHLO, PROTO_STATE.S_IDLE),
		new FSM_Trigger(null, PROTO_EVENT.E_QUIT, SMTPREQ_QUIT, null),
		new FSM_Trigger(PROTO_STATE.S_IDLE, PROTO_EVENT.E_SASL_PLAIN, SMTPREQ_SASL_PLAIN, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_IDLE, PROTO_EVENT.E_SASL_CMD5, SMTPREQ_SASL_CMD5, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_IDLE, PROTO_EVENT.E_SASL_EXTERNAL, SMTPREQ_SASL_EXTERNAL, PROTO_STATE.S_SASL),
		new FSM_Trigger(PROTO_STATE.S_IDLE, PROTO_EVENT.E_STLS, SMTPREQ_STLS, PROTO_STATE.S_STLS),
		new FSM_Trigger(null, PROTO_EVENT.E_RESET, SMTPREQ_RESET, PROTO_STATE.S_IDLE),
		new FSM_Trigger(null, PROTO_EVENT.E_NOOP, SMTPREQ_NOOP, null)
	};

	private static final int TMRTYPE_SESSIONTMT = 1;
	private static final int TMRTYPE_DISCON = 2;
	private static final int TMRTYPE_ACTION = 3;
	private static final int TMRTYPE_GREET = 4;
	private static final int TMRTYPE_FILTER = 5;

	private static final byte S2_ENDED = 1 << 0; //we have already called endConnection()
	private static final byte S2_DNSWAIT = 1 << 1; //secondary state flag within various states - means we're waiting for a DNS answer
	private static final byte S2_DATAWAIT = 1 << 2; //we're waiting for the remote client to send a request
	private static final byte S2_ABORT = 1 << 3;
	private static final byte S2_RECIPSTRANSFORM = 1 << 4; //original MAILTO recips have been modified
	private static final byte S2_RECIPSDISCARD = 1 << 5; //at least one recip was discarded rather than rejected

	//status values returnd by some address-validation methods
	private enum ADDR_STATUS {OK, REJECT, PENDING}

	// This class maps the new Listener.Server design to the original prototype scheme on which
	// this server is still based.
	public static final class Factory
		implements com.grey.naf.reactor.CM_Listener.ServerFactory
	{
		private final Server prototype;

		public Factory(CM_Listener l, Object cfg) throws java.io.IOException {
			com.grey.base.config.XmlConfig xmlcfg = (com.grey.base.config.XmlConfig)cfg;
			prototype = new Server(l, xmlcfg);
		}

		@Override
		public Server createServer() {return new Server(prototype);}
		@Override
		public void shutdownServerFactory() {prototype.abortServer();}
	}

	// config to apply on a per-connection basis, depending who we're talking to
	private static final class ConnectionConfig
	{
		final com.grey.base.utils.IP.Subnet[] ipnets;
		final ValidationSettings validHELO;
		final ValidationSettings validSender;
		final ValidationSettings validRecip;
		final String announcehost; //hostname to announce in greeting
		final int max_ipconns; //max simultaneous connections allowed from any one IP address
		final int max_msgrecips; //max recips per message
		final int max_connmsgs;	//max messages accepted per SMTP connection
		final int max_msgsize;
		final int maxbadcmd;
		final long tmtprotocol;
		final long delay_greet; //anti-spam measure - catches out "slammers"
		final long delay_badrecip; //anti-spam measure - slow down senders with poorly validated recipient addresses
		final long delay_badcmd; //slow down rogue clients
		final long delay_chanclose; //has solved abort-on-close issues in the past
		final boolean omitrcvhdr;

		final java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> authtypes_enabled;
		final java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> authtypes_ssl; //only allowed in SSL mode
		final boolean auth_mdty;
		final boolean auth_compat; //advertise Auth types with Protocol.EXT_AUTH_COMPAT as well

		// ESMTP settings
		final boolean ext_8bitmime;
		final boolean ext_pipeline;
		final boolean ext_size;
		final boolean ext_stls;

		final com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars> sender_deny;
		final com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars> sender_permit;

		// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned replies
		final java.nio.ByteBuffer smtprsp_greet;
		final java.nio.ByteBuffer smtprsp_ehlo;
		final java.nio.ByteBuffer smtprsp_ehlo_ssl; //EHLO response to use if already in SSL mode

		public ConnectionConfig(int id, XmlConfig cfg, SharedFields common, ConnectionConfig dfltcfg,
				com.grey.naf.reactor.config.SSLConfig sslcfg, com.grey.logging.Logger logger, String logpfx) throws java.net.UnknownHostException
		{
			if (id == 0) {
				ipnets = null;
			} else {
				ipnets = parseSubnets("@ip", cfg, common.appConfig);
				if (ipnets == null) throw new MailismusConfigException(logpfx+" remotenet config has missing 'ip' attribute");
			}
			announcehost = common.appConfig.getAnnounceHost(cfg, dfltcfg==null ? common.appConfig.getAnnounceHost() : dfltcfg.announcehost);
			validHELO = new ValidationSettings(cfg, "validate_helo", dfltcfg==null? null : dfltcfg.validHELO, false);
			validSender = new ValidationSettings(cfg, "validate_sender", dfltcfg==null? null : dfltcfg.validSender, true);
			validRecip = new ValidationSettings(cfg, "validate_recip", dfltcfg==null? null : dfltcfg.validRecip, true);
			omitrcvhdr = cfg.getBool("omitreceivedheader", dfltcfg==null ? false : dfltcfg.omitrcvhdr);

			max_ipconns = cfg.getInt("maxpeerconnections", false, dfltcfg==null ? 0 : dfltcfg.max_ipconns);
			max_msgrecips = cfg.getInt("maxrecips", false, dfltcfg==null ? 0 : dfltcfg.max_msgrecips);
			max_connmsgs = cfg.getInt("maxmessages", false, dfltcfg==null ? 0 : dfltcfg.max_connmsgs);
			max_msgsize = (int)cfg.getSize("maxmsgsize", dfltcfg==null ? 0 : dfltcfg.max_msgsize);
			maxbadcmd = cfg.getInt("maxbadreqs", false, dfltcfg==null ? 2 : dfltcfg.maxbadcmd);

			tmtprotocol = cfg.getTime("timeout", dfltcfg==null ? TimeOps.parseMilliTime("2m") : dfltcfg.tmtprotocol);
			delay_greet = cfg.getTime("delay_greet", dfltcfg==null ? 0 : dfltcfg.delay_greet);
			delay_badrecip = cfg.getTime("delay_badrecip", dfltcfg==null ? 0 : dfltcfg.delay_badrecip);
			delay_badcmd = cfg.getTime("delay_badreq", dfltcfg==null ? 0 : dfltcfg.delay_badcmd);
			delay_chanclose = cfg.getTime("delay_close", dfltcfg==null ? 0 : dfltcfg.delay_chanclose);

			ext_8bitmime = cfg.getBool("ext8BITMIME", dfltcfg==null ? false : dfltcfg.ext_8bitmime);
			ext_pipeline = cfg.getBool("extPIPELINING", dfltcfg==null ? true : dfltcfg.ext_pipeline);
			ext_size = cfg.getBool("extSIZE", dfltcfg==null ? true : dfltcfg.ext_size);
			ext_stls = cfg.getBool("extSTARTTLS", dfltcfg==null ? sslcfg != null && sslcfg.isLatent() : dfltcfg.ext_stls);

			if (common.dtory == null) {
				authtypes_enabled = new java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH>();
			} else {
				authtypes_enabled = configureAuthTypes("authtypes", cfg, dfltcfg == null?null:dfltcfg.authtypes_enabled, null, logpfx);
				if (!common.dtory.supportsPasswordLookup()) authtypes_enabled.remove(com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5);
			}
			if (sslcfg == null || (sslcfg.isLatent() && !sslcfg.isMandatory())) {
				java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> sslonly =
								(dfltcfg == null || dfltcfg.authtypes_enabled.size() == 0 ? null : dfltcfg.authtypes_ssl);
				if (sslonly == null) {
					sslonly = new java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH>();
					sslonly.add(com.grey.base.sasl.SaslEntity.MECH.EXTERNAL);
					if (sslcfg != null) sslonly.add(com.grey.base.sasl.SaslEntity.MECH.PLAIN);
				}
				authtypes_ssl = configureAuthTypes("authtypes_ssl", cfg, sslonly, authtypes_enabled, logpfx);
			} else {
				authtypes_ssl = authtypes_enabled;
			}
			auth_mdty = cfg.getBool("authtypes/@mandatory", dfltcfg==null ? false : dfltcfg.auth_mdty);
			auth_compat = cfg.getBool("authtypes/@compat", dfltcfg==null ? false : dfltcfg.auth_mdty);

			sender_deny = buildSet(cfg, dfltcfg==null ? null : dfltcfg.sender_deny, "sender_deny");
			sender_permit = buildSet(cfg, dfltcfg==null ? null : dfltcfg.sender_permit, "sender_permit");

			String greetmsg = cfg.getValue("smtpgreet", false, null);
			if (greetmsg == null && dfltcfg != null) {
				smtprsp_greet = dfltcfg.smtprsp_greet;
			} else {
				if (greetmsg == null) greetmsg = DFLTRSP_GREET;
				greetmsg = "220 " + greetmsg + Protocol.EOL;
				greetmsg = greetmsg.replace(TOKEN_HOSTNAME, announcehost);
				greetmsg = greetmsg.replace(AppConfig.TOKEN_PRODNAME, common.appConfig.getProductName());
				smtprsp_greet = com.grey.mailismus.Task.constBuffer(greetmsg);
			}
			String ehlomsg = buildResponseEHLO(this, false, common);
			smtprsp_ehlo = com.grey.mailismus.Task.constBuffer(ehlomsg);
			ehlomsg = buildResponseEHLO(this, true, common);
			smtprsp_ehlo_ssl = com.grey.mailismus.Task.constBuffer(ehlomsg);

			if (tmtprotocol == 0) throw new MailismusConfigException(logpfx+"Idle timeout cannot be zero");

			if (ipnets == null) {
				logger.info(logpfx+"Default config:");
			} else {
				String txt = "Subnets="+ipnets.length;
				String dlm = ": ";
				for (int idx = 0; idx != ipnets.length; idx++) {
					txt += dlm+com.grey.base.utils.IP.displayDottedIP(ipnets[idx].ip, null)+"/"+ipnets[idx].netprefix;
					dlm = ", ";
				}
				logger.info(logpfx+"remotenet config - "+txt);
			}
			String pfx = "- ";
			String txt = (authtypes_ssl.size() == 0 ? "none" : authtypes_ssl.size()+"/"+authtypes_ssl);
			if (txt != null && authtypes_ssl.size() == authtypes_enabled.size()) txt = "all";
			logger.info(pfx+"authtypes="+authtypes_enabled.size()+"/"+authtypes_enabled
					+"/mandatory="+auth_mdty+"/compat="+auth_compat
					+(authtypes_enabled.size() == 0 ? "" : "; SSL-only="+txt));
			logger.info(pfx+"announce="+announcehost);
			logger.info(pfx+"maxpeerconns="+max_ipconns+"; maxmsgsize="+max_msgsize
					+"; maxrecips="+max_msgrecips+"; maxmessages="+max_connmsgs);
			logger.info(pfx+validHELO.toString("validateHELO", dfltcfg == null ? null : dfltcfg.validHELO));
			logger.info(pfx+validSender.toString("validateSender", dfltcfg == null ? null : dfltcfg.validSender));
			logger.info(pfx+validRecip.toString("validateRecip", dfltcfg == null ? null : dfltcfg.validRecip));
			logger.info(pfx + "sender-deny="+(dfltcfg != null && sender_deny == dfltcfg.sender_deny ? "common"
									: Integer.valueOf(sender_deny==null?0:sender_deny.size()))
					+"; sender-permit="+(dfltcfg != null && sender_permit == dfltcfg.sender_permit ? "common"
									: Integer.valueOf(sender_permit==null?0:sender_permit.size())));
			logger.info(pfx+"timeout="+TimeOps.expandMilliTime(tmtprotocol)
					+"; delay-badrecip="+TimeOps.expandMilliTime(delay_badrecip)
					+"; delay-badcmd="+TimeOps.expandMilliTime(delay_badcmd)
					+"; delay-greet="+TimeOps.expandMilliTime(delay_greet));
			logger.info(pfx+"omit-rcvhdr="+omitrcvhdr+"; maxbadreqs="+maxbadcmd);
			logger.trace(pfx+"delay-chanclose="+delay_chanclose);
		}
	}

	// Define fields which can be shared by all instances of this class that were created from the same prototype,
	// secure in the knowledge that they are all running in the same thread, and can therefore share any objects
	// whose value or state does not persist across invocations of this object.
	private static final class SharedFields
	{
		final ConnectionConfig defaultcfg;
		final ConnectionConfig[] remotecfg;
		final com.grey.naf.BufferGenerator netbufs;
		final Server prototype_server;
		final String srcrouted_bounces_recip;
		final boolean localdelivery;
		final boolean spf_sender_rewrite;

		// assorted shareable objects
		final AppConfig appConfig;
		final com.grey.mailismus.mta.queue.QueueManager qmgr;
		final com.grey.mailismus.directory.Directory dtory;
		final SaslAuthenticator saslauth;
		final com.grey.base.utils.IP.Subnet[] relay_clients;
		final Routing routing;
		final com.grey.mailismus.IPlist blacklst;
		final Greylist greylst;
		final com.grey.base.collections.HashedMapIntInt ipconns = new com.grey.base.collections.HashedMapIntInt(0); //maps remote IP to number of current connections from it
		final com.grey.mailismus.Transcript transcript;

		final com.grey.base.collections.ObjectWell<com.grey.base.sasl.PlainServer> saslmechs_plain;
		final com.grey.base.collections.ObjectWell<com.grey.base.sasl.CramMD5Server> saslmechs_cmd5;
		final com.grey.base.collections.ObjectWell<com.grey.base.sasl.ExternalServer> saslmechs_ext;

		final FilterManager filter_manager;
		final long tmtfilter;

		// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned replies
		final java.nio.ByteBuffer smtprsp_ok;
		final java.nio.ByteBuffer smtprsp_data;
		final java.nio.ByteBuffer smtprsp_quit;
		final java.nio.ByteBuffer smtprsp_shutdown;
		final java.nio.ByteBuffer smtprsp_badhello;
		final java.nio.ByteBuffer smtprsp_badsender;
		final java.nio.ByteBuffer smtprsp_badrecip;
		final java.nio.ByteBuffer smtprsp_relaydenied;
		final java.nio.ByteBuffer smtprsp_blacklisted;
		final java.nio.ByteBuffer smtprsp_greylisted;
		final java.nio.ByteBuffer smtprsp_excessrecips;
		final java.nio.ByteBuffer smtprsp_excessmsgs;
		final java.nio.ByteBuffer smtprsp_msgsize;
		final java.nio.ByteBuffer smtprsp_busy;
		final java.nio.ByteBuffer smtprsp_nullrecips;
		final java.nio.ByteBuffer smtprsp_errproto;
		final java.nio.ByteBuffer smtprsp_premature;
		final java.nio.ByteBuffer smtprsp_forged;
		final java.nio.ByteBuffer smtprsp_errlocal;
		final java.nio.ByteBuffer smtprsp_nuisance;
		final java.nio.ByteBuffer smtprsp_needssl;
		final java.nio.ByteBuffer smtprsp_needauth;
		final java.nio.ByteBuffer smtprsp_authok;
		final java.nio.ByteBuffer smtprsp_invauth;
		final java.nio.ByteBuffer smtprsp_emptychallenge;

		boolean stopped; //applies to prototype object
		int current_conncnt; //total number of current incoming connections
		int peak_conncnt; //max value reached by current_conncnt

		// these are the stats counters that are retrieved and reset by the NAFMAN COUNTERS command
		long stats_start;
		int stats_conncnt;	//number of connections accepted
		int stats_rejconns;	//number of connections explicitly rejected
		int stats_msgcnt;	//number of messages successfully submitted onto queue
		int stats_recipcnt;	//number of recipients associated with those msgcnt messages
		int stats_rejrecips;//number of recipients explicitly rejected
		int stats_peakconcurrency; //max value reached by current_conncnt in current stats interval

		private final StringBuilder discardmsgidbuf = new StringBuilder();
		private long discard_msgid;

		// temp work areas, pre-allocated for efficiency
		final com.grey.base.collections.ObjectWell<com.grey.base.utils.EmailAddress> addrbufcache;
		final com.grey.base.collections.ObjectWell<com.grey.base.utils.ByteChars> bcwell;
		final com.grey.base.utils.TSAP tmptsap = new com.grey.base.utils.TSAP();
		final java.util.Calendar dtcal = TimeOps.getCalendar(null);
		final com.grey.base.utils.ByteChars tmpbcbuf = new com.grey.base.utils.ByteChars();
		final StringBuilder tmpsb = new StringBuilder();
		java.nio.ByteBuffer tmpniobuf;

		public SharedFields(XmlConfig cfg, Dispatcher dsptch, MTA_Task task,
				Server proto, CM_Listener lstnr, com.grey.naf.reactor.config.SSLConfig sslcfg, String logpfx)
			throws java.io.IOException
		{
			prototype_server = proto;
			appConfig = task.getAppConfig();
			qmgr = task.getQueue();
			dtory = task.getDirectory();
			saslauth = (dtory == null ? null : new SaslAuthenticator(dtory));
			transcript = com.grey.mailismus.Transcript.create(dsptch, cfg, "transcript");
			srcrouted_bounces_recip = cfg.getValue("srcrouted_bounces_recip", false, "postmaster");
			localdelivery = cfg.getBool("localdelivery", true);
			spf_sender_rewrite = cfg.getBool("spf_sender_rewrite", true);
			stats_start = dsptch.getRealTime();
			discard_msgid = stats_start;
			netbufs = new com.grey.naf.BufferGenerator(cfg, "niobuffers", 4*1024, 128);
			if (netbufs.rcvbufsiz < 80) throw new MailismusConfigException(logpfx+"recvbuf="+netbufs.rcvbufsiz+" is too small");

			// read the per-connection config
			defaultcfg = new ConnectionConfig(0, cfg, this, null, sslcfg, dsptch.getLogger(), logpfx);
			XmlConfig[] cfgnodes = cfg.getSections("remotenets/remotenet");
			if (cfgnodes == null) {
				remotecfg = null;
			} else {
				remotecfg = new ConnectionConfig[cfgnodes.length];
				for (int idx = 0; idx != cfgnodes.length; idx++) {
					remotecfg[idx] = new ConnectionConfig(idx+1, cfgnodes[idx], this, defaultcfg, sslcfg, dsptch.getLogger(), logpfx);
				}
			}

			// determine the clients who are permitted to relay email through us, even without authenticating
			relay_clients = parseSubnets("relay_clients", cfg, appConfig);

			// Record the domains we relay mail for. This class doesn't care about their relay's address, as it merely treats
			// the existence of a relay as proof that a domain is known a priori to be legit, and shouldn't be subjected to
			// DNS-Validation or relay-restriction checks.
			// Doesn't matter if a relay domain duplicates a local one, as recipient-validation tests if local domain first.
			routing = new Routing(appConfig.getConfigRelays(), dsptch.getApplicationContext().getConfig(), null);

			// Set up blacklisting, if configured
			String xpath = "blacklist"+XmlConfig.XPATH_ENABLED;
			XmlConfig cfg_black = cfg.getSection(xpath);
			String blackrsp = DFLTRSP_BLACKLISTED;
			if (cfg_black.exists()) {
				blacklst = new com.grey.mailismus.IPlist("smtpsrv_black", appConfig.getDatabaseType(), cfg_black, dsptch);
				String rsp = cfg_black.getValue("smtpreply", false, null);
				if (rsp != null) blackrsp = Protocol.REPLYCODE_BLACKLIST+" "+rsp+Protocol.EOL;
			} else {
				blacklst = null;
			}

			// Set up greylisting, if configured
			xpath = "greylist"+XmlConfig.XPATH_ENABLED;
			XmlConfig cfg_grey = cfg.getSection(xpath);
			String greyrsp = DFLTRSP_GREYLISTED;
			if (cfg_grey.exists()) {
				greylst = new Greylist(dsptch, appConfig.getDatabaseType(), cfg_grey);
				String rsp = cfg_grey.getValue("smtpreply", false, null);
				if (rsp != null) greyrsp = Protocol.REPLYCODE_GREYLIST+" "+rsp+Protocol.EOL;
			} else {
				greylst = null;
			}

			// Set up message filtering, if configured
			xpath = "filter"+XmlConfig.XPATH_ENABLED;
			XmlConfig cfg_filter = cfg.getSection(xpath);
			if (cfg_filter.exists()) {
				tmtfilter = cfg_filter.getTime("@timeout", TimeOps.parseMilliTime("2m"));
				if (tmtfilter == 0) throw new MailismusConfigException(logpfx+"Filter timeout cannot be zero");
				dsptch.getLogger().info(logpfx+"Filter timeout="+TimeOps.expandMilliTime(tmtfilter));
				filter_manager = new FilterManager(cfg_filter, dsptch);
			} else {
				filter_manager = null;
				tmtfilter = 0;
			}

			smtprsp_ok = com.grey.mailismus.Task.constBuffer(DFLTRSP_OK);
			smtprsp_data = com.grey.mailismus.Task.constBuffer(DFLTRSP_DATA);
			smtprsp_quit = com.grey.mailismus.Task.constBuffer(DFLTRSP_QUIT);
			smtprsp_shutdown = com.grey.mailismus.Task.constBuffer(DFLTRSP_SHUTDOWN);
			smtprsp_badhello = com.grey.mailismus.Task.constBuffer(DFLTRSP_BADHELLO);
			smtprsp_badsender = com.grey.mailismus.Task.constBuffer(DFLTRSP_BADSENDER);
			smtprsp_badrecip = com.grey.mailismus.Task.constBuffer(DFLTRSP_BADRECIP);
			smtprsp_relaydenied = com.grey.mailismus.Task.constBuffer(DFLTRSP_RELAYDENIED);
			smtprsp_blacklisted = com.grey.mailismus.Task.constBuffer(blackrsp);
			smtprsp_greylisted = com.grey.mailismus.Task.constBuffer(greyrsp);
			smtprsp_excessrecips = com.grey.mailismus.Task.constBuffer(DFLTRSP_EXCESSRECIPS);
			smtprsp_excessmsgs = com.grey.mailismus.Task.constBuffer(DFLTRSP_EXCESSMSGS);
			smtprsp_msgsize = com.grey.mailismus.Task.constBuffer(DFLTRSP_MSGSIZE);
			smtprsp_busy = com.grey.mailismus.Task.constBuffer(DFLTRSP_BUSY);
			smtprsp_nullrecips = com.grey.mailismus.Task.constBuffer(DFLTRSP_NULLRECIPS);
			smtprsp_errproto = com.grey.mailismus.Task.constBuffer(DFLTRSP_ERRPROTO);
			smtprsp_premature = com.grey.mailismus.Task.constBuffer(DFLTRSP_PREMATURE);
			smtprsp_forged = com.grey.mailismus.Task.constBuffer(DFLTRSP_FORGED);
			smtprsp_errlocal = com.grey.mailismus.Task.constBuffer(DFLTRSP_ERRLOCAL);
			smtprsp_nuisance = com.grey.mailismus.Task.constBuffer(DFLTRSP_NUISANCE);
			smtprsp_needssl = com.grey.mailismus.Task.constBuffer(DFLTRSP_NEEDSSL);
			smtprsp_needauth = com.grey.mailismus.Task.constBuffer(DFLTRSP_NEEDAUTH);
			smtprsp_authok = com.grey.mailismus.Task.constBuffer(DFLTRSP_AUTHOK);
			smtprsp_invauth = com.grey.mailismus.Task.constBuffer(DFLTRSP_INVAUTH);
			smtprsp_emptychallenge = com.grey.mailismus.Task.constBuffer(Protocol.AUTH_CHALLENGE+Protocol.EOL);

			if (dtory == null) {
				saslmechs_plain = null;
				saslmechs_cmd5 = null;
				saslmechs_ext = null;
			} else {
				com.grey.base.sasl.SaslServerFactory fact = new com.grey.base.sasl.SaslServerFactory(com.grey.base.sasl.SaslEntity.MECH.PLAIN, saslauth, true);
				saslmechs_plain = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.PlainServer>(null, fact, "SMTP_Plain-"+lstnr.getName(), 0, 0, 1);
				fact = new com.grey.base.sasl.SaslServerFactory(com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5, saslauth, true);
				saslmechs_cmd5 = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.CramMD5Server>(null, fact, "SMTP_CramMD5-"+lstnr.getName(), 0, 0, 1);
				fact = new com.grey.base.sasl.SaslServerFactory(com.grey.base.sasl.SaslEntity.MECH.EXTERNAL, saslauth, true);
				saslmechs_ext = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.ExternalServer>(null, fact, "SMTP_External-"+lstnr.getName(), 0, 0, 1);
			}
			addrbufcache = new com.grey.base.collections.ObjectWell<com.grey.base.utils.EmailAddress>(com.grey.base.utils.EmailAddress.class, "SmtpServer-"+lstnr.getName());
			bcwell = new com.grey.base.collections.ObjectWell<com.grey.base.utils.ByteChars>(com.grey.base.utils.ByteChars.class, "SmtpServer-"+lstnr.getName());
		}

		// Required to be generally unique, but not with a 100% guarantee of not reusung the IDs of a prev Mailismus process.
		// The only hard and fast requirement is that never clash with the message IDs we report for accepted messages, and
		// since those are simply hexadecimal SPID values, the leading "DM" ensures that.
		public CharSequence genDiscardMessageID() {
			discardmsgidbuf.setLength(0);
			return discardmsgidbuf.append("DM").append(++discard_msgid);
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

	private final SharedFields shared;
	private final ResolverDNS resolver;
	private final java.util.ArrayList<com.grey.base.utils.EmailAddress> msgrecips
			= new java.util.ArrayList<com.grey.base.utils.EmailAddress>(); //valid, resolved recipients
	private final java.util.ArrayList<com.grey.base.utils.ByteChars> rawrecips
			= new java.util.ArrayList<com.grey.base.utils.ByteChars>(); //original form of recipients (as received in MAILTO)
	private final java.util.ArrayList<com.grey.base.utils.ByteChars> sender_rewrites
			= new java.util.ArrayList<com.grey.base.utils.ByteChars>(); //rewritten senders
	private final com.grey.base.utils.TSAP remote_tsap = new com.grey.base.utils.TSAP();
	private com.grey.base.utils.ByteChars remote_helo;
	private com.grey.base.utils.ByteChars msgsender;
	private ConnectionConfig conncfg; //config to apply to current connection
	private com.grey.base.sasl.SaslServer saslmech;
	private com.grey.base.utils.ByteChars username;
	private PROTO_STATE pstate;
	private byte state2; //secondary-state, qualifying some of the pstate phases
	private PROTO_EVENT dnsEvent; //event that triggered current DNS request - only relevant if dnsWait is True
	private com.grey.base.utils.EmailAddress dnsAddress; //subject of current DNS request - or of non-DNS/pre-DNS validation
	private ValidationSettings.HOSTDIR dnsPhase;
	private PROTO_EVENT delayedEvent;
	private java.nio.ByteBuffer delayedRsp;
	private com.grey.mailismus.mta.queue.SubmitHandle msgh;
	private com.grey.naf.reactor.TimerNAF tmr_exit;
	private com.grey.naf.reactor.TimerNAF tmr_sesstmt;
	private com.grey.naf.reactor.TimerNAF tmr_filter;
	private com.grey.naf.reactor.TimerNAF tmr_action;
	private com.grey.naf.reactor.TimerNAF tmr_greet;
	private int msgcnt; //number of messages received during current connection - for the prototype Server instance, this is the total
	private int msgsize_body; //running total of current message size
	private int badcmdcnt;
	private int eomseq_off;
	private int eomseqlf_off;
	private int thisEntered; //detect how deeply nested we are, in terms of callbacks from NAF
	private FilterExecutor msgfilter; //non-null means filter op currently in progress (S_FILTER tells us same thing)
	private int cnxid; //increments with each incarnation - useful for distinguishing Transcript logs
	private String pfx_log;
	private String pfx_transcript;

	private void setFlag(byte f) {state2 |= f;}
	private void clearFlag(byte f) {state2 &= ~f;}
	private boolean isFlagSet(byte f) {return ((state2 & f) != 0);}

	@Override
	public CharSequence nafmanHandlerID() {return getListener().getName();}


	// Prototype constructor.
	// Note that although it initialises differently, this instance doesn't necessarily know that it's a prototype, and it is also
	// ready for action. It's under the control of the (potential) prototype's creator, as to whether it is used as a prototype or
	// an active object, or both.
	public Server(CM_Listener l, XmlConfig cfg) throws java.io.IOException
	{
		super(l, null, null);
		String stem = "SMTP-Server";
		pfx_log = stem+"/E"+getCMID();
		String pfx = stem+(getSSLConfig() == null || getSSLConfig().isLatent() ? "" : "/SSL")+": ";
		com.grey.logging.Logger log = getLogger();
		MTA_Task task = MTA_Task.class.cast(getListener().getController());
		resolver = task.getResolverDNS();
		shared = new SharedFields(cfg, getDispatcher(), task, this, getListener(), getSSLConfig(), pfx);

		if (shared.relay_clients != null) {
			String txt = pfx+"Relay clients="+shared.relay_clients.length+" [";
			String dlm = "";
			for (int idx = 0; idx != shared.relay_clients.length; idx++) {
				txt += dlm + com.grey.base.utils.IP.displayDottedIP(shared.relay_clients[idx].ip, null)+"/"+shared.relay_clients[idx].netprefix;
				dlm = ", ";
			}
			txt += "]";
			log.info(txt);
		}
		log.trace(pfx+shared.netbufs);
		log.trace(pfx+"local-delivery="+shared.localdelivery
				+"; spf-sender-rewrite="+shared.spf_sender_rewrite
				+"; srcrouted-bounces-recip="+shared.srcrouted_bounces_recip
				+"; require-CRLF="+REQUIRE_CRLF);
		getLogger().info(pfx+"Declare self as '"+shared.appConfig.getProductName());

		if (getDispatcher().getNafManAgent() != null) {
			com.grey.naf.nafman.NafManRegistry reg = getDispatcher().getNafManAgent().getRegistry();
			task.registerDirectoryOps(com.grey.mailismus.nafman.Loader.PREF_DTORY_SMTPS);
			reg.registerHandler(com.grey.mailismus.nafman.Loader.CMD_COUNTERS, 0, this, getDispatcher());
			if (shared.greylst != null) {
				reg.registerHandler(com.grey.mailismus.nafman.Loader.CMD_LISTGREY, 0, this, getDispatcher());
			}
		}
	}

	Server(Server proto)
	{
		super(proto.getListener(), proto.shared.netbufs, proto.shared.netbufs);
		shared = proto.shared;
		resolver = proto.resolver;
		setLogPrefix();
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

	@Override
	public CharSequence handleNAFManCommand(com.grey.naf.nafman.NafManCommand cmd) throws java.io.IOException
	{
		StringBuilder sb = shared.tmpsb;
		sb.setLength(0);
		if (cmd.getCommandDef().code.equals(com.grey.mailismus.nafman.Loader.CMD_LISTGREY)) {
			shared.greylst.show(sb);
		} else if (cmd.getCommandDef().code.equals(com.grey.mailismus.nafman.Loader.CMD_COUNTERS)) {
			sb.append("Stats since ");
			TimeOps.makeTimeLogger(shared.stats_start, sb, true, true);
			sb.append(" - Period=");
			TimeOps.expandMilliTime(getSystemTime() - shared.stats_start, sb, false);
			sb.append("<br/>Connections: ").append(shared.stats_conncnt).append(" (Rej=").append(shared.stats_rejconns);
			sb.append(")<br/>Messages: ").append(shared.stats_msgcnt);
			sb.append("<br/>Recipients: ").append(shared.stats_recipcnt).append(" (Rej=").append(shared.stats_rejrecips);
			sb.append(")<br/>Current Connections: ").append(shared.current_conncnt).append(" (Clients=").append(shared.ipconns.size());
			sb.append(")<br/>Peak concurrency: ").append(shared.stats_peakconcurrency).append(" (all-time=").append(shared.peak_conncnt).append(')');
			if (StringOps.stringAsBool(cmd.getArg(com.grey.naf.nafman.NafManCommand.ATTR_RESET))) {
				shared.stats_start = getSystemTime();
				shared.stats_conncnt = 0;
				shared.stats_msgcnt = 0;
				shared.stats_recipcnt = 0;
				shared.stats_rejconns = 0;
				shared.stats_rejrecips = 0;
				shared.stats_peakconcurrency = 0;
			}
		} else {
			getLogger().error(pfx_log+": Missing case for NAFMAN cmd="+cmd.getCommandDef().code);
			return null;
		}
		return sb;
	}

	@Override
	public boolean abortServer()
	{
		if (this == shared.prototype_server) {
			if (shared.stopped) return true;
			shared.stopped = true;
			shared.qmgr.stop();
			if (shared.filter_manager != null) shared.filter_manager.shutdown();
			if (shared.blacklst != null) shared.blacklst.close();
			if (shared.greylst != null) shared.greylst.close();
			if (shared.transcript != null)	shared.transcript.close(getSystemTime());
			shared.ipconns.clear();
			return true;
		}
		if (pstate == PROTO_STATE.S_DISCON) return (tmr_exit == null);
		setFlag(S2_ABORT);
		try {transmit(shared.smtprsp_shutdown);} catch (Throwable ex) {} //this message was just a courtesy anyway
		issueDisconnect("Shutting down");
		getReader().endReceive();
		return false; //we will call back to reaper explicitly (may even have done so already)
	}

	// call this whenever an entry-point call to this object returns
	private void exitThis() throws java.io.IOException
	{
		//Detect if we're returning control out of this class back to NAF, or still nested
		if (--thisEntered > 0) {
			//Can only go negative if exitThis() previously threw, so if in doubt err on the side of being
			//responsive, ie. taking the exit actions.
			return;
		}

		if (isFlagSet(S2_DATAWAIT)) {
			if (tmr_sesstmt == null) {
				// we're in a state that requires the timer, so if it doesn't exist, that's because it's not created yet - create now
				if (conncfg.tmtprotocol != 0) tmr_sesstmt = getDispatcher().setTimer(conncfg.tmtprotocol, TMRTYPE_SESSIONTMT, this);
			} else {
				if (tmr_sesstmt.age(getDispatcher()) > Task.MIN_RESET_PERIOD) tmr_sesstmt.reset();
			}
			if (pstate == PROTO_STATE.S_MAILBODY) {
				getReader().receive(0);
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

	private PROTO_STATE raiseEvent(PROTO_EVENT evt, PROTO_STATE newstate, CharSequence discmsg)
			throws java.io.IOException
	{
		if (newstate != null) transitionState(newstate);
		return handleEvent(evt, null, discmsg);
	}

	// this eliminate the Throws declaration for events that are known not to throw
	private PROTO_STATE raiseSafeEvent(PROTO_EVENT evt, PROTO_STATE newstate, CharSequence discmsg, boolean entrypoint)
	{
		try {
			raiseEvent(evt, newstate, discmsg);
			if (entrypoint) exitThis();
		} catch (Throwable ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed to issue event="+evt);
			endConnection(PROTO_EVENT.E_LOCALERROR, "Failed to issue event="+evt+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
		return pstate;
	}

	@Override
	protected void connected() throws java.io.IOException
	{
		initServer();
		thisEntered++;
		// increment our stats first, because endConnection() will decrement them (and recordConn uses them too)
		if (shared.current_conncnt++ == shared.stats_peakconcurrency) {
			shared.stats_peakconcurrency = shared.current_conncnt;
			if (shared.current_conncnt > shared.peak_conncnt) shared.peak_conncnt = shared.current_conncnt;
		}
		shared.stats_conncnt++;
		recordConnection(false); //this sets remote_tsap
		conncfg = getConnectionConfig(remote_tsap.ip);

		if ((dnsPhase = conncfg.validHELO.direction) == ValidationSettings.HOSTDIR.BOTH) {
			dnsPhase = ValidationSettings.HOSTDIR.BACKWARD; //start with backwards validation
		}
		raiseEvent(PROTO_EVENT.E_CONNECTED, PROTO_STATE.S_HELO, null);
		exitThis();
	}

	private void initServer()
	{
		pstate = PROTO_STATE.S_DISCON; //we are actually connected now, but this was starting point
		state2 = 0;
		msgcnt = 0;
		badcmdcnt = 0;
		thisEntered = 0;
		cnxid++;
		setLogPrefix();
		// these should already be null/empty, but just discard back to GC if not
		username = null;
		remote_helo = null;
		msgsender = null;
		dnsAddress = null;
		msgfilter = null;
		msgrecips.clear();
		sender_rewrites.clear();
		rawrecips.clear();
	}

	private void setLogPrefix() {
		int pos = shared.prototype_server.pfx_log.lastIndexOf('E');
		pfx_log = shared.prototype_server.pfx_log.substring(0, pos+1)+getCMID()+"-"+cnxid;
		pos = pfx_log.lastIndexOf('E');
		pfx_transcript = pfx_log.substring(pos);
	}

	private void recordConnection(boolean notrace)
	{
		LEVEL lvl = LEVEL.TRC2;
		boolean log = getLogger().isActive(lvl) || (shared.transcript != null);
		com.grey.base.utils.TSAP.get(getRemoteIP(), getRemotePort(), remote_tsap, log);

		if (log) {
			com.grey.base.utils.TSAP local_tsap = com.grey.base.utils.TSAP.get(getLocalIP(), getLocalPort(), shared.tmptsap, true);
			if (!notrace && getLogger().isActive(lvl)) {
				StringBuilder sb = shared.tmpsb;
				sb.setLength(0);
				sb.append(pfx_log).append(" called on ").append(local_tsap.dotted_ip).append(':').append(local_tsap.port);
				sb.append(" by remote=").append(remote_tsap.dotted_ip).append(':').append(remote_tsap.port);
				sb.append(" - concurrent=").append(shared.current_conncnt);
				sb.append('/').append(shared.stats_peakconcurrency).append('/').append(shared.peak_conncnt);
				getLogger().log(lvl, sb);
			}
			if (shared.transcript != null) {
				shared.transcript.connection_in(pfx_transcript, remote_tsap.dotted_ip, remote_tsap.port, local_tsap.dotted_ip, local_tsap.port,
						getSystemTime(), usingSSL());
			}
		}
	}

	@Override
	public void ioDisconnected(CharSequence diagnostic)
	{
		thisEntered++;
		raiseSafeEvent(PROTO_EVENT.E_DISCONNECTED, null, diagnostic, true);
	}

	private PROTO_STATE issueDisconnect(CharSequence diagnostic)
	{
		return raiseSafeEvent(PROTO_EVENT.E_DISCONNECT, null, diagnostic, false);
	}

	@Override
	public void ioReceived(ByteArrayRef rcvdata) throws java.io.IOException
	{
		if (pstate == PROTO_STATE.S_DISCON) return; //this method can be called in a loop, so skip it after a disconnect
		thisEntered++;
		clearFlag(S2_DATAWAIT);
		if (shared.transcript != null && (pstate != PROTO_STATE.S_MAILBODY || TRANSCRIPTBODY)) {
			shared.transcript.data_in(pfx_transcript, rcvdata, getSystemTime());
		}

		if (tmr_greet != null) {
			executeAction(0, shared.smtprsp_premature, PROTO_EVENT.E_DISCONNECT);
		} else {
			if (pstate == PROTO_STATE.S_SASL) {
				//strip End-of-Line sequence, but not any trailing spaces
				while (rcvdata.size() != 0 && rcvdata.byteAt(rcvdata.size() - 1) < ' ') rcvdata.incrementSize(-1);
				handleEvent(PROTO_EVENT.E_SASLRSP, rcvdata, null);
			} else {
				boolean matchedRule = false;
				int triggercnt = fsmTriggers.length;
				for (int idx = 0; idx != triggercnt; idx++) {
					FSM_Trigger trigger = fsmTriggers[idx];
					if (trigger.state == null || trigger.state == pstate) {
						if ((trigger.cmd == null) || matchesCommand(rcvdata, trigger.cmd)) {
							if (trigger.new_state != null) transitionState(trigger.new_state);
							handleEvent(trigger.evt, rcvdata, null);
							matchedRule = true;
							break;
						}
					}
				}
				if (!matchedRule) {
					executeAction(conncfg.delay_badcmd, null, PROTO_EVENT.E_BADCMD);
				}
			}
		}
		exitThis();
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, Dispatcher d)
			throws java.io.IOException
	{
		thisEntered++;

		switch (tmr.getType())
		{
		case TMRTYPE_SESSIONTMT:
			tmr_sesstmt = null;		// this timer is now expired, so we must not access it again
			issueDisconnect("Client-timeout");
			break;

		case TMRTYPE_FILTER:
			tmr_filter = null;
			shared.transcript.event(pfx_transcript, "Filter-timeout", getSystemTime());
			executeAction(0, shared.smtprsp_errlocal, PROTO_EVENT.E_DISCONNECT);
			break;

		case TMRTYPE_DISCON:
			tmr_exit = null;
			disconnect();
			break;

		case TMRTYPE_ACTION:
			tmr_action = null;
			executeAction(0, delayedRsp, delayedEvent);
			break;

		case TMRTYPE_GREET:
			tmr_greet = null;
			raiseEvent(PROTO_EVENT.E_GREET, null, null);
			break;

		default:
			getLogger().error(pfx_log+": Unexpected timer-type="+tmr.getType());
			break;
		}
		exitThis();
	}

	@Override
	public void eventError(Throwable ex)
	{
		thisEntered++;
		eventErrorIndication(ex, null);
	}

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, Dispatcher d, Throwable ex)
	{
		thisEntered++;
		eventErrorIndication(ex, tmr);
	}

	// error already logged by Dispatcher
	private void eventErrorIndication(Throwable ex, com.grey.naf.reactor.TimerNAF tmr)
	{
		raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "State="+pstate+" - "+com.grey.base.ExceptionUtils.summary(ex), true);
	}

	private void transmit(java.nio.ByteBuffer xmtbuf) throws java.io.IOException
	{
		if (shared.transcript != null) shared.transcript.data_out(pfx_transcript, xmtbuf, 0, getSystemTime());
		xmtbuf.position(0);
		getWriter().transmit(xmtbuf);
		setFlag(S2_DATAWAIT);
	}

	private void transmit(com.grey.base.utils.ByteChars data) throws java.io.IOException
	{
		shared.tmpniobuf = shared.netbufs.encode(data, shared.tmpniobuf);
		transmit(shared.tmpniobuf);
	}

	// We don't do the final channel disconnect in here, as we defer that to a timer-driven callback.
	// This is primarily for safety, to prevent the final disconnect (which releases this object) being issued at the end of a long calling chain,
	// in case some code higher up the chain attempts to access this now-deleted object, so a zero-second timer would suffice.
	// However, the use of a timer also permits us to specify an actual delay, which helps work around the fact that there seem to be issues with the
	// Linger option in the JDK (or maybe just on Windows?) and an instant close can become an abort even if Linger is off.
	private void endConnection(PROTO_EVENT evt, CharSequence discmsg)
	{
		LEVEL lvl = LEVEL.TRC2;
		if (getLogger().isActive(lvl)) {
			shared.tmpsb.setLength(0);
			shared.tmpsb.append(pfx_log).append(" ending with state=").append(pstate).append("/0x").append(Integer.toHexString(state2));
			shared.tmpsb.append(", remote=").append(remote_tsap);
			if (username != null) shared.tmpsb.append("/auth=").append(username);
			shared.tmpsb.append(", msgcnt=").append(msgcnt);
			if (discmsg != null) shared.tmpsb.append(" - reason=").append(discmsg);
			getLogger().log(lvl, shared.tmpsb);
		}
		if (tmr_sesstmt != null) {
			tmr_sesstmt.cancel();
			tmr_sesstmt = null;
		}
		if (tmr_filter != null) {
			tmr_filter.cancel();
			tmr_filter = null;
		}
		if (tmr_action != null) {
			tmr_action.cancel();
			tmr_action = null;
		}
		if (tmr_greet != null) {
			tmr_greet.cancel();
			tmr_greet = null;
		}
		if (isFlagSet(S2_ENDED)) return; //shutdown events criss-crossing each other - that's ok
		setFlag(S2_ENDED);

		if (conncfg != null && conncfg.max_ipconns != 0 && conncfg.max_ipconns != -1) {
			int ipconns = shared.ipconns.get(remote_tsap.ip);
			if (ipconns == 1) {
				// this was the last connection from that remote IP, so remove it from tracker
				shared.ipconns.remove(remote_tsap.ip);
			} else {
				// decrement remote IP's connection count to mark the end of this connection
				shared.ipconns.put(remote_tsap.ip, ipconns - 1);
			}
		}
		shared.current_conncnt--;

		if (msgfilter != null) {
			try {
				msgfilter.cancelMessage();
			} catch (Throwable ex) {
				getLogger().log(LEVEL.ERR, ex, true, pfx_log+": Filter threw on cancel()");
			}
			msgfilter = null;
		}
		concludeMessage(true); //if a message-submission is currently in progress, then it's obviously been aborted

		if (isFlagSet(S2_DNSWAIT)) {
			try {
				resolver.cancel(this);
			} catch (Exception ex) {
				getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed to cancel DNS ops");
			}
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
		if (username != null) {
			shared.bcwell.store(username);
			username = null;
		}
		if (remote_helo != null) {
			shared.bcwell.store(remote_helo);
			remote_helo = null;
		}
		if (saslmech != null) releaseSASL();
		remote_tsap.clear();
		clearFlag(S2_DATAWAIT);
		transitionState(PROTO_STATE.S_DISCON);
		// don't call base disconnect() till next Dispatcher callback, to prevent reentrancy issues
		long delay = (isFlagSet(S2_ABORT) || evt == PROTO_EVENT.E_DISCONNECTED || evt == PROTO_EVENT.E_LOCALERROR ? 0 : conncfg.delay_chanclose);
		tmr_exit = getDispatcher().setTimer(delay, TMRTYPE_DISCON, this);
	}

	private boolean concludeMessage(boolean rollback)
	{
		boolean success = true;
		if (msgh != null) {
			if ((success = shared.qmgr.endSubmit(msgh, rollback))) {
				if (!rollback) {
					shared.stats_msgcnt++;
					shared.stats_recipcnt += msgrecips.size();
				}
			}
			msgh = null;
		}
		while (msgrecips.size() != 0) {
			shared.addrbufcache.store(msgrecips.remove(0));
		}
		while (rawrecips.size() != 0) {
			shared.bcwell.store(rawrecips.remove(0));
		}
		while (sender_rewrites.size() != 0) {
			com.grey.base.utils.ByteChars bc = sender_rewrites.remove(0);
			if (bc != null) shared.bcwell.store(bc);
		}
		if (msgsender != null) {
			shared.bcwell.store(msgsender);
			msgsender = null;
		}
		if (dnsAddress != null) {
			//must have been in the middle of validating something
			shared.addrbufcache.store(dnsAddress);
			dnsAddress = null;
		}
		clearFlag(S2_RECIPSTRANSFORM);
		clearFlag(S2_RECIPSDISCARD);
		return success;
	}

	private void executeAction(long delay, java.nio.ByteBuffer buf, PROTO_EVENT evt)
			throws java.io.IOException
	{
		if (delay == 0) {
			if (buf != null) transmit(buf);
			if (evt != null) {
				if (evt == PROTO_EVENT.E_DISCONNECT) shared.stats_rejconns++;
				raiseEvent(evt, null, null);
			}
			return;
		}
		delayedRsp = buf;
		delayedEvent = evt;

		if (tmr_action == null) {
			tmr_action = getDispatcher().setTimer(delay, TMRTYPE_ACTION, this);
		} else {
			tmr_action.reset(delay);
		}
	}

	private PROTO_STATE handleEvent(PROTO_EVENT evt, ByteArrayRef rcvdata, CharSequence discmsg) throws java.io.IOException
	{
		if (evt != PROTO_EVENT.E_BADCMD) badcmdcnt = 0;
		com.grey.base.utils.EmailAddress emaddr;
		com.grey.base.utils.ByteChars bc;
		java.nio.ByteBuffer rspmsg;
		PROTO_EVENT evt2;
		boolean is_valid;
		long delay;

		switch (evt)
		{
		case E_CONNECTED:
			if (conncfg.max_ipconns != 0) {
				//there is a limit on connections from this remote IP
				if (conncfg.max_ipconns == -1) {
					//in fact, no connections are allowed
					executeAction(conncfg.delay_badcmd, shared.smtprsp_relaydenied, PROTO_EVENT.E_DISCONNECT);
					return pstate;
				}
				int ipconns = shared.ipconns.get(remote_tsap.ip) + 1;
				shared.ipconns.put(remote_tsap.ip, ipconns);

				if (ipconns > conncfg.max_ipconns) {
					transmit(shared.smtprsp_busy);
					return issueDisconnect("Max connections exceeded");
				}
			}
			if (shared.blacklst != null) {
				if (shared.blacklst.exists(remote_tsap.ip)) {
					executeAction(conncfg.delay_badcmd, shared.smtprsp_blacklisted, PROTO_EVENT.E_DISCONNECT);
					return pstate;
				}
			}
			if (conncfg.delay_greet == 0) return raiseEvent(PROTO_EVENT.E_GREET, null, null);
			tmr_greet = getDispatcher().setTimer(conncfg.delay_greet, TMRTYPE_GREET, this);
			setFlag(S2_DATAWAIT); //turn on receive so we can trap premature responses
			break;

		case E_DISCONNECT:
		case E_DISCONNECTED:
			endConnection(evt, discmsg);
			break;

		case E_STLS:
			// Arguably we shouldn't prevent the client authenticating before this if we allow non-SSL
			// authentication, but it just seems odd behaviour on its part.
			if (getSSLConfig() == null || usingSSL() || msgcnt != 0 || username != null) {
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_IDLE, null);
			}
			transmit(shared.smtprsp_ok);
			startSSL();
			break;

		case E_GREET:
			transmit(conncfg.smtprsp_greet);
			break;

		case E_HELO:
		case E_EHLO:
			emaddr = shared.addrbufcache.extract();
			emaddr.parse(rcvdata);
			handleAddress(evt, emaddr, conncfg.validHELO);
			break;

		case E_SASL_PLAIN:
			if (!validateAuth(com.grey.base.sasl.SaslEntity.MECH.PLAIN)) {
				return pstate;
			}
			saslmech = shared.saslmechs_plain.extract().init();
			if (rcvdata.size() == 0) {
				//The Auth command didn't include an initial response, so send an empty initial challenge.
				transmit(shared.smtprsp_emptychallenge);
				break;
			}
			return handleEvent(PROTO_EVENT.E_SASLRSP, rcvdata, null);

		case E_SASL_CMD5:
			if (!validateAuth(com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5)) {
				return pstate;
			}
			if (rcvdata.size() != 0) {
				//initial response not allowed for this mechanism
				return raiseEvent(PROTO_EVENT.E_BADCMD, PROTO_STATE.S_IDLE, null);
			}
			com.grey.base.sasl.CramMD5Server cmd5 = shared.saslmechs_cmd5.extract().init("SMTP", getCMID(), shared.tmpsb);
			saslmech = cmd5;
			bc = shared.tmpbcbuf.populate(Protocol.AUTH_CHALLENGE);
			cmd5.setChallenge(bc);
			bc.append(Protocol.EOL);
			transmit(bc);
			break;

		case E_SASL_EXTERNAL:
			if (!validateAuth(com.grey.base.sasl.SaslEntity.MECH.EXTERNAL)) {
				return pstate;
			}
			saslmech = shared.saslmechs_ext.extract().init(getPeerCertificate());
			if (rcvdata.size() == 0) {
				//The Auth command didn't include an initial response, so send an empty initial challenge.
				transmit(shared.smtprsp_emptychallenge);
				break;
			}
			if (rcvdata.size() == 1 && rcvdata.byteAt(0) == Protocol.AUTH_EMPTY) {
				rcvdata.clear();
			}
			return handleEvent(PROTO_EVENT.E_SASLRSP, rcvdata, null);

		case E_SASLRSP:
			is_valid = saslmech.verifyResponse(rcvdata);
			if (is_valid) {
				//client is now authenticated - this means it is allowed to relay, regardless of its IP
				username = shared.bcwell.extract().populate(saslmech.getUser());
				rspmsg = shared.smtprsp_authok;
				evt2 = null;
				delay = 0;
			} else {
				LEVEL lvl = LEVEL.TRC;
				if (getLogger().isActive(lvl)) {
					StringBuilder sb = shared.tmpsb;
					sb.setLength(0);
					sb.append(pfx_log).append(": AUTH/").append(saslmech.mechanism);
					sb.append(" failed with username=").append(saslmech.getUser());
					getLogger().log(lvl, sb);
				}
				rspmsg = shared.smtprsp_invauth;
				evt2 = PROTO_EVENT.E_DISCONNECT;
				delay = conncfg.delay_badcmd;
			}
			releaseSASL();
			transitionState(PROTO_STATE.S_IDLE);
			executeAction(delay, rspmsg, evt2);
			break;

		case E_MAILFROM:
			// start of new message
			if (!usingSSL() && getSSLConfig() != null && getSSLConfig().isMandatory()) {
				transitionState(PROTO_STATE.S_IDLE);
				transmit(shared.smtprsp_needssl);
				break;
			}
			if (conncfg.auth_mdty && username == null && !isRelayClient()) {
				transitionState(PROTO_STATE.S_IDLE);
				transmit(shared.smtprsp_needauth);
				break;
			}
			if (conncfg.max_connmsgs != 0 && msgcnt == conncfg.max_connmsgs) {
				transmit(shared.smtprsp_excessmsgs);
				return issueDisconnect("Max messages per connection exceeded");
			}
			// The way EmailAddress.parse() works, it will strip any AUTH param after the address (see RFC-4954, section 5).
			// Strictly, we're supposed to pass it on to the next hop SMTP server, but we won't. We just discard it right here.
			emaddr = shared.addrbufcache.extract();
			emaddr.parse(rcvdata); //NB: this converts incoming address to the expected lower-case form
			handleAddress(evt, emaddr, conncfg.validSender);
			break;

		case E_MAILTO:
			if (conncfg.max_msgrecips != 0 && rawrecips.size() == conncfg.max_msgrecips) {
				transmit(shared.smtprsp_excessrecips);
				return pstate;
			}
			emaddr = shared.addrbufcache.extract();
			emaddr.parse(rcvdata); //NB: this converts incoming address to the expected lower-case form
			bc = shared.bcwell.extract().populate(emaddr.full);
			rawrecips.add(bc);
			handleAddress(evt, emaddr, conncfg.validRecip);
			break;

		case E_BODYSTART:
			if (msgrecips.size()== 0) {
				//if this is because recips were silently dropped, pretend to accept the message, but discard it
				if (!isFlagSet(S2_RECIPSDISCARD)) {
					//client is aware we haven't accepted any recips, so reject DATA command with an error
					return raiseEvent(PROTO_EVENT.E_NULLRECIPS, null, null);
				}
			} else {
				msgh = shared.qmgr.startSubmit(msgsender, msgrecips, sender_rewrites, remote_tsap.ip);
				if (!conncfg.omitrcvhdr) {
					if (getSystemTime() - shared.dtcal.getTimeInMillis() > 1000) shared.dtcal.setTimeInMillis(getSystemTime()); //avoid too frequent
					shared.tmpsb.setLength(0);
					TimeOps.makeTimeRFC822(shared.dtcal, shared.tmpsb);
					remote_tsap.ensureDotted();
					bc = shared.tmpbcbuf.clear();
					bc.append(RCVHDR_PFX).append(remote_tsap.dotted_ip).append(RCVHDR_HELO).append(remote_helo);
					bc.append(RCVHDR_BY).append(conncfg.announcehost).append(RCVHDR_PREPKGNAM).append(shared.appConfig.getProductName());
					bc.append(RCVHDR_WITH1).append("ESMTP"); //see RFC-3848 for WITH types
					if (usingSSL()) bc.append('S');
					if (username != null) bc.append('A');
					bc.append(RCVHDR_WITH2).append(shared.qmgr.externalSPID(msgh.spid)).append(RCVHDR_WITH3);
					if (rawrecips.size() == 1) bc.append(RCVHDR_FOR1).append(rawrecips.get(0)).append(RCVHDR_FOR2);
					bc.append(shared.tmpsb).append(Protocol.EOL_BC);
					msgh.write(bc);
				}
			}
			transmit(shared.smtprsp_data);
			msgsize_body = 0;
			eomseq_off = 0;
			eomseqlf_off = 0;
			break;

		case E_BODYDATA:
			// We look for the possible termination sequences (plural if we don't insist on CRLF) and we always strip
			// the final 3 bytes from the message-body stream.
			// If it was terminated by CRLF.CRLF, then those 3 are the final .CRLF, which is not part of the message
			// body and has to go.
			// If it was terminated by LF.LF, then we strip both the final .LF as well as the preceding LF, as we then
			// add a CRLF to make sure the message is at least properly terminated.
			int rcvlen = rcvdata.size();
			int off_next = 0;
			eomseq_off = ByteOps.find(rcvdata.buffer(), rcvdata.offset(), rcvlen, EOMSEQ_CRLF, eomseq_off);
			if (eomseq_off < 0) {
				off_next = -eomseq_off;
			} else if (!REQUIRE_CRLF) {
				eomseqlf_off = ByteOps.find(rcvdata.buffer(), rcvdata.offset(), rcvlen, EOMSEQ_LF, eomseqlf_off);
				if (eomseqlf_off < 0) off_next = -eomseqlf_off;
			}
			int excess = (off_next == 0 ? 0 : rcvdata.limit() - off_next);
			rcvlen -= excess;
			msgsize_body += rcvlen;

			if (msgh != null) {
				if (conncfg.max_msgsize != 0 && msgsize_body > conncfg.max_msgsize) {
					transmit(shared.smtprsp_msgsize);
					return issueDisconnect("Max message-size exceeded");
				}
				rcvdata.setSize(rcvlen);
				msgh.write(rcvdata);

				if (off_next != 0) {
					//we have now fully received the message body, so seal the spool
					if (!msgh.close(3, eomseqlf_off < 0, getLogger())) {
						return raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed to close spool", false);
					}
				}
			}

			if (off_next == 0) {
				setFlag(S2_DATAWAIT); //keep receiving, though we issue no response right now
				break;
			}

			if (excess != 0) {
				getReader().pushback(rcvdata.buffer(), off_next, excess);
			}

			if (shared.filter_manager != null && msgh != null) {
				return raiseEvent(PROTO_EVENT.E_FILTERMSG, PROTO_STATE.S_FILTER, null);
			}
			raiseEvent(PROTO_EVENT.E_ACCEPTMSG, PROTO_STATE.S_IDLE, null);
			break;

		case E_FILTERMSG:
			java.nio.file.Path fh = msgh.getMessage();
			tmr_filter = getDispatcher().setTimer(shared.tmtfilter, TMRTYPE_FILTER, this);
			msgfilter = shared.filter_manager.approveMessage(this, remote_tsap, username, remote_helo, msgsender, msgrecips, fh);
			break;

		case E_ACCEPTMSG:
			if (msgrecips.size() == 0) {
				messageReceived(false, "accepted/discarding", null);
			} else {
				messageReceived(false, "accepted", null);
			}
			break;

		case E_REJECTMSG:
			messageReceived(true, "rejected", discmsg);
			break;

		case E_QUIT:
			transmit(shared.smtprsp_quit);
			issueDisconnect(null);
			break;

		case E_RESET:
			if (!concludeMessage(true)) return raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed to rollback msg on RESET", false);
			transmit(shared.smtprsp_ok);
			break;

		case E_NOOP:
			transmit(shared.smtprsp_ok);
			break;

		case E_NULLRECIPS:
			transmit(shared.smtprsp_nullrecips);
			issueDisconnect("No recipients specified");
			break;

		case E_BADCMD:
			if (badcmdcnt++ == conncfg.maxbadcmd) {
				executeAction(0, shared.smtprsp_nuisance, PROTO_EVENT.E_DISCONNECT);
			} else {
				transmit(shared.smtprsp_errproto);
			}
			break;

		case E_LOCALERROR:
			LEVEL lvl = LEVEL.TRC2;
			if (getLogger().isActive(lvl)) {
				shared.tmpsb.setLength(0);
				shared.tmpsb.append(pfx_log).append(" aborting connection on error - ").append(discmsg);
				getLogger().log(lvl, shared.tmpsb);
			}
			if (!isBrokenPipe()) transmit(shared.smtprsp_errlocal);
			issueDisconnect("Local Error - "+discmsg);
			break;

		default:
			// this is an internal bug whereby we're missing a case label
			getLogger().error(pfx_log+": Unrecognised event="+evt);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised event="+evt, false);
			break;
		}
		return pstate;
	}

	private void messageReceived(boolean rollback, String action, CharSequence errmsg) throws java.io.IOException
	{
		msgcnt++;
		shared.prototype_server.msgcnt++;
		CharSequence spid = (msgh == null ? shared.genDiscardMessageID() : shared.qmgr.externalSPID(msgh.spid));
		//construct log message before msgh gets released by concludeMessage()
		StringBuilder sb = shared.tmpsb;
		sb.setLength(0);
		sb.append(pfx_log).append(' ').append(action).append(" msg #").append(msgcnt).append('/').append(shared.prototype_server.msgcnt);
		sb.append(": msgid=").append(spid).append("/size=").append(msgsize_body);
		sb.append(" from ").append(msgsender.length() == 0 ? "<>" : msgsender);
		if (username != null) sb.append(" with Auth=").append(username);
		sb.append(" for recips=").append(rawrecips.size());
		if (rawrecips.size() != msgrecips.size()) sb.append("=>").append(msgrecips.size());
		if (getLogger().isActive(LEVEL.TRC)) {
			String dlm = " [";
			for (int idx = 0; idx != rawrecips.size(); idx++) {
				sb.append(dlm).append(rawrecips.get(idx));
				dlm = ", ";
			}
			if (isFlagSet(S2_RECIPSTRANSFORM) || rawrecips.size() != msgrecips.size()) {
				dlm = "] => [";
				for (int idx = 0; idx != msgrecips.size(); idx++) {
					sb.append(dlm).append(msgrecips.get(idx));
					dlm = ", ";
				}
			}
			sb.append(']');
		}
		sb.append(" - concurrent=").append(shared.current_conncnt).append('/').append(shared.peak_conncnt);

		// We've neither submitted the message locally nor responded yet, but we have finished receiving it, so log that now
		getLogger().log(LEVEL.INFO, sb);
		if (shared.transcript != null) {
			sb.setLength(0);
			sb.append("Received MessageBody octets=").append(msgsize_body);
			shared.transcript.event(pfx_transcript, sb, getSystemTime());
		}

		// submit the message to the queue
		if (!concludeMessage(rollback)) {
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed to submit msg="+spid+" - "+action, false);
			return;
		}

		if (rollback) {
			shared.tmpbcbuf.populate(errmsg);
		} else {
			shared.tmpbcbuf.populate(DFLTRSP_BODY).append(spid);
		}
		shared.tmpbcbuf.append(Protocol.EOL_BC);
		transmit(shared.tmpbcbuf);
	}

	// wait for next client command - the first to be issued over SSL
	@Override
	protected void startedSSL()
	{
		if (remote_helo != null) {
			shared.bcwell.store(remote_helo);
			remote_helo = null;
		}
		if (pstate == PROTO_STATE.S_DISCON) return; //we are about to close the connection
		if (shared.transcript != null) shared.transcript.event(pfx_transcript, "Switched to SSL mode", getSystemTime());
		transitionState(PROTO_STATE.S_HELO);
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

	// This method merely validates the act of issuing this Auth command at this time, as opposed to any
	// authentication credentials that may be conveyed.
	private boolean validateAuth(com.grey.base.sasl.SaslEntity.MECH auth) throws java.io.IOException
	{
		java.nio.ByteBuffer errmsg = null;
		if (msgcnt != 0 || username != null || !conncfg.authtypes_enabled.contains(auth)) {
			errmsg = shared.smtprsp_errproto;
		} else if (!usingSSL() && conncfg.authtypes_ssl.contains(auth)) {
			errmsg = shared.smtprsp_needssl;
		}

		if (errmsg != null) {
			transitionState(PROTO_STATE.S_IDLE);
			transmit(errmsg);
			return false;
		}
		return true;
	}

	private ADDR_STATUS handleAddress(PROTO_EVENT evt, com.grey.base.utils.EmailAddress addrinfo, ValidationSettings validation)
		throws java.io.IOException
	{
		dnsEvent = evt;
		dnsAddress = addrinfo;
		boolean validate = validation.validate;
		java.util.ArrayList<com.grey.base.utils.ByteChars> alias_members = null;

		if (addrinfo.full.length() == 0) {
			if (evt == PROTO_EVENT.E_MAILFROM) {
				// must be a delivery report - accept it
				return addressAccepted(null, null);
			}
			if (evt == PROTO_EVENT.E_MAILTO) validate = true; //blank address is always unacceptable

			if (validate) {
				// short-circuit the battery of checks below
				return addressRejected(com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.BADNAME);
			}
		}

		if (evt == PROTO_EVENT.E_MAILTO || evt == PROTO_EVENT.E_MAILFROM) {
			// Determine whether it's one of our served domains - if so, it's automatically valid.
			if (!shared.qmgr.verifyAddress(addrinfo.full)) {
				// whatever the other checks, we have to run email addresses by the Queue as well, and it didn't like this one
				return addressRejected(com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.BADNAME);
			}
			addrinfo.decompose();

			if (addrinfo.mailbox.length() == 0 || addrinfo.domain.length() == 0) {
				return addressRejected(com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.BADNAME);
			}
			boolean is_valid = false; //True means positively known to be valid, False merely means we don't know yet

			if (evt == PROTO_EVENT.E_MAILFROM) {
				boolean is_invalid = false; //True means positively known to be invalid
				if (conncfg.sender_permit != null) {
					//this is a conclusive test which rules on the sender's validity either way
					is_valid = conncfg.sender_permit.contains(addrinfo.domain);
					is_invalid = !is_valid;
				}
				if (!is_invalid && conncfg.sender_deny != null) is_invalid = conncfg.sender_deny.contains(addrinfo.domain);
				if (is_invalid) return addressRejected(null, shared.smtprsp_forged);
				if (!is_valid && shared.dtory != null && shared.dtory.isLocalDomain(addrinfo.domain)) is_valid = true;
			} else {
				if (shared.dtory != null) {
					com.grey.base.utils.ByteChars alias = shared.dtory.isLocalAlias(addrinfo);
					if (alias != null) {
						alias_members = shared.dtory.expandAlias(alias);
						if (alias_members.size() == 1 && alias_members.get(0).equalsIgnoreCase(com.grey.mailismus.directory.Directory.SINK_ALIAS)) {
							//report recip as ok, but we're silently discarding it
							setFlag(S2_RECIPSDISCARD);
							return addressHandled(ADDR_STATUS.OK, shared.smtprsp_ok, 0, null);
						}
						if (shared.localdelivery) {
							setFlag(S2_RECIPSTRANSFORM);
						} else {
							alias_members = null;
						}
						is_valid = true;
					} else if (shared.dtory.isLocalDomain(addrinfo.domain)) {
						//First we check if it looks like a response to a source-routed message we sent out.
						//If so, we're not going to relay it onwards (else we'd effectively become an open relay for
						//source-routed recips), so it's just a question of which local mailbox to drop it in, or
						//discarding it altogether.
						boolean srcrouted_bounce = (msgsender.length() == 0
								&& addrinfo.mailbox.indexOf((byte)com.grey.base.utils.EmailAddress.DLM_RT) != -1);
						if (srcrouted_bounce && shared.srcrouted_bounces_recip != null) {
							if (shared.srcrouted_bounces_recip.equals(".")) {
								//report recip as ok, but we're silently discarding it
								setFlag(S2_RECIPSDISCARD);
								return addressHandled(ADDR_STATUS.OK, shared.smtprsp_ok, 0, null);
							}
							if (shared.localdelivery) {
								//reroute it to specified local user - this might get rewritten again below
								addrinfo.set(shared.srcrouted_bounces_recip);
								addrinfo.decompose();
								setFlag(S2_RECIPSTRANSFORM);
							}
						}
						if (!shared.dtory.isLocalUser(addrinfo.mailbox)) {
							return addressRejected(com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.NODOMAIN);
						}
						if (shared.localdelivery) {
							addrinfo.stripDomain(); //reduce to local form (no domain part)
							setFlag(S2_RECIPSTRANSFORM);
						}
						is_valid = true;
					}
				}
			}

			// Even though destination relays are only used to route the recipient and source relays the recipient,
			// the fact that either party matches a relay means their domain is known to be valid.
			if (!is_valid) is_valid = shared.routing.hasRoute(addrinfo.domain);
			if (!is_valid) is_valid = (shared.routing.getSourceRoute(addrinfo, 0) != null);

			if (is_valid) {
				validate = false; //no need to do any DNS validation, already proven valid
			} else {
				if (evt == PROTO_EVENT.E_MAILTO) {
					// Quite apart from DNS validation, we need to check on relay permissions. The destination domain is not one of ours
					// so if the remote IP is not a permitted relay client, we reject this address regardless of DNS validity.
					// Unless client has authenticated, in which which case it is allowed to relay.
					if (username == null && !isRelayClient()) {
						return addressRejected(null, shared.smtprsp_relaydenied);
					}
				}
			}
		}

		if (!validate) {
			return addressAccepted(alias_members, addrinfo.domain);
		}
		return execDNS(validation, addrinfo);
	}

	// This can only fail due to Greylisting on MAILTO
	private ADDR_STATUS addressAccepted(java.util.ArrayList<com.grey.base.utils.ByteChars> alias_members,
			com.grey.base.utils.ByteChars alias_dompart) throws java.io.IOException
	{
		java.nio.ByteBuffer rsp;
		if (dnsEvent == PROTO_EVENT.E_EHLO) {
			if (!conncfg.omitrcvhdr) remote_helo = shared.bcwell.extract().populate(dnsAddress.full);
			rsp = (usingSSL() ? conncfg.smtprsp_ehlo_ssl : conncfg.smtprsp_ehlo);
		} else {
			if (dnsEvent == PROTO_EVENT.E_MAILTO) {
				if (shared.greylst != null) {
					if (!shared.greylst.vet(remote_tsap.ip, msgsender, dnsAddress.full)) {
						//temporary rejection - note that we don't increment shared.stats_rejrecips
						LEVEL lvl = LEVEL.TRC2;
						if (getLogger().isActive(lvl)) {
							shared.tmpsb.setLength(0);
							shared.tmpsb.append(pfx_log).append(" greylisted ").append(msgsender).append("=>").append(dnsAddress);
							getLogger().log(lvl, shared.tmpsb);
						}
						return addressHandled(ADDR_STATUS.REJECT, shared.smtprsp_greylisted, 0, null);
					}
				}
				if (alias_members == null) {
					if (!msgrecips.contains(dnsAddress)) {
						msgrecips.add(dnsAddress);
						sender_rewrites.add(null);
						dnsAddress = null;
					}
				} else {
					for (int idx = 0; idx != alias_members.size(); idx++) {
						com.grey.base.utils.ByteChars member = alias_members.get(idx);
						com.grey.base.utils.EmailAddress addr = shared.addrbufcache.extract().set(member);
						if (msgrecips.contains(addr)) {
							//discard duplicate
							shared.addrbufcache.store(addr);
						} else {
							boolean is_mbx = (addr.full.indexOf((byte)com.grey.base.utils.EmailAddress.DLM_DOM) == -1);
							if (is_mbx || !shared.spf_sender_rewrite
									|| (shared.dtory != null && shared.dtory.isLocalDomain(addr.domain))) {
								sender_rewrites.add(null);
							} else {
								// rewrite sender for this recip, to avoid SPF validation failure at next hop
								com.grey.base.utils.ByteChars altsender = shared.bcwell.extract().populate(msgsender);
								int pos = altsender.indexOf((byte)com.grey.base.utils.EmailAddress.DLM_DOM);
								if (pos != -1) altsender.setByte(pos, com.grey.base.utils.EmailAddress.DLM_RT);
								if (alias_dompart.length() != 0) {
									altsender.append(com.grey.base.utils.EmailAddress.DLM_DOM);
									altsender.append(alias_dompart);
								}
								sender_rewrites.add(altsender);
							}
							msgrecips.add(addr);
						}
					}
				}
			} else if (dnsEvent == PROTO_EVENT.E_MAILFROM) {
				msgsender = shared.bcwell.extract().populate(dnsAddress.full);
			} else {
				//PROTO_EVENT.E_HELO
				if (!conncfg.omitrcvhdr) remote_helo = shared.bcwell.extract().populate(dnsAddress.full);
			}
			rsp = shared.smtprsp_ok;
		}
		return addressHandled(ADDR_STATUS.OK, rsp, 0, null);
	}

	private ADDR_STATUS addressRejected(com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS status, java.nio.ByteBuffer rsp) throws java.io.IOException
	{
		LEVEL lvl = LEVEL.TRC;
		PROTO_EVENT evt = null;
		long delay;

		if (status != null && getLogger().isActive(lvl)) {
			StringBuilder sb = shared.tmpsb;
			sb.setLength(0);
			sb.append(pfx_log).append(" rejecting event ").append(dnsEvent);
			sb.append('=').append(dnsAddress).append(" with status=").append(status);
			if (dnsEvent == PROTO_EVENT.E_MAILTO) sb.append(" - sender=").append(msgsender);
			getLogger().log(lvl, sb);
		}

		if (dnsEvent == PROTO_EVENT.E_MAILTO) {
			//reject recipient, but remain in same state
			if (rsp == null) rsp = shared.smtprsp_badrecip;
			delay = conncfg.delay_badrecip;
			shared.stats_rejrecips++;
		} else {
			evt = PROTO_EVENT.E_DISCONNECT;
			if (rsp == null) rsp = (dnsEvent == PROTO_EVENT.E_MAILFROM ? shared.smtprsp_badsender : shared.smtprsp_badhello);
			delay = conncfg.delay_badcmd;
		}
		return addressHandled(ADDR_STATUS.REJECT, rsp, delay, evt);
	}

	private ADDR_STATUS addressRejected(com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS status) throws java.io.IOException
	{
		return addressRejected(status, null);
	}

	private ADDR_STATUS addressHandled(ADDR_STATUS status, java.nio.ByteBuffer rsp, long delay, PROTO_EVENT evt) throws java.io.IOException
	{
		if (dnsAddress != null) {
			shared.addrbufcache.store(dnsAddress);
			dnsAddress = null;
		}
		executeAction(delay, rsp, evt);
		return status;
	}

	private ADDR_STATUS execDNS(ValidationSettings validation, com.grey.base.utils.EmailAddress addr)
			throws java.io.IOException
	{
		com.grey.naf.dns.resolver.engine.ResolverAnswer answer;

		if (dnsEvent == PROTO_EVENT.E_HELO || dnsEvent == PROTO_EVENT.E_EHLO) {
			if (dnsPhase == ValidationSettings.HOSTDIR.BACKWARD) {
				answer = resolver.resolveIP(remote_tsap.ip, this, validation, validation.dnsflags);
			} else {
				answer = resolver.resolveHostname(addr.full, this, validation, validation.dnsflags);
			}
		} else {
			answer = resolver.resolveMailDomain(addr.domain, this, validation, validation.dnsflags);
		}

		if (answer == null) {
			setFlag(S2_DNSWAIT);
			return ADDR_STATUS.PENDING;
		}
		return evaluateDNS(answer, validation);
	}

	private ADDR_STATUS evaluateDNS(com.grey.naf.dns.resolver.engine.ResolverAnswer answer, ValidationSettings validation)
			throws java.io.IOException
	{
		boolean timeout = (answer.result == com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.TIMEOUT);
		com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS status = (timeout && validation.allowTimeout ? com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.OK : answer.result);

		if (status == com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.OK) {
			boolean rejected = false;
			if ((dnsEvent == PROTO_EVENT.E_HELO || dnsEvent == PROTO_EVENT.E_EHLO)
						&& !conncfg.validHELO.syntaxOnly
						&& !timeout) {
				if (dnsPhase == ValidationSettings.HOSTDIR.FORWARD) {
					// verify that IP address returned by DNS resolver matches the actual address at the other end of this connection
					if (remote_tsap.ip != answer.getA().getIP()) rejected = true;
				} else {
					// we were able to resolve the remote IP to a hostname
					if (!dnsAddress.full.equals(answer.getPTR().getName())) {
						rejected = true;
					} else if (conncfg.validHELO.direction == ValidationSettings.HOSTDIR.BOTH) {
						// now resolve the hostname to see if it matches the IP
						dnsPhase = ValidationSettings.HOSTDIR.FORWARD;
						return execDNS(validation, dnsAddress);
					}
				}
			}
			if (!rejected) return addressAccepted(null, null);
		}

		if (status == com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.ERROR || status == com.grey.naf.dns.resolver.engine.ResolverAnswer.STATUS.TIMEOUT) {
			// we have been unable to complete the required validation, through no fault of the remote SMTP client
			shared.tmpsb.setLength(0);
			shared.tmpsb.append("DNS failure=").append(status).append(" on ").append(dnsEvent).append('=').append(dnsAddress);
			shared.addrbufcache.store(dnsAddress);
			dnsAddress = null;
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, shared.tmpsb.toString(), false);
			return ADDR_STATUS.REJECT;
		}
		return addressRejected(status);
	}

	@Override
	public void dnsResolved(Dispatcher d, com.grey.naf.dns.resolver.engine.ResolverAnswer answer, Object cbdata)
	{
		thisEntered++;
		clearFlag(S2_DNSWAIT);
		ValidationSettings validation = (ValidationSettings)cbdata;
		try {
			evaluateDNS(answer, validation);
			exitThis();
		} catch (Throwable ex) {
			//broken pipe would already have been logged
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed on DNS response - "+answer);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed to process DNS response - "+com.grey.base.ExceptionUtils.summary(ex), true);
		}
	}

	public void messageFilterCompleted(FilterExecutor executor) {
		if (executor != msgfilter) return; //stale callback
		FilterExecutor f = msgfilter;
		msgfilter = null;
		String rejectrsp = f.getRejectResponse();
		PROTO_EVENT evt = PROTO_EVENT.E_ACCEPTMSG;
		if (rejectrsp != null) {
			evt = PROTO_EVENT.E_REJECTMSG;
			getLogger().info(pfx_log+" Message rejected by filter - "+rejectrsp);
		}
		tmr_filter.cancel();
		tmr_filter = null;
		try {
			thisEntered++;
			raiseEvent(evt, PROTO_STATE.S_IDLE, rejectrsp);
			exitThis();
		} catch (Exception ex) {
			if (!isBrokenPipe()) getLogger().log(LEVEL.ERR, ex, true, pfx_log+" failed on event="+evt);
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Failed on event="+evt+" - "+com.grey.base.ExceptionUtils.summary(ex), true);
		}
	}

	private boolean isRelayClient()
	{
		if (shared.relay_clients == null) return false;
		for (int idx = 0; idx != shared.relay_clients.length; idx++) {
			if (shared.relay_clients[idx].isMember(remote_tsap.ip)) {
				return true;
			}
		}
		return false;
	}

	private boolean matchesCommand(ByteArrayRef data, char[] cmd)
	{
		final int cmdlen = cmd.length;
		if (data.size() < cmdlen) return false;
		byte[] databuf = data.buffer();
		int off = data.offset();

		for (int idx = 0; idx != cmdlen; idx++) {
			if (Character.toUpperCase(databuf[off++]) != cmd[idx]) return false;
		}
		boolean sts = (cmd[cmdlen - 1] == ':');
		if (!sts) sts = ((data.size() == cmdlen) || (databuf[off] <= 32));

		if (sts) {
			data.advance(cmdlen);
			while (databuf[data.offset()] == ' ') data.advance(1);
			while (data.size() != 0 && databuf[data.limit() - 1] <= ' ') data.incrementSize(-1); //strip trailing white space
		}
		return sts;
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
			raiseSafeEvent(PROTO_EVENT.E_LOCALERROR, null, "Unrecognised SASL mechanism="+saslmech.mechanism, false);
			break;
		}
		saslmech = null;
	}

	static java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> configureAuthTypes(String cfgitem, XmlConfig cfg,
			java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> dflt, java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> full,
			String logpfx)
	{
		String dlm_tuple = "|";
		String dflt_txt = "";
		String dlm = "";
		if (dflt != null) {
			java.util.Iterator<com.grey.base.sasl.SaslEntity.MECH> it = dflt.iterator();
			while (it.hasNext()) {
				dflt_txt += dlm + it.next();
				dlm = dlm_tuple;
			}
		}
		String[] authtypes_cfg = cfg.getTuple(cfgitem, dlm_tuple, false, dflt_txt);
		if (authtypes_cfg == null) authtypes_cfg = new String[0]; //avoids aggravation below

		if (authtypes_cfg.length == 1 && authtypes_cfg[0].equalsIgnoreCase("all")) {
			if (full == null) {
				if (dflt == null) {
					dflt = new java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH>();
					dflt.addAll(java.util.Arrays.asList(com.grey.base.sasl.SaslEntity.MECH.values()));
				}
				full = dflt;
			}
			return full;
		}
		java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> authtypes = new java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH>();

		for (int idx = 0; idx != authtypes_cfg.length; idx++) {
			authtypes_cfg[idx] = authtypes_cfg[idx].toUpperCase();
			com.grey.base.sasl.SaslEntity.MECH authtype = null;
			try {
				authtype = com.grey.base.sasl.SaslEntity.MECH.valueOf(authtypes_cfg[idx]);
			} catch (java.lang.IllegalArgumentException ex) {
				throw new MailismusConfigException(logpfx+"Unsupported Authentication type="+authtypes_cfg[idx]
						+" - Supported="+java.util.Arrays.asList(com.grey.base.sasl.SaslEntity.MECH.values()));
			}
			// authtypes_ssl must be a subset of authtypes
			if (full == null || full.contains(authtype)) authtypes.add(authtype);
		}
		return authtypes;
	}

	// While this MTA is 8-bit clean and is perfectly prepared to relay 8-bit content exactly as is, we cannot guarantee that if we do
	// accept a message with BODY=8BITMIME (see RFC-1652), the next hop MTA will also be prepared to accept it in 8BITMIMEMODE. We make
	// no provision to track the body type and encode (Base64) it if necessary, therefore, we have to disable the 8BITMIME extension by
	// default.
	// If this MTA is being used in a closed environment where all its peers are known to support 8-bit transport, or else if the admin
	// wishes to take a flying leap of faith, then we do provide the option to enable 8BITMIME via config. The SMTP-Client will still
	// forward it as is, with no 7-bit encoding.
	static String buildResponseEHLO(ConnectionConfig conncfg, boolean sslmode, SharedFields shared)
	{
		StringBuilder extprefix = new StringBuilder();
		extprefix.append(Protocol.REPLYCODE_OK).append(Protocol.REPLY_CONTD);

		StringBuilder ehlorsp = new StringBuilder();
		ehlorsp.append(DFLTRSP_EHLO.replace(TOKEN_HOSTNAME, conncfg.announcehost==null? "" : conncfg.announcehost));
		int idx_lastline = 0;

		if (conncfg.ext_8bitmime) {
			idx_lastline = ehlorsp.length();
			ehlorsp.append(extprefix).append(Protocol.EXT_8BITMIME).append(Protocol.EOL);
		}

		if (conncfg.ext_pipeline) {
			idx_lastline = ehlorsp.length();
			ehlorsp.append(extprefix).append(Protocol.EXT_PIPELINE).append(Protocol.EOL);
		}

		if (!sslmode && conncfg.ext_stls) {
			idx_lastline = ehlorsp.length();
			ehlorsp.append(extprefix).append(Protocol.EXT_STLS).append(Protocol.EOL);
		}

		java.util.HashSet<com.grey.base.sasl.SaslEntity.MECH> excl = (sslmode ? null : conncfg.authtypes_ssl);
		if (conncfg.authtypes_enabled.size() != 0) {
			StringBuilder sb = new StringBuilder();
			java.util.Iterator<com.grey.base.sasl.SaslEntity.MECH> it = conncfg.authtypes_enabled.iterator();
			while (it.hasNext()) {
				com.grey.base.sasl.SaslEntity.MECH mech = it.next();
				if (excl != null && excl.contains(mech)) continue;
				sb.append(sb.length() == 0 ? "" : " ").append(mech.toString().replace('_', '-'));
			}
			if (sb.length() != 0) {
				idx_lastline = ehlorsp.length();
				ehlorsp.append(extprefix).append(Protocol.EXT_AUTH).append(' ').append(sb).append(Protocol.EOL);
				if (conncfg.auth_compat) {
					idx_lastline = ehlorsp.length();
					ehlorsp.append(extprefix).append(Protocol.EXT_AUTH_COMPAT).append(sb).append(Protocol.EOL);
				}
			}
		}

		if (conncfg.ext_size && conncfg.max_msgsize != 0) {
			idx_lastline = ehlorsp.length();
			ehlorsp.append(extprefix).append(Protocol.EXT_SIZE).append(' ').append(conncfg.max_msgsize).append(Protocol.EOL);
		}
		ehlorsp.setCharAt(idx_lastline + Protocol.REPLY_CODELEN, ' ');
		return ehlorsp.toString();
	}

	static com.grey.base.utils.IP.Subnet[] parseSubnets(String fldnam, XmlConfig cfg, AppConfig appcfg)
			throws java.net.UnknownHostException
	{
		String[] arr = cfg.getTuple(fldnam, "|", false, null);
		if (arr == null) return null;
		java.util.ArrayList<com.grey.base.utils.IP.Subnet> lst = new java.util.ArrayList<com.grey.base.utils.IP.Subnet>();
		for (int idx = 0; idx != arr.length; idx++) {
			String val = arr[idx].trim();
			if (val.length() == 0) continue;
			val = appcfg.parseHost(null, null, false, val);
			lst.add(com.grey.base.utils.IP.parseSubnet(val));
		}
		if (lst.size() == 0) return null;
		return lst.toArray(new com.grey.base.utils.IP.Subnet[lst.size()]);
	}

	static com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars>
		buildSet(XmlConfig cfg, com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars> dflt, String item)
	{
		String[] list = cfg.getTuple(item, "|", false, null);
		if (list == null) return dflt;
		if (list.length==1 && list[0].trim().equals(".")) return null;

		com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars> map
						= new com.grey.base.collections.HashedSet<com.grey.base.utils.ByteChars>(0);
		for (int idx = 0; idx != list.length; idx++) {
			String str = list[idx].trim().toLowerCase();
			if (str.length() == 0) continue;
			com.grey.base.utils.ByteChars bc = new com.grey.base.utils.ByteChars(str);
			map.add(bc);
		}
		if (map.size() == 0) map = dflt;
		return map;
	}

	@Override
	public StringBuilder dumpAppState(StringBuilder sb)
	{
		if (sb == null) sb = new StringBuilder();
		sb.append(pfx_log).append('/').append(pstate).append("/0x").append(Integer.toHexString(state2));
		if (username != null) sb.append("/auth=").append(username);
		sb.append(": ").append(" msgcnt=").append(msgcnt);
		if (badcmdcnt != 0) sb.append("; ").append(" badcmds=").append(badcmdcnt);
		return sb;
	}

	@Override
	public String toString() {
		return getClass().getName()+"=E"+getCMID();
	}
}

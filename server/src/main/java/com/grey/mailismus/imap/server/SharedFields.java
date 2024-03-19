/*
 * Copyright 2013-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.base.utils.TimeOps;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.imap.IMAP4Protocol;
import com.grey.mailismus.imap.server.Defs.EnvelopeHeader;
import com.grey.mailismus.imap.server.Defs.FetchOpDef;
import com.grey.naf.BufferGenerator;
import com.grey.mailismus.imap.server.Defs.FetchOp;

/*
 * There is a single instance of this class per prototype IMAP4Server object, and since all IMAP4Server instances
 * created from that prototype run in the same thread, they can all share the objects in here without contention - so long
 * as they don't require the state to persist NAF callbacks.
 * The server config associated with the prototype object is also performed in here.
 */
final class SharedFields
{
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

	final com.grey.mailismus.ms.maildir.MaildirStore ms;
	final com.grey.mailismus.directory.Directory dtory;
	final SaslAuthenticator saslauth;
	final MessageFlags msgFlags;
	final java.util.HashMap<String, FetchOpDef> fopdefs = new java.util.HashMap<String, FetchOpDef>();
	final java.util.HashMap<String, FetchOp> fopImmutable = new java.util.HashMap<String, FetchOp>();
	final java.util.Calendar dtcal = TimeOps.getCalendar(null);
	final com.grey.mailismus.Transcript transcript;
	final boolean full_transcript;
	final com.grey.naf.BufferGenerator bufspec;
	final java.io.File stagingDir;
	final long tmtprotocol;
	final long tmtauth;
	final long interval_newmail;
	final boolean capa_idle;
	final int batchsize_nodisk;
	final int batchsize_renames;
	final int batchsize_fileio;
	final int maximapbuf;
	final long delay_chanclose; //has solved abort-on-close issues in the past
	final IMAP4Server prototype_server;

	final java.util.HashSet<IMAP4Protocol.AUTHTYPE> authtypes_enabled;
	final java.util.HashSet<IMAP4Protocol.AUTHTYPE> authtypes_ssl; //only allowed in SSL mode
	final com.grey.base.collections.ObjectWell<com.grey.base.sasl.PlainServer> saslmechs_plain;
	final com.grey.base.collections.ObjectWell<com.grey.base.sasl.CramMD5Server> saslmechs_cmd5;
	final com.grey.base.collections.ObjectWell<com.grey.base.sasl.ExternalServer> saslmechs_ext;

	// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned replies
	final java.nio.ByteBuffer imap4rsp_greet;
	final java.nio.ByteBuffer imap4rsp_emptychallenge;
	final java.nio.ByteBuffer imap4rsp_bye_timeout;
	final java.nio.ByteBuffer imap4rsp_contd_ready;

	// see ABNF 'envelope' def in RFC-3501, section 9
	final EnvelopeHeader[] envHeaders = new EnvelopeHeader[]{new EnvelopeHeader("Date"),
			new EnvelopeHeader("Subject", EnvelopeHeader.F_ESC),
			new EnvelopeHeader("From", EnvelopeHeader.F_ADDR),
			new EnvelopeHeader("Sender", "From", EnvelopeHeader.F_ADDR),
			new EnvelopeHeader("Reply-To", "From", EnvelopeHeader.F_ADDR),
			new EnvelopeHeader("To", EnvelopeHeader.F_ADDR),
			new EnvelopeHeader("Cc", EnvelopeHeader.F_ADDR),
			new EnvelopeHeader("Bcc", EnvelopeHeader.F_ADDR),
			new EnvelopeHeader("In-Reply-To", EnvelopeHeader.F_ANGBRACE),
			new EnvelopeHeader("Message-Id", EnvelopeHeader.F_ANGBRACE)};
	final String[] envHeaderNames;

	// temp work areas, pre-allocated for efficiency
	final com.grey.base.utils.TSAP tmptsap = new com.grey.base.utils.TSAP();
	final StringBuilder tmpsb = new StringBuilder();
	final StringBuilder tmpsb2 = new StringBuilder();
	final com.grey.base.utils.ByteChars tmpbc = new com.grey.base.utils.ByteChars();
	final com.grey.base.utils.ByteChars tmpbc2 = new com.grey.base.utils.ByteChars();
	final com.grey.base.utils.ByteChars tmplightbc = new com.grey.base.utils.ByteChars(-1); //lightweight object without own storage
	final com.grey.base.utils.ByteChars tmplightbc2 = new com.grey.base.utils.ByteChars(-1);
	final com.grey.base.utils.ByteChars tmplightbc3 = new com.grey.base.utils.ByteChars(-1);
	final com.grey.base.utils.ByteChars tmplightbc4 = new com.grey.base.utils.ByteChars(-1);
	final com.grey.base.utils.ByteChars xmtbuf = new com.grey.base.utils.ByteChars(); //only for use within transmit()
	final com.grey.base.utils.ByteChars xmtbuflight = new com.grey.base.utils.ByteChars(-1); //only for use within transmit()
	final com.grey.base.collections.NumberList tmpnumlst = new com.grey.base.collections.NumberList();
	final com.grey.base.collections.HashedMap<String,String> tmpstrmap = new com.grey.base.collections.HashedMap<String,String>();
	java.nio.ByteBuffer tmpniobuf;

	public SharedFields(com.grey.base.config.XmlConfig cfg, com.grey.naf.reactor.Dispatcher dsptch, com.grey.mailismus.Task task,
			IMAP4Server proto, com.grey.naf.reactor.config.SSLConfig sslcfg, String logpfx)
		throws java.io.IOException
	{
		if (task.getMS() == null || task.getDirectory() == null) {
			throw new MailismusConfigException(logpfx+"MessageStore and Directory must be configured");
		}
		ms = (com.grey.mailismus.ms.maildir.MaildirStore)task.getMS();
		dtory = task.getDirectory();
		saslauth = new SaslAuthenticator(dtory);
		prototype_server = proto;
		tmtprotocol = cfg.getTime("timeout", TimeOps.parseMilliTime("30m")); //see RFC-3501 section 5.4
		tmtauth = cfg.getTime("authtimeout", TimeOps.parseMilliTime("1m"));
		capa_idle = cfg.getBool("capa_idle", true);
		delay_chanclose = cfg.getTime("delay_close", 0);
		maximapbuf = cfg.getInt("maxtransmitbuf", true, 8 * 1024);
		bufspec = BufferGenerator.create(cfg, "niobuffers", 16 * 1024, 16 * 1024);
		transcript = com.grey.mailismus.Transcript.create(dsptch, cfg, "transcript");
		full_transcript = cfg.getBool("transcript/@full", false);

		int bs_nodisk = cfg.getInt("batchsize_nodisk", true, 1000);
		int bs_renames = cfg.getInt("batchsize_renames", true, 50);
		int bs_fileio = cfg.getInt("batchsize_fileio", true, 10);
		if (bs_renames >= bs_nodisk) bs_renames = bs_nodisk - 1;
		if (bs_fileio > bs_renames) bs_fileio = bs_renames;
		batchsize_nodisk = (bs_nodisk <= 0 ? 1 : bs_nodisk);
		batchsize_renames = (bs_renames <= 0 ? 1 : bs_renames);
		batchsize_fileio = (bs_fileio <= 0 ? 1 : bs_fileio);

		long minval = 15 * TimeOps.MSECS_PER_SECOND;
		long timeval = cfg.getTime("newmailfreq", TimeOps.parseMilliTime("20s"));
		interval_newmail = Math.max(timeval, minval);

		String pthnam_msgflags = cfg.getValue("keywords_map", true, dsptch.getApplicationContext().getNafConfig().getPathVar()+"/imap/imapkeywords");
		boolean dynkwords = cfg.getBool("keywords_dyn", true);
		msgFlags = new MessageFlags(pthnam_msgflags, dynkwords);

		fopdefs.put("UID", new FetchOpDef(FetchOpDef.OPCODE.UID, 0, batchsize_nodisk, null));
		fopdefs.put("RFC822.SIZE", new FetchOpDef(FetchOpDef.OPCODE.SIZE, 0, batchsize_nodisk, null));
		fopdefs.put("INTERNALDATE", new FetchOpDef(FetchOpDef.OPCODE.TIMESTAMP, 0, batchsize_nodisk, Defs.PARENTH_QUOTE));
		fopdefs.put("FLAGS", new FetchOpDef(FetchOpDef.OPCODE.FLAGS, 0, batchsize_nodisk, "()"));
		fopdefs.put("ENVELOPE", new FetchOpDef(FetchOpDef.OPCODE.ENVELOPE, 0, batchsize_fileio, "()"));
		fopdefs.put("BODY", new FetchOpDef(FetchOpDef.OPCODE.BODY, 0, batchsize_fileio, null));
		fopdefs.put("BODYSTRUCTURE", new FetchOpDef(FetchOpDef.OPCODE.BODYSTRUCTURE, 0, batchsize_fileio, null));
		fopdefs.put("BODY[]", new FetchOpDef(FetchOpDef.OPCODE.ALL, FetchOpDef.F_RDWR | FetchOpDef.F_HASMIME, batchsize_fileio, null));
		fopdefs.put("RFC822", new FetchOpDef(FetchOpDef.OPCODE.ALL, FetchOpDef.F_RDWR, batchsize_fileio, null));
		fopdefs.put("BODY[TEXT]", new FetchOpDef(FetchOpDef.OPCODE.TEXT, FetchOpDef.F_RDWR | FetchOpDef.F_HASMIME, batchsize_fileio, null));
		fopdefs.put("RFC822.TEXT", new FetchOpDef(FetchOpDef.OPCODE.TEXT, FetchOpDef.F_RDWR, batchsize_fileio, null));
		fopdefs.put("BODY[HEADER]", new FetchOpDef(FetchOpDef.OPCODE.HEADERS, FetchOpDef.F_RDWR | FetchOpDef.F_HASMIME, batchsize_fileio, null));
		fopdefs.put("RFC822.HEADER", new FetchOpDef(FetchOpDef.OPCODE.HEADERS, 0, batchsize_fileio, null));
		fopdefs.put("BODY[HEADER.FIELDS]", new FetchOpDef(FetchOpDef.OPCODE.HEADERS,
				FetchOpDef.F_RDWR | FetchOpDef.F_HASMIME | FetchOpDef.F_HASFLDS, batchsize_fileio, null));
		fopdefs.put("BODY[HEADER.FIELDS.NOT]", new FetchOpDef(FetchOpDef.OPCODE.HEADERS,
				FetchOpDef.F_RDWR | FetchOpDef.F_HASMIME | FetchOpDef.F_HASFLDS | FetchOpDef.F_EXCL,
				batchsize_fileio, null));
		fopdefs.put("BODY[MIME]", new FetchOpDef(FetchOpDef.OPCODE.MIME,
				FetchOpDef.F_RDWR | FetchOpDef.F_HASMIME | FetchOpDef.F_MDTYMIME, batchsize_fileio, null));

		// predefine instances of the FetchOps that have no variable fields
		String[] opnames = new String[]{"UID", "RFC822.SIZE", "INTERNALDATE", "FLAGS"};
		for (int idx = 0; idx != opnames.length; idx++) {
			FetchOpDef def = fopdefs.get(opnames[idx]);
			FetchOp op = new FetchOp(def, opnames[idx], false);
			fopImmutable.put(opnames[idx], op);
		}

		// extract envelope header names into a simple array, which we can pass to Mailbox.getHeaders()
		envHeaderNames = new String[envHeaders.length];
		for (int idx = 0; idx != envHeaders.length; idx++) {
			envHeaderNames[idx] = envHeaders[idx].hdrname;
		}

		java.util.ArrayList<IMAP4Protocol.AUTHTYPE> atypeslst = new java.util.ArrayList<IMAP4Protocol.AUTHTYPE>(java.util.Arrays.asList(IMAP4Protocol.AUTHTYPE.values()));
		if (!dtory.supportsPasswordLookup()) atypeslst.remove(IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5);
		authtypes_enabled = configureAuthTypes("authtypes", cfg, true, atypeslst, null, dtory.supportsPasswordLookup(), logpfx);
		if (sslcfg == null || (sslcfg.isLatent() && !sslcfg.isMandatory())) {
			if (sslcfg == null) {
				atypeslst.remove(IMAP4Protocol.AUTHTYPE.LOGIN);
				atypeslst.remove(IMAP4Protocol.AUTHTYPE.SASL_PLAIN);
			}
			atypeslst.remove(IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5);
			authtypes_ssl = configureAuthTypes("authtypes_ssl", cfg, false, atypeslst, authtypes_enabled, dtory.supportsPasswordLookup(), logpfx);
		} else {
			authtypes_ssl = authtypes_enabled;
		}

		if (authtypes_enabled.contains(IMAP4Protocol.AUTHTYPE.SASL_PLAIN)) {
			com.grey.base.sasl.SaslServerFactory fact = new com.grey.base.sasl.SaslServerFactory(com.grey.base.sasl.SaslEntity.MECH.PLAIN, saslauth, true);
			saslmechs_plain = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.PlainServer>(null, fact, "IMAP_Plain", 0, 0, 1);
		} else {
			saslmechs_plain = null;
		}
		if (authtypes_enabled.contains(IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5)) {
			com.grey.base.sasl.SaslServerFactory fact = new com.grey.base.sasl.SaslServerFactory(com.grey.base.sasl.SaslEntity.MECH.CRAM_MD5, saslauth, true);
			saslmechs_cmd5 = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.CramMD5Server>(null, fact, "IMAP_CramMD5", 0, 0, 1);
		} else {
			saslmechs_cmd5 = null;
		}
		if (authtypes_enabled.contains(IMAP4Protocol.AUTHTYPE.SASL_EXTERNAL)) {
			com.grey.base.sasl.SaslServerFactory fact = new com.grey.base.sasl.SaslServerFactory(com.grey.base.sasl.SaslEntity.MECH.EXTERNAL, saslauth, true);
			saslmechs_ext = new com.grey.base.collections.ObjectWell<com.grey.base.sasl.ExternalServer>(null, fact, "IMAP_External", 0, 0, 1);
		} else {
			saslmechs_ext = null;
		}

		String greetmsg = cfg.getValue("greet", true, Defs.RSPMSG_GREET).replace(Defs.TOKEN_HOSTNAME, task.getAppConfig().getAnnounceHost());
		greetmsg = IMAP4Protocol.STATUS_UNTAGGED+IMAP4Protocol.STATUS_OK+" "+greetmsg+IMAP4Protocol.EOL;
		imap4rsp_greet = com.grey.mailismus.Task.constBuffer(greetmsg);

		imap4rsp_emptychallenge = com.grey.mailismus.Task.constBuffer(IMAP4Protocol.STATUS_CONTD+IMAP4Protocol.EOL);
		imap4rsp_bye_timeout = com.grey.mailismus.Task.constBuffer(IMAP4Protocol.STATUS_UNTAGGED+IMAP4Protocol.STATUS_BYE+" idle timeout"+IMAP4Protocol.EOL);
		imap4rsp_contd_ready = com.grey.mailismus.Task.constBuffer(IMAP4Protocol.STATUS_CONTD+"Ready"+IMAP4Protocol.EOL);

		String pthnam = dsptch.getApplicationContext().getNafConfig().getPathTemp()+"/imap4server";
		stagingDir = new java.io.File(pthnam);
	}

	private static java.util.HashSet<IMAP4Protocol.AUTHTYPE> configureAuthTypes(String cfgitem, com.grey.base.config.XmlConfig cfg, boolean mdty,
			java.util.List<IMAP4Protocol.AUTHTYPE> dflt, java.util.HashSet<IMAP4Protocol.AUTHTYPE> full,
			boolean supportsPasswordLookup, String logpfx)
	{
		java.util.HashSet<IMAP4Protocol.AUTHTYPE> authtypes = new java.util.HashSet<IMAP4Protocol.AUTHTYPE>();
		String dlm = "|";
		String txt = "";
		if (dflt != null) {
			for (int idx = 0; idx != dflt.size(); idx++) {
				txt += (idx==0?"":dlm) + dflt.get(idx);
			}
		}
		String[] authtypes_cfg = cfg.getTuple(cfgitem, dlm, mdty, txt);
		if (authtypes_cfg == null) authtypes_cfg = new String[0]; //avoids aggravation below

		if (authtypes_cfg.length == 1 && authtypes_cfg[0].equalsIgnoreCase("all")) {
			if (full == null) full = new java.util.HashSet<IMAP4Protocol.AUTHTYPE>(dflt);
			return full;
		}

		for (int idx = 0; idx != authtypes_cfg.length; idx++) {
			authtypes_cfg[idx] = authtypes_cfg[idx].toUpperCase();
			IMAP4Protocol.AUTHTYPE authtype = null;
			try {
				authtype = IMAP4Protocol.AUTHTYPE.valueOf(authtypes_cfg[idx]);
			} catch (java.lang.IllegalArgumentException ex) {
				throw new MailismusConfigException(logpfx+"Unsupported Authentication type="+authtypes_cfg[idx]
						+" - Supported="+java.util.Arrays.asList(IMAP4Protocol.AUTHTYPE.values()));
			}
			// authtypes_ssl must be a subset of authtypes
			if (authtype == IMAP4Protocol.AUTHTYPE.SASL_CRAM_MD5 && !supportsPasswordLookup) continue;
			if (full == null || full.contains(authtype)) authtypes.add(authtype);
		}
		return authtypes;
	}
}
/*
 * Copyright 2013-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.TSAP;
import com.grey.base.utils.TimeOps;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.TestSupport;
import com.grey.mailismus.imap.IMAP4Protocol;

public class IMAP4ServerTest
{
	static {
		com.grey.mailismus.TestSupport.initPaths(IMAP4ServerTest.class);
	}
	// NB: any certificate files referenced in this test's config are copied from NAF's SSLConnectionTest
	private static final String appcfg_path = "cp:com/grey/mailismus/imap4/conf.xml";
	private static final String nafxml_server = "<x><configfile root=\"mailserver/imap/server\">"+appcfg_path+"</configfile></x>";
	private static final String username = "user1";
	private static final String userpass = "pass1";

	private static final String FOLDER1 = "folder1";
	private static final String FOLDER1B = FOLDER1+".child1";

	private static final String MBXFLAGS_STD = "\\Draft \\Flagged \\Answered \\Seen \\Deleted";

	private static final String UNTAG_NOCHECK = "_JUNIT_NOASSERT_"; //untagged response is present, but not to be checked

	private static final ApplicationContextNAF appctx = TestSupport.createApplicationContext("IMAP4ServerTest", true);
	private Dispatcher dsptch;
	private IMAP4Task srvtask;
	private TSAP srvaddr;
	private java.net.Socket sock;
	private java.io.OutputStreamWriter ocstrm;
	private java.io.BufferedReader ibstrm;
	private String reqtag;
	private int reqnum;

	@org.junit.Test
	public void testSuite() throws Exception
	{
		startServer();
		// do some tests which create independent connections
		testCapability(false, "IMAP4rev1 IDLE NAMESPACE CHILDREN UNSELECT LITERAL+ AUTH=PLAIN AUTH=CRAM-MD5");
		testLoginStd(false);
		testLoginSaslPlain(false);
		testLoginSaslPlain(true);
		testLoginSaslCramMD5();
		testLoginSaslExternal(false);
		testLoginSaslExternal(true);
		// run these tests within a single connection
		testLoginStd(true);
		testCapability(true, "IMAP4rev1 IDLE NAMESPACE CHILDREN UNSELECT LITERAL+");
		testFolders();
		testMessages();
		testMIME();
		testOddMessages();
		disconnect();
		stopServer();
	}

	private void testCapability(boolean connected, String exp) throws java.io.IOException
	{
		if (!connected) connect();
		issueCommand(IMAP4Protocol.CMDREQ_CAPA, null, exp);
		if (!connected) disconnect();
	}

	private void testLoginStd(boolean stay_logged_on) throws java.io.IOException
	{
		connect();
		if (!stay_logged_on) {
			//test bad password first
			sendRequest(IMAP4Protocol.CMDREQ_LOGIN+" "+username+" x"+userpass);
			getResponseRej(null, null);
		}
		issueCommand(IMAP4Protocol.CMDREQ_LOGIN, username+" "+userpass, null);
		if (!stay_logged_on) disconnect();
	}

	private void testLoginSaslPlain(boolean initrsp) throws java.io.IOException
	{
		com.grey.base.sasl.PlainClient sasl = new com.grey.base.sasl.PlainClient(true);
		sasl.init();
		ByteChars sendbuf = new ByteChars(IMAP4Protocol.CMDREQ_AUTH).append(' ').append(com.grey.base.sasl.SaslEntity.MECHNAME_PLAIN);
		if (initrsp) {
			sendbuf.append(' ');
			sasl.setResponse(null, username, userpass, sendbuf);
		}
		connect();
		sendRequest(sendbuf);

		if (!initrsp) {
			getResponseContd();
			sasl.setResponse(null, username, userpass, sendbuf.clear());
			sendLine(sendbuf);
		}
		getResponseOK(null, null);
		disconnect();
	}

	private void testLoginSaslCramMD5() throws java.io.IOException, java.security.NoSuchAlgorithmException
	{
		com.grey.base.sasl.CramMD5Client sasl = new com.grey.base.sasl.CramMD5Client(true);
		sasl.init();
		ByteChars sendbuf = new ByteChars(IMAP4Protocol.CMDREQ_AUTH).append(' ').append(com.grey.base.sasl.SaslEntity.MECHNAME_CMD5);
		connect();
		sendRequest(sendbuf);
		String challenge = getResponseContd();
		sasl.setResponse(username, new ByteChars(userpass), new ByteChars(challenge), sendbuf.clear());
		sendLine(sendbuf);
		getResponseOK(null, null);
		disconnect();
	}

	// This will fail as this is not an SSL connection - and server is not even SSL-enabled
	private void testLoginSaslExternal(boolean initrsp) throws java.io.IOException
	{
		com.grey.base.sasl.ExternalClient sasl = new com.grey.base.sasl.ExternalClient(true);
		sasl.init();
		ByteChars sendbuf = new ByteChars(IMAP4Protocol.CMDREQ_AUTH).append(' ').append(com.grey.base.sasl.SaslEntity.MECHNAME_EXTERNAL);
		if (initrsp) sendbuf.append(' ').append(IMAP4Protocol.AUTH_EMPTY);
		connect();
		sendRequest(sendbuf);
		getResponseRej(null, null);
		disconnect();
	}

	private void testFolders() throws java.io.IOException
	{
		String listrsp_inbox = "(\\HasNoChildren) \".\" \"INBOX\"";
		String newfolder1 = FOLDER1;
		String newfolder2 = "."+FOLDER1B;
		issueCommand(IMAP4Protocol.CMDREQ_NAMSPC, null, "((\"\" \".\")) NIL NIL");
		issueCommand(IMAP4Protocol.CMDREQ_LIST, null, "(\\Noselect) \".\" \"\"");
		issueCommand(IMAP4Protocol.CMDREQ_LIST, "\"\" *", listrsp_inbox);

		issueCommand(IMAP4Protocol.CMDREQ_CREATE, newfolder1, null);
		sendRequest(IMAP4Protocol.CMDREQ_LIST+" \"\" *");
		getUntaggedResponse(IMAP4Protocol.CMDREQ_LIST, "(\\HasNoChildren) \".\" \""+newfolder1+"\"");
		getUntaggedResponse(IMAP4Protocol.CMDREQ_LIST, listrsp_inbox);
		getResponseOK(null, null);

		issueCommand(IMAP4Protocol.CMDREQ_CREATE, newfolder2, null);
		sendRequest(IMAP4Protocol.CMDREQ_LIST+" \"\" *");
		getUntaggedResponse(IMAP4Protocol.CMDREQ_LIST, "(\\HasChildren) \".\" \""+newfolder1+"\"");
		getUntaggedResponse(IMAP4Protocol.CMDREQ_LIST, "(\\HasNoChildren) \".\" \""+newfolder2.substring(1)+"\"");
		getUntaggedResponse(IMAP4Protocol.CMDREQ_LIST, listrsp_inbox);
		getResponseOK(null, null);

		sendRequest(IMAP4Protocol.CMDREQ_CREATE+" "+newfolder2);
		getResponseBad(null, null); //because already exists
	}

	private void testMessages() throws java.io.IOException
	{
		createMessage("nosuchfoldername", null, null, false, -1, -1, null, true);
		createMessage(FOLDER1, null, null, false, -1, -1, null, false);
		sendRequest(IMAP4Protocol.CMDREQ_CHECK);
		getResponseBad(null, null); //cannot do this in non-Selected state

		String uidgen_inbox = openFolder("InBox", false, 0, 0, 0, 1, null, null);

		//verify that Status sees the pending new message in this non-open folder
		String rsp = issueCommand(IMAP4Protocol.CMDREQ_STATUS, FOLDER1+" (MESSAGES RECENT UNSEEN UIDNEXT UIDVALIDITY)", UNTAG_NOCHECK);
		assertResponseStart(rsp, "folder1 (MESSAGES 1 RECENT 1 UNSEEN 1 UIDNEXT 1 UIDVALIDITY ");

		issueCommand(IMAP4Protocol.CMDREQ_CHECK, null, null);
		int subject1_id = reqnum;
		createMessage("INBox", "\\flaGGed $laBel1", "$laBel1", true, 1, 1, null, false);
		createMessage("INBox", null, null, false, 2, 2, null, false);
		issueCommand(IMAP4Protocol.CMDREQ_NOOP, null, null);

		sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 2,1,2,1:*,1 (uid flags rfc822.size)");
		getUntaggedResponse(null, "1 FETCH (UID 1 FLAGS (\\Recent \\Flagged $laBel1) RFC822.SIZE 132)");
		getUntaggedResponse(null, "2 FETCH (UID 2 FLAGS (\\Recent) RFC822.SIZE 132)");
		getResponseOK(null, null);

		// modify flags, with and without untagged updates
		sendRequest(IMAP4Protocol.CMDREQ_STORE+" 1 +flags ("+IMAP4Protocol.MSGFLAG_DRAFT+")");
		getUntaggedResponse(null, "1 FETCH (FLAGS (\\Flagged $laBel1 \\Draft \\Recent))");
		getResponseOK(null, null);
		issueCommand(IMAP4Protocol.CMDREQ_STORE, "2 flags.silent ("+IMAP4Protocol.MSGFLAG_DEL+")", null);

		// read back the modified flags
		sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 1 (uid flags rfc822.size)");
		getUntaggedResponse(null, "1 FETCH (UID 1 FLAGS (\\Recent \\Flagged $laBel1 \\Draft) RFC822.SIZE 132)");
		getResponseOK(null, null);
		sendRequest(IMAP4Protocol.CMDREQ_UID+" "+IMAP4Protocol.CMDREQ_FETCH+" 2 (flags rfc822.size)");
		getUntaggedResponse(null, "2 FETCH (FLAGS (\\Recent \\Deleted) RFC822.SIZE 132 UID 2)");
		getResponseOK(null, null);

		// now test some of the more complicated Fetches
		String hdr_from = "((NIL NIL \"sender1\" NIL))";
		String hdr_to = "((NIL NIL \"recip2\" \"domainA.com\"))";
		String hdr_cc = "((\"Mister Nosy\" NIL \"recip3\" \"domainB.com\"))";
		String exp = "NIL \"Topic "+subject1_id+"\" "+hdr_from+" "+hdr_from+" "+hdr_from+" "+hdr_to+" "+hdr_cc+" NIL NIL NIL";
		sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 1 envelope");
		getUntaggedResponse(null, "1 FETCH (ENVELOPE ("+exp+"))");
		getResponseOK(null, null);

		exp = "\"text\" \"plain\" (\"charset\" \"US-ASCII\") NIL NIL \"7bit\" 34 2 NIL NIL NIL";
		sendRequest(IMAP4Protocol.CMDREQ_UID+" "+IMAP4Protocol.CMDREQ_FETCH+" * bodystructure");
		getUntaggedResponse(null, "2 FETCH (BODYSTRUCTURE ("+exp+") UID 2)");
		getResponseOK(null, null);

		sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 1 BODY.Peek[header.fields (suBject)]");
		getUntaggedResponse(null, "1 FETCH (BODY[HEADER.FIELDS (SUBJECT)] {21}");
		getLine("Subject: Topic "+subject1_id);
		getLine("");
		getLine(")");
		getResponseOK(null, null);

		sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 1 BODY[header.fields.not (suBject cc)]");
		getUntaggedResponse(null, "1 FETCH (BODY[HEADER.FIELDS.NOT (SUBJECT CC)] {41}");
		getLine("From: sender1");
		getLine("To: recip2@domainA.com");
		getLine("");
		getLine(")");
		getUntaggedResponse(null, "1 FETCH (FLAGS (\\Recent \\Flagged $laBel1 \\Draft \\Seen))");
		getResponseOK(null, null);

		sendRequest(IMAP4Protocol.CMDREQ_SRCH+" 1:* uid 1:* deleted unanswered larger 1");
		getUntaggedResponse(IMAP4Protocol.CMDREQ_SRCH, "2");
		getResponseOK(null, null);

		// verify that the pending new message is loaded on Select
		String uidgen_folder1 = openFolder(FOLDER1, false, 1, 1, 1, 2, null, "$laBel1");

		// do status on Inbox to make sure the implicit close above expunged the message we marked as Deleted
		exp = "Inbox (MESSAGES 1 RECENT 0 UNSEEN 0 UIDNEXT 3 UIDVALIDITY "+uidgen_inbox+")";
		issueCommand(IMAP4Protocol.CMDREQ_STATUS, "Inbox (MESSAGES RECENT UNSEEN UIDNEXT UIDVALIDITY)", exp);

		//do status on self to make sure it doesn't break anything
		exp = "folder1 (MESSAGES 1 RECENT 1 UNSEEN 1 UIDNEXT 2 UIDVALIDITY "+uidgen_folder1+")";
		issueCommand(IMAP4Protocol.CMDREQ_STATUS, FOLDER1+" (MESSAGES RECENT UNSEEN UIDNEXT UIDVALIDITY)", exp);

		issueCommand(IMAP4Protocol.CMDREQ_CLOSE, null, null);
	}

	private void testMIME() throws java.io.IOException, java.net.URISyntaxException
	{
		java.net.URL url = DynLoader.getResource("/com/grey/mailismus/imap4/messages/nested.1.msg", getClass());
		java.io.File fh = new java.io.File(url.toURI());
		createMessage(FOLDER1B, null, null, false, -1, -1, fh, false);
		url = DynLoader.getResource("/com/grey/mailismus/imap4/messages/nested.bs", getClass());
		fh = new java.io.File(url.toURI());
		String bs_simple = FileOps.readAsText(fh, null).trim();

		url = DynLoader.getResource("/com/grey/mailismus/imap4/messages/nested_with_attach.2.msg", getClass());
		fh = new java.io.File(url.toURI());
		createMessage(FOLDER1B, null, null, false, -1, -1, fh, false);
		url = DynLoader.getResource("/com/grey/mailismus/imap4/messages/nested_with_attach.bs", getClass());
		fh = new java.io.File(url.toURI());
		String bs_attach = FileOps.readAsText(fh, null).trim();

		String uidgen = openFolder(FOLDER1B, false, 2, 2, 1, 3, null, "$laBel1");
		openFolder(FOLDER1B, true, 2, 0, 1, 3, uidgen, "$laBel1");

		if (System.currentTimeMillis() == 0) {
			// disabled as the resource files have been corrupted
			sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 1 (bodystructure uid)");
			getUntaggedResponse(null, "1 FETCH (BODYSTRUCTURE ("+bs_simple+") UID 1)");
			getResponseOK(null, null);

			sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 2 bodystructure");
			getUntaggedResponse(null, "2 FETCH (BODYSTRUCTURE ("+bs_attach+"))");
			getResponseOK(null, null);

			sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 2 BODY[header.fields (suBject)]");
			getUntaggedResponse(null, "2 FETCH (BODY[HEADER.FIELDS (SUBJECT)] {33}");
			getLine("Subject: Complex nested message");
			getLine("");
			getLine(")");
			getUntaggedResponse(null, "2 FETCH (FLAGS (\\Seen))");
			getResponseOK(null, null);

			sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 2 BODY[2.mime]");
			getUntaggedResponse(null, "2 FETCH (BODY[2.MIME] {30}");
			getLine("Content-Type: message/rfc822");
			getLine("");
			getLine(")");
			getResponseOK(null, null);

			sendRequest(IMAP4Protocol.CMDREQ_FETCH+" 2 BODY[2.header.fields (suBject)]");
			getUntaggedResponse(null, "2 FETCH (BODY[2.HEADER.FIELDS (SUBJECT)] {24}");
			getLine("Subject: New data file");
			getLine("");
			getLine(")");
			getResponseOK(null, null);
			//NB: BODY[2.2.mime] would yield MIME headers for attachment bodypart
		}

		//throw in a test of explicit expunge
		openFolder(FOLDER1B, false, 2, 0, 1, 3, uidgen, "$laBel1");
		issueCommand(IMAP4Protocol.CMDREQ_STORE, "1,2 +flags.silent ("+IMAP4Protocol.MSGFLAG_DEL+")", null);
		sendRequest(IMAP4Protocol.CMDREQ_EXPUNGE);
		getUntaggedResponse(null, "2 EXPUNGE");
		getUntaggedResponse(null, "1 EXPUNGE");
		getResponseOK(null, null);
		String exp = FOLDER1B+" (MESSAGES 0 RECENT 0 UNSEEN 0 UIDNEXT 3 UIDVALIDITY "+uidgen+")";
		issueCommand(IMAP4Protocol.CMDREQ_STATUS, FOLDER1B+" (MESSAGES RECENT UNSEEN UIDNEXT UIDVALIDITY)", exp);

		issueCommand(IMAP4Protocol.CMDREQ_CLOSE, null, null);
	}

	private void testOddMessages() throws java.io.IOException, java.net.URISyntaxException
	{
		String pthnam = dsptch.getApplicationContext().getConfig().getPathTemp()+"/badmsg1";
		java.io.File fh = new java.io.File(pthnam);
		int exists_cnt = 1;
		int recent_cnt = 0;
		int unseen_seq = 1;
		int uidnext = 2;

		// malformed message with truncated headers - even the final header's EOL is missing
		String txt = "Subject: Message with headers truncated before EOL";
		FileOps.writeTextFile(fh, txt, false);
		createMessage(FOLDER1, null, null, false, -1, -1, fh, false);
		exists_cnt++;
		recent_cnt++;
		uidnext++;
		openFolder(FOLDER1, false, exists_cnt, recent_cnt, unseen_seq, uidnext, null, "$laBel1");
		sendRequest(IMAP4Protocol.CMDREQ_FETCH.toString()+" "+exists_cnt+" BODY[header.fields (suBject To)]");
		getUntaggedResponse(null, exists_cnt+" FETCH (BODY[HEADER.FIELDS (SUBJECT TO)] {"+(txt.length()+1)+"}");
		getLine(txt.trim());
		getLine(")");
		getUntaggedResponse(null, exists_cnt+" FETCH (FLAGS (\\Recent \\Seen))");
		getResponseOK(null, null);
		issueCommand(IMAP4Protocol.CMDREQ_CLOSE, null, null);
		recent_cnt = 0;

		// malformed message with truncated headers - no following blank line or body
		txt = "Subject: Message with no blank line after headers\n";
		FileOps.writeTextFile(fh, txt, false);
		createMessage(FOLDER1, null, null, false, -1, -1, fh, false);
		exists_cnt++;
		recent_cnt++;
		uidnext++;
		openFolder(FOLDER1, false, exists_cnt, recent_cnt, unseen_seq, uidnext, null, "$laBel1");
		sendRequest(IMAP4Protocol.CMDREQ_FETCH.toString()+" "+exists_cnt+" BODY[header.fields (suBject To)]");
		getUntaggedResponse(null, exists_cnt+" FETCH (BODY[HEADER.FIELDS (SUBJECT TO)] {"+(txt.length())+"}");
		getLine(txt.trim());
		getLine(")");
		getUntaggedResponse(null, exists_cnt+" FETCH (FLAGS (\\Recent \\Seen))");
		getResponseOK(null, null);
		issueCommand(IMAP4Protocol.CMDREQ_CLOSE, null, null);
		recent_cnt = 0;

		// this is technically a valid message, despite having no body
		txt = "Subject: Message with blank line after headers but no body\r\n\r\n";
		FileOps.writeTextFile(fh, txt, false);
		createMessage(FOLDER1, null, null, false, -1, -1, fh, false);
		exists_cnt++;
		recent_cnt++;
		uidnext++;
		openFolder(FOLDER1, false, exists_cnt, recent_cnt, unseen_seq, uidnext, null, "$laBel1");
		sendRequest(IMAP4Protocol.CMDREQ_FETCH.toString()+" "+exists_cnt+" BODY[header.fields (suBject To)]");
		getUntaggedResponse(null, exists_cnt+" FETCH (BODY[HEADER.FIELDS (SUBJECT TO)] {"+txt.length()+"}");
		getLine(txt.trim());
		getLine("");
		getLine(")");
		getUntaggedResponse(null, exists_cnt+" FETCH (FLAGS (\\Recent \\Seen))");
		getResponseOK(null, null);
		issueCommand(IMAP4Protocol.CMDREQ_CLOSE, null, null);
	}

	private void startServer() throws java.io.IOException
	{
		// create a disposable Dispatcher first, just to identify and clean up the working directories that will be used
		dsptch = Dispatcher.create(appctx, new com.grey.naf.DispatcherDef.Builder().build(), com.grey.logging.Factory.getLogger("no-such-logger"));
		NAFConfig nafcfg = dsptch.getApplicationContext().getConfig();
		FileOps.deleteDirectory(nafcfg.getPathVar());
		FileOps.deleteDirectory(nafcfg.getPathTemp());
		FileOps.deleteDirectory(nafcfg.getPathLogs());
		// now create the real Dispatcher
		com.grey.naf.DispatcherDef def = new com.grey.naf.DispatcherDef.Builder()
				.withSurviveHandlers(false)
				.build();
		dsptch = Dispatcher.create(appctx, def, com.grey.logging.Factory.getLogger("no-such-logger"));

		// set up the IMAP server
		XmlConfig cfg = XmlConfig.makeSection(nafxml_server, "x");
		srvtask = new IMAP4Task("utest_imap", dsptch, cfg);

		// find out which ephemeral port it's listening on
		com.grey.naf.reactor.CM_Listener lstnr = appctx.getListener("UTEST_IMAP4");
		srvaddr = TSAP.build(null, lstnr.getPort(), true);

		// launch Dispatcher
		srvtask.startDispatcherRunnable();
		dsptch.start(); //Dispatcher launches in separate thread
	}

	private void stopServer() throws java.io.IOException
	{
		dsptch.stop();
		//we join() Dispatcher thread, so its memory changes will be visible on return
		Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(TimeOps.MSECS_PER_SECOND * 10, true);
		org.junit.Assert.assertEquals(Dispatcher.STOPSTATUS.STOPPED, stopsts);
		org.junit.Assert.assertTrue(dsptch.completedOK());
		boolean taskstopped = srvtask.stopDispatcherRunnable();
		org.junit.Assert.assertTrue(taskstopped);
	}

	private void connect() throws java.io.IOException
	{
		sock = new java.net.Socket(srvaddr.sockaddr.getAddress(), srvaddr.port);
		ocstrm = new java.io.OutputStreamWriter(sock.getOutputStream());
		java.io.InputStreamReader icstrm = new java.io.InputStreamReader(sock.getInputStream());
		ibstrm = new java.io.BufferedReader(icstrm, 1024);
		String rsp = ibstrm.readLine(); //get greeting
		assertResponseStart(rsp, IMAP4Protocol.STATUS_UNTAGGED+IMAP4Protocol.STATUS_OK+" ");
	}

	private void disconnect() throws java.io.IOException
	{
		sendRequest(IMAP4Protocol.CMDREQ_QUIT);
		getResponseOK(IMAP4Protocol.STATUS_BYE, null);
		ocstrm.close();
		ibstrm.close();
		sock.close();
		ocstrm = null;
		ibstrm = null;
		sock = null;
	}

	private void createMessage(CharSequence mbxname, CharSequence msgflags, CharSequence mbxflags, boolean withoutsynch, int exists_cnt, int recent_cnt,
			java.io.File fh, boolean badfolder) throws java.io.IOException
	{
		String msg;
		if (fh != null) {
			msg = FileOps.readAsText(fh, null);
		} else {
			msg = "From: sender1\r\n"
				+"To: recip2@domainA.com\r\n"
				+"Cc: Mister Nosy <recip3@domainB.com>\r\n"
				+"Subject: Topic "+reqnum+"\r\n"
				+"\r\n"
				+"This is line 1.\r\n"
				+"This is line 2.\r\n";
		}
		String req = IMAP4Protocol.CMDREQ_APPEND+" "+mbxname;
		if (msgflags != null) req += " ("+msgflags+")";
		req += " {"+msg.length()+(withoutsynch?"+":"")+"}";
		sendRequest(req);
		if (badfolder) {
			getResponseRej(null, null);
			return;
		}
		if (!withoutsynch) getResponseContd();
		sendBuffer(msg+"\r\n");
		if (mbxflags != null) {
			String flags = MBXFLAGS_STD+" "+mbxflags;
			getUntaggedResponse(null, "FLAGS ("+flags+")");
			getUntaggedResponse(null, "OK [PERMANENTFLAGS ("+flags+" \\*)] perm");
		}
		if (exists_cnt != -1) {
			getUntaggedResponse(null, exists_cnt+" EXISTS");
		}
		if (recent_cnt != -1) {
			getUntaggedResponse(null, recent_cnt+" RECENT");
		}
		getResponseOK(null, null);
	}

	private String openFolder(CharSequence mbxname, boolean rdonly, int exists_cnt, int recent_cnt, int unseen_seq,
			int uidnext, String exp_uidgen, String extraflags) throws java.io.IOException
	{
		CharSequence cmd = IMAP4Protocol.CMDREQ_SELECT;
		String flags = MBXFLAGS_STD+(extraflags == null ? "" : (" "+extraflags));
		String permflags = flags+" \\*";
		String rspcode = "[READ-WRITE]";
		if (rdonly) {
			cmd = IMAP4Protocol.CMDREQ_EXAMINE;
			permflags = "";
			rspcode = "[READ-ONLY]";
		}
		sendRequest(cmd+" "+mbxname);
		getUntaggedResponse("FLAGS", "("+flags+")");
		getUntaggedResponse("OK [PERMANENTFLAGS", "("+permflags+")] perm");
		getUntaggedResponse(null, exists_cnt+" EXISTS");
		getUntaggedResponse(null, recent_cnt+" RECENT");
		if (unseen_seq != 0) getUntaggedResponse("OK [UNSEEN "+unseen_seq+"]", null);
		String uidgen = getUntaggedResponse("OK [UIDVALIDITY", null);
		getUntaggedResponse("OK [UIDNEXT "+uidnext+"]", null);
		getResponseOK(null, rspcode);

		int pos = uidgen.indexOf(']');
		uidgen = uidgen.substring(0, pos);
		if (exp_uidgen != null) org.junit.Assert.assertEquals(exp_uidgen, uidgen);
		return uidgen;
	}

	// suitable for a successful (as we expect) request with at most one untagged response
	private String issueCommand(CharSequence cmd, CharSequence args, String exp_untagged) throws java.io.IOException
	{
		CharSequence req = (args == null ? cmd : (cmd+" "+args));
		CharSequence untagged_rsp = (exp_untagged == null ? null : cmd);
		sendRequest(req);
		String rsp = getResponseOK(untagged_rsp, null);
		if (exp_untagged != null && !exp_untagged.equals(UNTAG_NOCHECK)) org.junit.Assert.assertEquals(exp_untagged, rsp);
		return rsp;
	}

	private void sendRequest(CharSequence req) throws java.io.IOException
	{
		reqtag = "C"+reqnum++;
		sendLine(reqtag+" "+req);
	}

	private String getResponseOK(CharSequence prior_untagged, CharSequence rsptxt) throws java.io.IOException
	{
		return getResponseStatus(IMAP4Protocol.STATUS_OK, prior_untagged, rsptxt);
	}

	private void getResponseRej(CharSequence prior_untagged, CharSequence rsptxt) throws java.io.IOException
	{
		getResponseStatus(IMAP4Protocol.STATUS_REJ, prior_untagged, rsptxt);
	}

	private void getResponseBad(CharSequence prior_untagged, CharSequence rsptxt) throws java.io.IOException
	{
		getResponseStatus(IMAP4Protocol.STATUS_ERR, prior_untagged, rsptxt);
	}

	private String getResponseContd() throws java.io.IOException
	{
		String rsp = ibstrm.readLine();
		assertResponseStart(rsp, IMAP4Protocol.STATUS_CONTD);
		return stripPrefix(rsp, IMAP4Protocol.STATUS_CONTD);
	}

	private String getResponseStatus(CharSequence expected_status, CharSequence prior_untagged, CharSequence rsptxt) throws java.io.IOException
	{
		String ursp = null;
		if (prior_untagged != null) ursp = getUntaggedResponse(prior_untagged, null); //get single untagged response
		String rsp = ibstrm.readLine();
		assertResponseStart(rsp, reqtag+" "+expected_status+" ");
		if (rsptxt != null) org.junit.Assert.assertTrue(rsp.contains(rsptxt));
		return ursp;
	}

	private String getUntaggedResponse(CharSequence name, String exp) throws java.io.IOException
	{
		String rsp = ibstrm.readLine();
		String pfx = (name == null ? IMAP4Protocol.STATUS_UNTAGGED : IMAP4Protocol.STATUS_UNTAGGED+name);
		assertResponseStart(rsp, pfx);
		rsp = stripPrefix(rsp, pfx);
		if (exp != null) org.junit.Assert.assertEquals("len="+rsp.length()+" vs exp="+exp.length(), exp, rsp);
		return rsp;
	}

	private void getLine(String exp) throws java.io.IOException
	{
		String rsp = ibstrm.readLine();
		org.junit.Assert.assertNotNull(rsp);
		org.junit.Assert.assertEquals("len="+rsp.length()+" vs exp="+exp.length(), exp, rsp);
	}

	private void sendLine(CharSequence msg) throws java.io.IOException
	{
		sendBuffer(msg+IMAP4Protocol.EOL);
	}

	private void sendBuffer(CharSequence msg) throws java.io.IOException
	{
		ocstrm.write(msg.toString());
		ocstrm.flush();
	}

	private static void assertResponseStart(String rsp, String exp)
	{
		org.junit.Assert.assertTrue("Expected-Response="+exp+" vs Actual="+(rsp==null?rsp:rsp.trim()), rsp != null && rsp.startsWith(exp));
	}

	private static String stripPrefix(String str, String pfx)
	{
		if (pfx.length() == str.length()) return null;
		int off = pfx.length();
		while (str.charAt(off) == ' ') off++;
		return str.substring(off);
	}
}
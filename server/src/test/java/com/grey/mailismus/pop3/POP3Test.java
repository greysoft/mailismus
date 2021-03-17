/*
 * Copyright 2013-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.pop3;

import java.util.ArrayList;
import java.util.List;

import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.FileOps;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.DispatcherDef;
import com.grey.naf.EntityReaper;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.ListenerSet;
import com.grey.naf.reactor.config.ConcurrentListenerConfig;
import com.grey.mailismus.Task;
import com.grey.mailismus.TestSupport;
import com.grey.mailismus.pop3.client.DownloadClient;
import com.grey.mailismus.pop3.client.DownloadTask;
import com.grey.mailismus.pop3.server.POP3Server;

public class POP3Test
	implements com.grey.naf.reactor.TimerNAF.Handler
{
	static {
		com.grey.mailismus.TestSupport.initPaths(POP3Test.class);
	}
	private static final long MAXRUNTIME = SysProps.getTime("grey.test.pop3.runtime", "1m");
	private static final int TMRTYPE_STOP = 1;

	// NB: the certificate files referenced in this test's config are copied from NAF's SSLConnectionTest
	private static final String appcfg_path = "cp:com/grey/mailismus/pop3/conf.xml";
	private static final String nafxml_server = "<x><configfile root=\"mailserver/pop3server\">"+appcfg_path+"</configfile></x>";
	private static final String nafxml_client = "<x><configfile root=\"mailserver/pop3download\">"+appcfg_path+"</configfile></x>";

	private static final com.grey.base.utils.ByteChars username_src = new com.grey.base.utils.ByteChars("user2");
	private static final com.grey.base.utils.ByteChars username_dst = new com.grey.base.utils.ByteChars("user1");

	private static final int SRVID_STD = 0;
	private static final int SRVID_SSLONLY = 1;
	private static final int SRVID_CRAMONLY = 2;

	private static final String SIZEPFX = "Size=";

	private static final ApplicationContextNAF appctx = TestSupport.createApplicationContext("POP3Test", true);
	private Dispatcher dsptch;
	private boolean dsptch_failed;

	@org.junit.Test
	public void testLogin_UserPass() throws Exception
	{
		DownloadClient.Results results = runtest("userpass", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("userpass", SRVID_SSLONLY);
		org.junit.Assert.assertFalse(results.completed_ok);

		// just want to test a server that doesn't have APOP, as it does some things differently
		results = runtest("userpass", SRVID_CRAMONLY);
		org.junit.Assert.assertFalse(results.completed_ok);

		results = runtest("userpass_badpass", SRVID_STD);
		org.junit.Assert.assertFalse(results.completed_ok);
	}

	@org.junit.Test
	public void testLogin_APOP() throws Exception
	{
		DownloadClient.Results results = runtest("apop", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("apop_capa", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);
	}

	@org.junit.Test
	public void testLogin_SaslPlain() throws Exception
	{
		DownloadClient.Results results = runtest("ssl_sasl_plain", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("ssl_sasl_plain_initrsp", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("ssl_sasl_plain", SRVID_SSLONLY);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("sasl_plain", SRVID_STD);
		org.junit.Assert.assertFalse(results.completed_ok);
	}

	@org.junit.Test
	public void testLogin_SaslCramMD5() throws Exception
	{
		DownloadClient.Results results = runtest("sasl_crammd5", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("sasl_crammd5_initrsp", SRVID_CRAMONLY);
		org.junit.Assert.assertTrue(results.completed_ok);
	}

	@org.junit.Test
	public void testLogin_SaslExternal() throws Exception
	{
		DownloadClient.Results results = runtest("ssl_sasl_external", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("ssl_sasl_external_initrsp", SRVID_STD);
		org.junit.Assert.assertTrue(results.completed_ok);

		results = runtest("sasl_external", SRVID_STD);
		org.junit.Assert.assertFalse(results.completed_ok);
	}

	@org.junit.Test
	public void testDownload() throws Exception
	{
		String[] msgs = new String[]{"Message-ID: msgid1\r\nFrom: from1\r\nSender: sender1\r\nMessage 1\r\n",
				"Message 2\r\nLine 2\r\n..Line 3\r\n...Line 4\r\n",
				SIZEPFX+com.grey.base.utils.ByteOps.parseByteSize("10M")};
		DownloadClient.Results results = runtest("userpass", SRVID_STD, msgs, false, false);
		org.junit.Assert.assertTrue(results.completed_ok);
		results = runtest("userpass", SRVID_STD, msgs, true, false);
		org.junit.Assert.assertTrue(results.completed_ok);
	}

	@org.junit.Test
	public void testLogin_fallback() throws Exception
	{
		String[] msgs = new String[]{"Message 1\n",
			"Message 2\n"};
		DownloadClient.Results results = runtest("fallback_plain_crammd5", SRVID_STD, msgs, false, false);
		org.junit.Assert.assertTrue(results.completed_ok);
	}

	@org.junit.Test
	public void testLogin_connectfail() throws Exception
	{
		DownloadClient.Results results = runtest("userpass", SRVID_STD, null, false, true);
		org.junit.Assert.assertFalse(results.completed_ok);
	}

	private DownloadClient.Results runtest(String cid, int sid, String[] messages, boolean dotstuffing, boolean connectfail)
			throws java.io.IOException, java.security.GeneralSecurityException
	{
		// create a disposable Dispatcher first, just to identify and clean up the working directories that will be used
		dsptch = Dispatcher.create(appctx, new DispatcherDef.Builder().build(), com.grey.logging.Factory.getLogger("no-such-logger"));
		NAFConfig nafcfg = dsptch.getApplicationContext().getConfig();
		FileOps.deleteDirectory(nafcfg.getPathVar());
		FileOps.deleteDirectory(nafcfg.getPathTemp());
		FileOps.deleteDirectory(nafcfg.getPathLogs());
		// now create the real Dispatcher
		com.grey.naf.DispatcherDef def = new com.grey.naf.DispatcherDef.Builder()
				.withSurviveHandlers(false)
				.build();
		dsptch = Dispatcher.create(appctx, def, com.grey.logging.Factory.getLogger("no-such-logger"));

		// set up the POP3 server
		XmlConfig cfg = XmlConfig.makeSection(nafxml_server, "x");
		com.grey.mailismus.Task stask = new com.grey.mailismus.Task("utest_pop3s", dsptch, cfg, Task.DFLT_FACT_DTORY, Task.DFLT_FACT_MS, null);
		if (dotstuffing) DynLoader.setField(stask.getMS(), "dotstuffing", true);
		String grpname = "utest_pop3s_listeners";
		ConcurrentListenerConfig[] lcfg = ConcurrentListenerConfig.buildMultiConfig(grpname, appctx.getConfig(), "listeners/listener", stask.taskConfig(), 0, 0, POP3Server.Factory.class, null);
		ListenerSet lstnrs = new ListenerSet(grpname, dsptch, stask, null, lcfg);
		int srvport = (connectfail ? 0 : lstnrs.getListener(sid).getPort());

		// set up the POP3 client
		cfg = XmlConfig.makeSection(nafxml_client, "x");
		ClientReaper creaper = new ClientReaper(this, dsptch);
		List<String> lst = new ArrayList<String>();
		lst.add(cid);
		com.grey.mailismus.Task ctask = new TestDownloadTask("utest_pop3downloader", dsptch, cfg, srvport, creaper, lst);

		// set up any messages to be downloaded by the POP client - inject into Server's MS
		int msgsizes = 0;
		if (messages != null) {
			String workdir = nafcfg.getPathTemp()+"/utest/upload";
			FileOps.ensureDirExists(workdir);
			for (int idx = 0; idx != messages.length; idx++) {
				java.io.File fh = new java.io.File(workdir+"/x");
				java.io.FileOutputStream strm = new java.io.FileOutputStream(fh, false);
				String filetxt = messages[idx];
				try {
					byte[] filebytes;
					if (filetxt.startsWith(SIZEPFX)) {
						filebytes = new byte[Integer.parseInt(filetxt.substring(SIZEPFX.length()))];
						java.util.Arrays.fill(filebytes, (byte)'A');
						if (dotstuffing) msgsizes += 2; //because this has no EOL, but Server will load it in line-oriented mode
					} else {
						filebytes = filetxt.getBytes();
					}
					strm.write(filebytes);
					msgsizes += filebytes.length;
					if (!dotstuffing) msgsizes += 2;  //our POP server adds extra CRLF at end
				} finally {
					strm.close();
				}
				stask.getMS().deliver(username_src, fh);
			}
			org.junit.Assert.assertEquals(messages.length, getMessageCount(ctask.getMS(), username_src));
			org.junit.Assert.assertEquals(0, getMessageCount(ctask.getMS(), username_dst));
		}

		// launch
		dsptch_failed = true;
		lstnrs.start(false);
		ctask.startDispatcherRunnable();
		dsptch.start(); //Dispatcher launches in separate thread
		Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(MAXRUNTIME, true);
		boolean cstopped = ctask.stopDispatcherRunnable();
		boolean lstopped = lstnrs.stop(false);
		org.junit.Assert.assertEquals(Dispatcher.STOPSTATUS.STOPPED, stopsts);
		org.junit.Assert.assertTrue(dsptch.completedOK());
		org.junit.Assert.assertFalse(dsptch_failed);
		org.junit.Assert.assertTrue(cstopped);
		org.junit.Assert.assertTrue(lstopped);
		DownloadClient.Results main_results = creaper.results.get(0);

		if (main_results.completed_ok) {
			if (messages != null) {
				// doesn't matter whether we use ctask or stask to access the MS
				com.grey.mailismus.ms.maildir.MaildirStore ms = (com.grey.mailismus.ms.maildir.MaildirStore)ctask.getMS();
				com.grey.mailismus.ms.maildir.InboxSession sess = ms.startInboxSession(username_dst);
				org.junit.Assert.assertEquals(messages.length, main_results.msgcnt);
				org.junit.Assert.assertEquals(messages.length, getMessageCount(ctask.getMS(), username_dst));
				org.junit.Assert.assertEquals(messages.length, getMessageCount(stask.getMS(), username_dst));
				org.junit.Assert.assertEquals(messages.length, sess.newMessageCount());
				int actualsizes = 0;
				for (int idx = 0; idx != sess.newMessageCount(); idx++) {
					actualsizes += sess.getMessageSize(idx);
				}
				sess.endSession();
				org.junit.Assert.assertEquals(msgsizes, actualsizes);
			} else {
				org.junit.Assert.assertEquals(0, main_results.msgcnt);
				org.junit.Assert.assertEquals(0, getMessageCount(ctask.getMS(), username_dst));
			}
			org.junit.Assert.assertEquals(0, getMessageCount(ctask.getMS(), username_src));
		}
		for (int idx = 1; idx < creaper.results.size(); idx++) {
			org.junit.Assert.assertEquals(0, creaper.results.get(idx).msgcnt);
			org.junit.Assert.assertSame(main_results.completed_ok, creaper.results.get(idx).completed_ok);
		}
		return main_results;
	}

	private DownloadClient.Results runtest(String cid, int sid)
			throws java.io.IOException, java.security.GeneralSecurityException
	{
		return runtest(cid, sid, null, false, false);
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, Dispatcher d) throws java.io.IOException {
		if (tmr.getType() != TMRTYPE_STOP) return;
		if (d.isRunning()) dsptch_failed = false; //state that we stopped the Dispatcher, as opposed to it crashing out on error
		boolean stopped = d.stop();
		org.junit.Assert.assertFalse(stopped);
	}
	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, Dispatcher d, Throwable ex) {}

	private static int getMessageCount(com.grey.mailismus.ms.MessageStore ms_iface, com.grey.base.utils.ByteChars username)
	{
		com.grey.mailismus.ms.maildir.MaildirStore ms = (com.grey.mailismus.ms.maildir.MaildirStore)ms_iface;
		com.grey.mailismus.ms.maildir.InboxSession sess = ms.startInboxSession(username);
		int cnt = sess.newMessageCount();
		sess.endSession();
		return cnt;
	}
	
	private static class ClientReaper implements EntityReaper {
		public final List<DownloadClient.Results> results = new ArrayList<DownloadClient.Results>();
		private final com.grey.naf.reactor.TimerNAF.Handler observer;
		private final Dispatcher dsptch;
		
		public ClientReaper(com.grey.naf.reactor.TimerNAF.Handler th, Dispatcher d) {
			observer = th;
			dsptch = d;
		}

		@Override
		public void entityStopped(Object obj) {
			if (obj instanceof DownloadTask) {
				dsptch.setTimer(100, TMRTYPE_STOP, observer); //give server time to receive disconnect event
				return;
			}
			DownloadClient client = (DownloadClient)obj;
			DownloadClient.Results r = new DownloadClient.Results();
			r.completed_ok = client.getResults().completed_ok;
			r.msgcnt = client.getResults().msgcnt;
			results.add(r);
		}
	}

	private static class TestDownloadTask extends DownloadTask {
		private final EntityReaper rpr;
		public TestDownloadTask(String name, Dispatcher d, XmlConfig cfg, int srvport, EntityReaper rpr, List<String> clients)
				throws java.io.IOException, java.security.GeneralSecurityException {
			super(name, d, cfg, srvport, rpr, clients);
			this.rpr = rpr;
		}
		@Override
		protected void nafletStopped() {
			super.nafletStopped();
			rpr.entityStopped(this);
		}
	}
}
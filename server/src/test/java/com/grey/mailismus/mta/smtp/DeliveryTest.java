/*
 * Copyright 2013-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.smtp;

import java.nio.file.Path;
import java.util.ArrayList;

import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.IP;
import com.grey.base.utils.TSAP;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.DynLoader;
import com.grey.base.collections.Circulist;
import com.grey.base.collections.ObjectQueue;
import com.grey.base.collections.ObjectWell;
import com.grey.base.collections.HashedMapIntInt;
import com.grey.base.collections.HashedMapIntKey;

import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.naf.DispatcherDef;
import com.grey.naf.EntityReaper;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.TimerNAF;
import com.grey.naf.reactor.ChannelMonitor;

import com.grey.mailismus.AppConfig;
import com.grey.mailismus.Task;
import com.grey.mailismus.mta.MTA_Task;
import com.grey.mailismus.mta.queue.QueueFactory;
import com.grey.mailismus.mta.submit.filter.api.FilterFactory;
import com.grey.mailismus.mta.submit.filter.api.FilterResultsHandler;
import com.grey.mailismus.mta.submit.filter.api.MessageFilter;
import com.grey.mailismus.mta.deliver.Relay;
import com.grey.mailismus.mta.deliver.Delivery;
import com.grey.mailismus.mta.deliver.Forwarder;
import com.grey.mailismus.TestSupport;

/*
 * NB: The certificate files referenced in this test's config are copied from NAF's SSLConnectionTest
 */
public class DeliveryTest
	implements Forwarder.BatchCallback, EntityReaper, TimerNAF.Handler
{
	private static final String appcfg_path = "cp:com/grey/mailismus/mta/smtp/conf.xml";
	private static final long MAXRUNTIME = SysProps.getTime("grey.test.mta.runtime", "1m");
	private static final int TMRTYPE_STOP = 1;

	private static MockServerDNS mockserverDNS;

	static {
		TestSupport.initPaths(DeliveryTest.class);
	}

	private static class FwdStats
	{
		final Delivery.Stats stats = new Delivery.Stats();
		int qsize;
		FwdStats() {}
		FwdStats(int q, int c, int m) {qsize = q; stats.conncnt = c; stats.sendermsgcnt = m;}
		FwdStats relay(int t, int f) {stats.remotecnt = t; stats.remotefailcnt = f; return this;}
		FwdStats local(int t, int f) {stats.localcnt = t; stats.localfailcnt = f; return this;}
		FwdStats reset() {stats.reset(null); qsize = 0; return this;}
	}
	private FwdStats[] expect_fwdstats;
	private final FwdStats actual_fwdstats = new FwdStats();
	private int actual_fwdbatchcnt;
	private String fwd_errmsg;

	private Dispatcher dsptch;
	private Forwarder smtp_sender;
	private String altcfg_path;
	private boolean dsptch_failed;

	@org.junit.BeforeClass
	public static void beforeClass() throws java.io.IOException
	{
		SysProps.setAppEnv("MAILISMUS_TEST_PORT_AUTHOPT", String.valueOf(TSAP.getVacantPort()));
		SysProps.setAppEnv("MAILISMUS_TEST_PORT_AUTHMDTY", String.valueOf(TSAP.getVacantPort()));
		SysProps.setAppEnv("MAILISMUS_TEST_PORT_AUTHNONE", String.valueOf(TSAP.getVacantPort()));
		SysProps.setAppEnv("MAILISMUS_TEST_PORT_SSLMDTY", String.valueOf(TSAP.getVacantPort()));
		SysProps.setAppEnv("MAILISMUS_TEST_PORT_SMARTHOST", String.valueOf(TSAP.getVacantPort()));
		System.out.println("DeliverTest App Env = "+SysProps.getAppEnv());

		ApplicationContextNAF appctx = TestSupport.createApplicationContext("DeliveryTest-MockServerDNS", true);
		mockserverDNS = new MockServerDNS(appctx);
		mockserverDNS.start();
	}

	@org.junit.AfterClass
	public static void afterClass()
	{
		if (mockserverDNS != null) mockserverDNS.stop();
	}

	@org.junit.Rule
	public final org.junit.rules.TestRule testwatcher = new org.junit.rules.TestWatcher() {
		@Override public void starting(org.junit.runner.Description d) {
			System.out.println("Starting test="+d.getMethodName()+" - "+d.getClassName());
		}
	};

	//See testAliases() for treatment of @nosuchdomain.dns
	@org.junit.Test
	public void testMisc() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender1@dom1.local",
				new String[]{"recip1@anon1.relay", "a_user1", "z_user50"},
				new String[]{"recip100@temperr1.relay"},
				new String[]{"recip200@nosuchdomain.dns"},
				"Testing local-format recipients, mixed in with a valid remote one and transient and perm errors")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(5, 3, 3).relay(3, 2).local(2, 0)};
		runtest(msgs, 1, 1);
	}

	//The @nosuchdomain.dns alias triggers a DNS lookup which we expected to be answered with NXDOM, thus causing the delivery
	//to fail with a perm error.
	@org.junit.Test
	public void testAliases() throws Exception
	{
		//9 recips, 1 cannot be routed by Client (nosuchdomain.dns), 2 are rejected by server (alias2@dom2.local, nosuchuser)
		//as unknown local users. All 6 successful recips are received as a single message by Server.
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("SEnder1@dom1.local",
				new String[]{"ALias1@DOm1.local", "alias1@DOm2.local", "ALias2@dom1.locAL", "usEr104@DOm1.local",
					"USer109@DOm1.local", "USer108@DOm2.local"},
				null,
				new String[]{"alias2@dom2.local", "nosuchuser@dom1.local", "user109@nosuchdomain.dns"},
				"Testing server-side aliases")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(9, 2, 2).relay(9, 3)};
		runtest(msgs, 6, 1);
	}

	@org.junit.Test
	public void testFilterRejection() throws Exception
	{
		//first ensure that everything would work as expected if not filtered
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("filter_me_not@dom1.local",
				new String[]{"recip1@anon1.relay"}, null, null,
				"Verifying setup for message-filter test")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(1, 1, 1).relay(1, 0)};
		runtest(msgs, 1, 1);
		runtest(msgs, 1, 1); //verify that repeating runtest() doesn't cause an issue

		//now repeat with message-filtering triggered
		msgs = new MessageSpec[] {new MessageSpec("filter_me@dom1.local",
				null, null, new String[]{"recip1@anon1.relay"},
				"Testing rejection by MessageFilter")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(1, 1, 1).relay(1, 1)};
		runtest(msgs, 0, 0);
	}

	@org.junit.Test
	public void testFilterFailure() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("filter_fail_me@dom1.local",
				null, null, new String[]{"recip1@anon1.relay"},
				"Testing failure by MessageFilter")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(1, 1, 1).relay(1, 1)};
		runtest(msgs, 0, 0);
	}

	@org.junit.Test
	public void testAllRejected() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender1@dom1.local",
				null, null, new String[]{"nosuchuser1@dom1.local"},
				"Testing single recipient gets rejected"),
			new MessageSpec("sender2@dom1.local",
				null, null, new String[]{"nosuchuser11@dom1.local", "nosuchuser12@dom1.local"},
				"Testing multiple recipients who all get rejected")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(3, 2, 2).relay(3, 3)};
		runtest(msgs, 0, 0);
	}

	@org.junit.Test
	public void testMultipleDomainConns() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("senderA@dom1.local",
				new String[]{"recip1@anon1.relay", "recip2@anon1.relay"}, null, null,
				"Testing multiple messages per domain - message 1"),
			new MessageSpec("senderB@dom2.local",
				new String[]{"recip3@anon1.relay"}, null, null,
				"Testing multiple messages per domain - message 2"),
			new MessageSpec("senderC@dom3.local",
				new String[]{"recip4@anon1.relay"}, null, null,
				"Testing multiple messages per domain - message 3"),
			new MessageSpec("senderD@dom1.local",
				new String[]{"recip5@anon1.relay"}, null, null,
				"Testing multiple messages per domain - message 4")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(5, 4, 4).relay(5, 0)};
		runtest(msgs, 5, 4);
	}

	// batch multiple messages for a domain into one connection
	@org.junit.Test
	public void testMaxMessages_NoSplit() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("senderA@dom1.local",
				new String[]{"recip1@anon1.relay", "recip2@anon1.relay"}, null, null,
				"Testing max messages per connection with split - message 1"),
			new MessageSpec("senderB@dom2.local",
				new String[]{"recip3@anon1.relay"}, null, null,
				"Testing max messages per connection with split - message 2"),
			new MessageSpec("senderC@dom3.local",
				new String[]{"recip4@anon1.relay"}, null, null,
				"Testing max messages per connection with split - message 3"),
			new MessageSpec("senderD@dom1.local",
				new String[]{"recip5@anon1.relay"}, null, null,
				"Testing max messages per connection with split - message 4")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(5, 1, 4).relay(5, 0)};
		runtest(msgs, 5, 4, 1, 0);
	}

	// split excess messages to one domain across multiple connections
	@org.junit.Test
	public void testMaxMessages_Split() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("senderA@dom1.local",
				new String[]{"recip1@anon1.relay", "recip2@anon1.relay"}, null, null,
				"Testing max messages per connection - message 1"),
			new MessageSpec("senderB@dom2.local",
				new String[]{"recip3@anon1.relay"}, null, null,
				"Testing max messages per connection - message 2"),
			new MessageSpec("senderC@dom3.local",
				new String[]{"recip4@anon1.relay"}, null, null,
				"Testing max messages per connection - message 3"),
			new MessageSpec("senderD@dom1.local",
				new String[]{"recip5@anon1.relay"}, null, null,
				"Testing max messages per connection - message 4"),
			new MessageSpec("senderE@dom2.local",
				new String[]{"recip6@anon1.relay"}, null, null,
				"Testing max messages per connection - message 5")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(6, 1, 4).relay(5, 0),
				new FwdStats(1, 1, 1).relay(1, 0)};
		runtest(msgs, 6, 5, 1, 0);
	}

	// With the configured maxpipeline=4, this also tests pipelining, and makes sure we can resume after pipeline blocks
	@org.junit.Test
	public void testMaxRecips_NoSplit() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender@dom1.local",
				new String[]{"recip1@anon1.relay", "recip2@anon1.relay", "recip3@anon1.relay", "recip4@anon1.relay", "recip5@anon1.relay"},
				null, null,
				"Testing max recips per connection without split")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(5, 1, 1).relay(5, 0)};
		runtest(msgs, 5, 1, 0, 5);
	}

	@org.junit.Test
	public void testMaxRecips_Split() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender@dom1.local",
				new String[]{"recip1@anon1.relay", "recip2@anon1.relay", "recip3@anon1.relay", "recip4@anon1.relay", "recip5@anon1.relay",
									"recip6@anon1.relay"},
				null, null,
				"Testing max recips per connection with split")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(6, 2, 2).relay(6, 0)};
		runtest(msgs, 6, 2, 0, 5);
	}

	// tests SMTP-Auth and SSL
	@org.junit.Test
	public void testAuth() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender1@dom1.local",
					new String[]{"recip1@auto.auth.relay", "recip2@anon1.relay", "recip3@anon2.relay",
						"recip4@plain.auth.relay", "recip5@plain_init.auth.relay", "recip6@crammd5.auth.relay", "recip7@crammd5_init.auth.relay",
						"recip8@external.auth.relay", "recip9@external_init.auth.relay", "recip10@auto2-notsupp.auth.relay"},
					null,
					new String[]{"recip11@crammd5_badpass.auth.relay", "recip12@plain_nossl.auth.relay", "recip13@crammd5_notsupp.auth.relay",
						"recip14@missing.auth.relay", "recip15@anon3-mdtyssl.relay", "recip16@anon4.relay"},
					"Testing SMTP-Auth")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(16, 16, 16).relay(16, 6)};
		runtest(msgs, 10, 10);
	}

	// The requirement for the email domain used here is that it must have several (more than one) MX records.
	// We redirect the SMTP forwarder to a bad local port to ensure that connections to all the MX hosts fail.
	// This test asserts the required end result of temp failure, but is unable to prove that the Client did actually walk
	// down the entire MX list. At least we're exercising the code.
	@org.junit.Test
	public void testMX() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender@dom1.local",
				null, new String[]{"recip1@"+MockServerDNS.MXQUERY}, null,
				"Testing the MX walk")
		};
		expect_fwdstats = new FwdStats[]{new FwdStats(1, 1, 1).relay(1, 1)};
		runtest(msgs, 0, 0, "localhost:51980"); //intended to be an invalid port with no resident SMTP server
	}

	@org.junit.Test
	public void testSmarthost() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[] {new MessageSpec("sender1@dom1.local",
				new String[]{"user101@dom1.local", "user102@dom2.local", "localuser1"}, null, null,
				"Testing smarthost with 2 remote domains that will get aggregated into 1 message"),
			new MessageSpec("sender2@dom1.local",
				new String[]{"user103@dom1.local"}, null, null,
				"Testing smarthost with  same domain as above but different message")
		};
		altcfg_path = "cp:com/grey/mailismus/mta/smtp/conf-smarthost.xml";
		expect_fwdstats = new FwdStats[]{new FwdStats(4, 2, 2).relay(3, 0).local(1, 0)};
		runtest(msgs, 3, 2);
	}

	@org.junit.Test
	public void testEmptyQ() throws Exception
	{
		MessageSpec[] msgs = new MessageSpec[0];
		expect_fwdstats = new FwdStats[0];
		runtest(msgs, 0, 0);
	}

	private void runtest(MessageSpec[] msgs, int server_submitcnt, int server_spoolcnt) throws java.io.IOException
	{
		runtest(msgs, server_submitcnt, server_spoolcnt, null);
	}

	private void runtest(MessageSpec[] msgs, int server_submitcnt, int server_spoolcnt, String interceptor_spec)
	        throws java.io.IOException
	{
		runtest(msgs, server_submitcnt, server_spoolcnt, 0, 0, interceptor_spec);
	}

	private void runtest(MessageSpec[] msgs, int server_submitcnt, int server_spoolcnt, int maxdomconns, int maxmsgrecips)
	        throws java.io.IOException
	{
		runtest(msgs, server_submitcnt, server_spoolcnt, maxdomconns, maxmsgrecips, null);
	}

	private void runtest(MessageSpec[] msgs, int server_submitcnt, int server_spoolcnt, int maxsrvconns, int maxmsgrecips, String interceptor_spec)
			throws java.io.IOException
	{
		String pthnam_appcfg = (altcfg_path == null ? appcfg_path : altcfg_path);
		altcfg_path = null;
		String nafxml = "<naf>"
				+"<baseport>"+NAFConfig.RSVPORT_ANON+"</baseport>"
				+"<dnsresolver>"
					+"<retry timeout=\"2s\" max=\"2\" backoff=\"200\"/>"
					+"<interceptor host=\"127.0.0.1\" port=\""+mockserverDNS.getPort()+"\"/>"
				+"</dnsresolver></naf>";
		String nafxml_server = "<x><configfile root=\"mailserver/mta/submit\">"+pthnam_appcfg+"</configfile></x>";
		String nafxml_client = "<x><configfile root=\"mailserver/mta/deliver\">"+pthnam_appcfg+"</configfile></x>";
		String nafxml_reports = "<x><configfile root=\"mailserver/mta/report\">"+pthnam_appcfg+"</configfile></x>";

		XmlConfig xmlcfg = XmlConfig.makeSection(nafxml, "/naf");
		NAFConfig nafcfg = new NAFConfig.Builder().withXmlConfig(xmlcfg).build();
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, nafcfg, true);

		// create a disposable Dispatcher first, just to identify and clean up the working directories that will be used
		com.grey.logging.Logger logger = com.grey.logging.Factory.getLogger("no-such-logger");
		dsptch = Dispatcher.create(appctx, new DispatcherDef.Builder().build(), logger);
		FileOps.deleteDirectory(nafcfg.getPathVar());
		FileOps.deleteDirectory(nafcfg.getPathTemp());
		FileOps.deleteDirectory(nafcfg.getPathLogs());
		// now create the real Dispatcher
		DispatcherDef def = new DispatcherDef.Builder()
				.withSurviveHandlers(false)
				.build();
		dsptch = Dispatcher.create(appctx, def, logger);
		AppConfig appcfg = AppConfig.get(nafcfg.getPath(pthnam_appcfg, null), dsptch);

		// Inject the messages into the queue for Forwarder to pick up.
		// Email addresses would be lower-cased by SMTP server before being submitted to Queue, so do same with our test data.
		com.grey.mailismus.mta.queue.Manager qmgr = QueueFactory.init(dsptch, appcfg, "initial-inject");
		for (int idx = 0; idx != msgs.length; idx++) {
			MessageSpec msg = msgs[idx];
			ByteChars sender = new ByteChars(msg.sender).toLowerCase();
			ArrayList<EmailAddress> recips = new ArrayList<>();
			MessageSpec.addRecips(recips, msg.recips_temperr);
			MessageSpec.addRecips(recips, msg.recips_permerr);
			MessageSpec.addRecips(recips, msg.recips_ok);
			com.grey.mailismus.mta.queue.SubmitHandle msgh = qmgr.startSubmit(sender, recips, null, IP.IP_LOCALHOST);
			msgh.write(new ByteChars("Header "+idx+"\r\n\r\n"));
			msgh.write(new ByteChars(msg.body+"\r\n"));
			boolean ok = qmgr.endSubmit(msgh, false);
			org.junit.Assert.assertTrue(ok);
		}
		boolean done = qmgr.stop();
		org.junit.Assert.assertTrue(done);

		// Set up the SMTP server
		// It needs to be configured to to use an alternative queue , to prevent it feeding messages back to the Forwarder in
		// an endless loop.
		XmlConfig cfg = XmlConfig.makeSection(nafxml_server, "x");
		com.grey.mailismus.mta.submit.SubmitTask stask = new com.grey.mailismus.mta.submit.SubmitTask("utest_smtps", dsptch, cfg);

		// set up the SMTP delivery component
		cfg = XmlConfig.makeSection(nafxml_client, "x");
		com.grey.mailismus.mta.MTA_Task ctask = new com.grey.mailismus.mta.MTA_Task("utest_smtpc", dsptch, cfg,
				Task.DFLT_FACT_DTORY, Task.DFLT_FACT_MS, MTA_Task.DFLT_FACT_QUEUE, stask.getResolverDNS());
		smtp_sender = new Forwarder(dsptch, ctask, ctask.taskConfig(), this, null, this);
		if (maxsrvconns != 0) {
			Object cproto = DynLoader.getField(smtp_sender, "protoClient");
			Object cshared = DynLoader.getField(cproto, "shared");
			DynLoader.setField(smtp_sender, "max_serverconns", maxsrvconns);
			DynLoader.setField(cshared, "max_serverconns", maxsrvconns);
		}
		if (maxmsgrecips != 0) DynLoader.setField(smtp_sender, "max_msgrecips", maxmsgrecips);
		if (interceptor_spec != null) {
			String ixml = "<intercept dns=\"Y\" address=\""+interceptor_spec+"\"/>";
			cfg = XmlConfig.makeSection(ixml, "intercept");
			Relay interceptor = new Relay(cfg, true, nafcfg, dsptch.getLogger());
			Object routing = DynLoader.getField(smtp_sender, "routing");
			DynLoader.setField(routing, "interceptor", interceptor);
		}

		// launch SMTP server and client in same Dispatcher thread
		actual_fwdstats.reset();
		actual_fwdbatchcnt = 0;
		fwd_errmsg = null;
		dsptch_failed = true;
		stask.startDispatcherRunnable();
		smtp_sender.start();
		dsptch.start(); //Dispatcher launches in separate thread
		Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(MAXRUNTIME, true);
		boolean c_stopped = smtp_sender.stop();
		boolean s_stopped = stask.stopDispatcherRunnable();
		org.junit.Assert.assertEquals(Dispatcher.STOPSTATUS.STOPPED, stopsts);
		org.junit.Assert.assertTrue(dsptch.completedOK());
		org.junit.Assert.assertFalse(dsptch_failed);
		org.junit.Assert.assertTrue(c_stopped);
		org.junit.Assert.assertTrue(s_stopped);

		Object cproto = DynLoader.getField(smtp_sender, "protoClient");
		Object cshared = DynLoader.getField(cproto, "shared");
		HashedMapIntInt activesrvconns = (HashedMapIntInt)DynLoader.getField(cshared, "active_serverconns");
		if (activesrvconns != null) org.junit.Assert.assertEquals(0, activesrvconns.size());

		// This is really a check on Dispatcher correctness, rather than the MTA
		@SuppressWarnings("unchecked")
		HashedMapIntKey<ChannelMonitor> activechannels = (HashedMapIntKey<ChannelMonitor>)DynLoader.getField(dsptch, "activeChannels");
		@SuppressWarnings("unchecked")
		Circulist<TimerNAF> activetimers = (Circulist<TimerNAF>)DynLoader.getField(dsptch, "activeTimers");
		@SuppressWarnings("unchecked")
		ObjectQueue<TimerNAF> pendingtimers = (ObjectQueue<TimerNAF>)DynLoader.getField(dsptch, "pendingTimers");
		@SuppressWarnings("unchecked")
		ObjectWell<TimerNAF> sparetimers = (ObjectWell<TimerNAF>)DynLoader.getField(dsptch, "timerPool");
		org.junit.Assert.assertEquals(0, activechannels.size());
		org.junit.Assert.assertEquals(0, activetimers.size());
		org.junit.Assert.assertEquals(0, pendingtimers.size());
		org.junit.Assert.assertEquals(sparetimers.size(), sparetimers.population());

		// now run the Reports task synchronously - note that Dispatcher is not running, but task is in one-shot mode
		cfg = XmlConfig.makeSection(nafxml_reports, "x");
		com.grey.mailismus.mta.reporting.ReportsTask rtask = new com.grey.mailismus.mta.reporting.ReportsTask("utest_smtprpt", dsptch, cfg, true);
		rtask.startDispatcherRunnable();
		boolean stopped = rtask.stopDispatcherRunnable();
		org.junit.Assert.assertTrue(stopped);

		//verify Audit logs
		String audit_ok = FileOps.readAsText(nafcfg.getPathLogs()+"/audit/delivered.log", null);
		String audit_permerr = FileOps.readAsText(nafcfg.getPathLogs()+"/audit/bounces.log", null);
		if (audit_ok == null) audit_ok = "";
		if (audit_permerr == null) audit_permerr = "";
		String errmsg = "";
		int expected_ok = 0;
		int expected_permerr = 0;
		int expected_temperr = 0;
		int expected_spoolcnt = 0;
		int expected_bouncecnt = 0;
		for (int idx = 0; idx != msgs.length; idx++) {
			MessageSpec msg = msgs[idx];
			if (msg.recips_ok != null) {
				for (int idx2 = 0; idx2 != msg.recips_ok.length; idx2++) {
					if (!audit_ok.contains("; To="+msg.recips_ok[idx2]+" ")) errmsg += "\nFailed to deliver "+msg.recips_ok[idx2];
				}
				expected_ok += msg.recips_ok.length;
			}
			if (msg.recips_permerr != null) {
				for (int idx2 = 0; idx2 != msg.recips_permerr.length; idx2++) {
					if (!audit_permerr.contains("; To="+msg.recips_permerr[idx2]+"; ")) errmsg += "\nFailed to bounce "+msg.recips_permerr[idx2];
				}
				expected_permerr += msg.recips_permerr.length;
				expected_bouncecnt++;
				expected_spoolcnt++;
			}
			if (msg.recips_temperr != null) {
				expected_temperr += msg.recips_temperr.length;
				expected_spoolcnt++;
			}
		}
		int actual_ok = StringOps.count(audit_ok, "\n");
		int actual_permerr = StringOps.count(audit_permerr, "\n");
		int actual_spoolcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/spool"), true);
		int srv_actual_spoolcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/spool_server"), true);
		int actual_bouncecnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/bounces"), false);
		// adjust spool counts for non-message files
		int diagcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/spool/ndrdiag"), true);
		actual_spoolcnt -= diagcnt;
		diagcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/spool_server/ndrdiag"), true);
		srv_actual_spoolcnt -= diagcnt;

		int actual_temperr = qmgr.qsize(com.grey.mailismus.mta.queue.Manager.SHOWFLAG_TEMPERR);
		int actual_ndrcnt = qmgr.qsize(com.grey.mailismus.mta.queue.Manager.SHOWFLAG_NEW);
		int srv_actual_submitcnt = stask.getQueue().qsize(com.grey.mailismus.mta.queue.Manager.SHOWFLAG_NEW);
		if (actual_temperr != -1) org.junit.Assert.assertEquals(expected_temperr, actual_temperr);
		if (actual_ndrcnt != -1) org.junit.Assert.assertEquals(expected_bouncecnt, actual_ndrcnt);
		if (srv_actual_submitcnt != -1) org.junit.Assert.assertEquals(server_submitcnt, srv_actual_submitcnt);

		if (qmgr.getClass().equals(com.grey.mailismus.mta.queue.queue_providers.filesystem.FilesysQueue.class)) {
			actual_temperr = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/queue/deferred"), true);
			actual_ndrcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/queue/incoming"), true);
			srv_actual_submitcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/queue_server/incoming"), true);
			org.junit.Assert.assertEquals(expected_temperr, actual_temperr);
			org.junit.Assert.assertEquals(expected_bouncecnt, actual_ndrcnt);
			org.junit.Assert.assertEquals(server_submitcnt, srv_actual_submitcnt);
		}
		if (ctask.getMS().getClass().equals(com.grey.mailismus.ms.maildir.MaildirStore.class)) {
			int actual_localcnt = FileOps.countFiles(new java.io.File(nafcfg.getPathVar()+"/ms"), true);
			int expect_localcnt = 0;
			for (int idx = 0; idx != expect_fwdstats.length; idx++) {expect_localcnt += (expect_fwdstats[idx].stats.localcnt - expect_fwdstats[idx].stats.localfailcnt);}
			org.junit.Assert.assertEquals(expect_localcnt, actual_localcnt);
		}
		org.junit.Assert.assertEquals(errmsg, 0, errmsg.length());
		org.junit.Assert.assertEquals(expected_ok, actual_ok);
		org.junit.Assert.assertEquals(expected_permerr, actual_permerr);
		org.junit.Assert.assertEquals(expected_spoolcnt, actual_spoolcnt);
		org.junit.Assert.assertEquals(server_spoolcnt, srv_actual_spoolcnt);
		org.junit.Assert.assertEquals(expected_bouncecnt, actual_bouncecnt);
		if (actual_fwdbatchcnt != expect_fwdstats.length) {
			org.junit.Assert.fail("Expected FwdBatches="+expect_fwdstats.length+" vs "+actual_fwdbatchcnt);
		} else {
			if (fwd_errmsg != null) org.junit.Assert.fail(fwd_errmsg);
		}
	}

	@Override
	public void entityStopped(Object obj)
	{
		if (obj != smtp_sender) return;
		dsptch.setTimer(100, TMRTYPE_STOP, this); //give server time to receive disconnect events
	}

	@Override
	public void batchCompleted(int qsize, Delivery.Stats stats)
	{
		if (qsize == 0) {
			boolean stopped = smtp_sender.stop();
			org.junit.Assert.assertTrue(stopped);
			entityStopped(smtp_sender); //because synchronous stop() won't result in call to entityStopped()
		} else {
			// crashing the Dispatcher here wouldn't yield friendly error messages, so don't assert these stats till later
			if (actual_fwdbatchcnt < expect_fwdstats.length && fwd_errmsg == null) {
				FwdStats expect = expect_fwdstats[actual_fwdbatchcnt];
				String msg = "";
				if (qsize != expect.qsize) msg += "; qsize="+qsize+" vs "+expect.qsize;
				if (stats.conncnt != expect.stats.conncnt) msg += "; conns="+stats.conncnt+" vs "+expect.stats.conncnt;
				if (stats.sendermsgcnt != expect.stats.sendermsgcnt) msg += "; msgs="+stats.sendermsgcnt+" vs "+expect.stats.sendermsgcnt;
				if (stats.remotecnt != expect.stats.remotecnt) msg += "; relay="+stats.remotecnt+" vs "+expect.stats.remotecnt;
				if (stats.remotefailcnt != expect.stats.remotefailcnt) msg += "; relayfail="+stats.remotefailcnt+" vs "+expect.stats.remotefailcnt;
				if (stats.localcnt != expect.stats.localcnt) msg += "; local="+stats.localcnt+" vs "+expect.stats.localcnt;
				if (stats.localfailcnt != expect.stats.localfailcnt) msg += "; localfail="+stats.localfailcnt+" vs "+expect.stats.localfailcnt;
				if (msg.length() != 0) fwd_errmsg = "Forwarder batch="+(actual_fwdbatchcnt+1)+": "+msg.substring(1);
			}
			actual_fwdbatchcnt++;
		}
	}

	@Override
	public void timerIndication(TimerNAF tmr, Dispatcher d) {
		if (tmr.getType() != TMRTYPE_STOP) return;
		if (d.isRunning()) dsptch_failed = false; //state that we stopped the Dispatcher, as opposed to it crashing out on error
		d.stop();
	}
	@Override
	public void eventError(TimerNAF tmr, Dispatcher d, Throwable ex) {}

	
	private static class MessageSpec
	{
		public final CharSequence sender;
		public final CharSequence[] recips_ok;
		public final CharSequence[] recips_temperr;
		public final CharSequence[] recips_permerr;
		public final CharSequence body;
		public MessageSpec(CharSequence s, CharSequence[] rok, CharSequence[] rtmp, CharSequence[] rperm, CharSequence b) {
			sender=s; recips_ok=rok; recips_temperr=rtmp; recips_permerr=rperm; body=b;
		}
		public static void addRecips(ArrayList<EmailAddress> lst, CharSequence[] recips) {
			if (recips == null) return;
			for (int idx = 0; idx != recips.length; idx++) {
				recips[idx] = recips[idx].toString().toLowerCase();
				lst.add(new EmailAddress(recips[idx]));
			}
		}
	}

	private static class TestMessageFilter implements MessageFilter {
		public TestMessageFilter() {}
		@Override
		public void approve(TSAP remote, ByteChars authuser, ByteChars helo_name,
				ByteChars sender, ArrayList<EmailAddress> recips, Path msg,
				FilterResultsHandler rproc) {
			if (sender.toString().startsWith("filter_fail_me@")) {
				throw new RuntimeException("Simulating failure in MessageFilter");
			}
			if (sender.toString().startsWith("filter_me@")) {
				rproc.rejected("550 Message filtered");
			} else {
				rproc.approved();
			}
		}
		@Override
		public void cancel() {}   
	}

	public static class TestFilterFactory implements FilterFactory {
		public TestFilterFactory(XmlConfig cfg) {}
		@Override
		public MessageFilter create() {
			return new TestMessageFilter();
		}
	}
}
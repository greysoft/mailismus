/*
 * Copyright 2015-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.utils.DynLoader;
import com.grey.base.collections.HashedSetInt;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.EventListenerNAF;
import com.grey.naf.NAFConfig;
import com.grey.naf.dns.resolver.ResolverConfig;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.TimerNAF;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.TestSupport;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.queue.MessageRecip;
import com.grey.mailismus.mta.smtp.MockServerDNS;

// Note that the older DeliveryTest suite includes a lot of test cases designed to test the Forwarder as well as the
// SMTP client and server entities, but it is a blunter interest, and this class allows a more precise exploration of
// the Forwarder's inputs and outputs.
public class ForwarderTest
	implements EventListenerNAF, Forwarder.BatchCallback
{
	private static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("no-such-logger");

	private static final String delivxml = "<deliver>"
			+"<interval_low>60m</interval_low>"
			+"<interval_high>60m</interval_high>"
			+"<delay_start>0</delay_start>"
			+"<x2maxconnections>2</x2maxconnections>"
			+"<x3maxconnections>3</x3maxconnections>"
			+"<x2maxserverconnections>2</x2maxserverconnections>"
			+"<x50maxserverconnections>50</x50maxserverconnections>"
			+"<relays>"
			+"<x1relay address=\"127.0.0.1:55168\"/>"
			+"<x2relay address=\"10.100.1.1\" senders=\"sender11@srcdomain1, srcdomain2\"/>"
			+"</relays>"
			+"</deliver>";

	private static final String[][] msgs1 = new String[][]{{"sender1", "recip1@domain2", "101", "2", "192.168.101.1"},
		{"sender1", "recip2@domain1", "101", "3", "192.168.101.1"},
		{"sender1", "recip3@domain1", "102", "1", "192.168.101.1"},
		{"sender1", "recip4@domain1", "101", "1", "192.168.101.1"}
	};
	private static final String[][] msgs2 = new String[][]{{"sender1", "recip1@domain1", "103", "1", "192.168.101.1"},
		{"sender1", "recip2@domain1", "101", "2", "192.168.101.1"},
		{"sender1", "recip3@domain1", "102", "1", "192.168.101.1"},
		{"sender1", "recip4@domain1", "101", "1", "192.168.101.1"}
	};
	private static final String[][] msgs3 = new String[][]{{"sender1", "recip1@domain2", "103", "1", "192.168.101.1"},
		{"sender1", "recip2@domain1", "101", "3", "192.168.101.1"},
		{"sender1", "recip3@domain1", "102", "1", "192.168.101.1"},
		{"sender1", "recip4@domain1", "101", "1", "192.168.101.1"}
	};
	private static final String[][] msgs4 = new String[][]{{"sender11", "recip1@domain1", "101", "1", "192.168.101.1"},
		{"sender11@srcdomain1", "recip2@domain1", "102", "1", "192.168.101.1"},
		{"sender11@srcdomain1", "recip3@domain1", "102", "2", "192.168.101.1"},
		{"sender11", "recip4@domain1", "103", "1", "192.168.101.1"}
	};
	private static final String[][] msgs5 = new String[][]{{"sender11@srcdomain1", "recip1@domain1", "101", "1", "192.168.101.1"},
		{"sender11@srcdomain1", "recip2@domain2", "102", "1", "192.168.101.1"},
		{"sender11@srcdomain1", "recip3@domain3", "103", "1", "192.168.101.1"},
		{"sender11@srcdomain1", "recip4@domain4", "103", "2", "192.168.101.1"},
		{"sender11@srcdomain2", "recip5@domain5", "104", "1", "192.168.101.1"}
	};

	private static MockServerDNS mockserver; //use mock server because failed queries much faster than timeouts

	private Dispatcher dsptch;
	private ResolverDNS dnsResolver;
	private Forwarder fwd;
	private boolean halted;
	String testname;

	@org.junit.Rule
	public final org.junit.rules.TestRule testwatcher = new org.junit.rules.TestWatcher() {
		@Override public void starting(org.junit.runner.Description d) {
			testname = d.getMethodName();
			System.out.println("Starting test="+d.getMethodName()+" - "+d.getClassName());
		}
	};

	@org.junit.BeforeClass
	public static void beforeClass() throws java.io.IOException {
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, true);
		mockserver = new MockServerDNS(appctx);
		mockserver.start();
	}

	@org.junit.AfterClass
	public static void afterClass() {
		if (mockserver != null) mockserver.stop();
	}

	@org.junit.Before
	public void setup() throws java.io.IOException {
		String nafxml = "<naf>"
				+"<baseport>"+NAFConfig.RSVPORT_ANON+"</baseport>"
				+"<dnsresolver>"
				+"<interceptor host=\"127.0.0.1\" port=\""+mockserver.getPort()+"\"/>"
				+"</dnsresolver></naf>";
		com.grey.naf.reactor.config.DispatcherConfig def = new com.grey.naf.reactor.config.DispatcherConfig.Builder()
				.withName("utest_fwd_"+testname)
				.withSurviveHandlers(false)
				.build();
		XmlConfig xmlcfg = XmlConfig.makeSection(nafxml, "/naf");
		NAFConfig nafcfg = new NAFConfig.Builder().withXmlConfig(xmlcfg).build();
		ResolverConfig rcfg = new ResolverConfig.Builder()
				.withXmlConfig(nafcfg.getNode("dnsresolver"))
				.build();
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, nafcfg, true);
		dsptch = Dispatcher.create(appctx, def, logger);
		dsptch.registerEventListener(this);
		dnsResolver = ResolverDNS.create(dsptch, rcfg);
	}

	// Use the real SMTP client as the sender, and its delivery fails as all the recipient domains are non-existent
	@org.junit.Test
	public void testRealClient_PermErr() throws Exception {
		String cfgxml = delivxml.replace("x3maxconnections", "maxconnections");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		//Add some more messages for final domain that will be leftover due to maxconnections=3 but will still get marked as
		//done and failed, as the error for the recips we did try was domain-wide.
		java.util.List<String[]> rdlst = java.util.Arrays.asList(msgs1);
		java.util.ArrayList<String[]> lst = new java.util.ArrayList<String[]>(rdlst); //asList() returns a read-only list
		lst.add(new String[]{"sender1", "recip11@domain2", "201", "1", "192.168.101.1"});
		lst.add(new String[]{"sender1", "recip12@domain2", "202", "1", "192.168.101.1"});
		String[][] msgs = lst.toArray(new String[lst.size()][]);
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs, false);
		AppConfig appcfg = AppConfig.get("", dsptch);
		fwd = new Forwarder(dsptch, cfg, appcfg, qmgr, null, this, null, this, dnsResolver);
		exec(qmgr, null, false);
	}

	@org.junit.Test
	public void testSmarthost() throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}, {101, 2}, {101, 3}}, {{102, 1}}};
		String cfgxml = delivxml.replace("x1relay", "relay");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs1, true);
		SenderFactory sndrfact = new SenderFactory(expected_senders, false);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, true);
	}

	@org.junit.Test
	public void testSenderRefill() throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}, {101, 2}}, {{102, 1}}};
		int[][][] expected_refills = new int[][][]{null, {{103, 1}}, null};
		String cfgxml = delivxml.replace("x2maxconnections", "maxconnections");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs2, true);
		SenderFactory sndrfact = new SenderFactory(expected_senders, expected_refills, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
	}

	// Unlike testSenderRefill() there will be no refill on messageCompleted() because the only remaining READY
	// MessageRecip entry is for a different domain. It will therefore remain undone after the batch completes.
	@org.junit.Test
	public void testLeftoverMessages() throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}, {101, 3}}, {{102, 1}}};
		String cfgxml = delivxml.replace("x2maxconnections", "maxconnections");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs3, true, 1);
		SenderFactory sndrfact = new SenderFactory(expected_senders, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
	}

	// One of the messages is left over during the initial cache scan as the maxserverconnections limit is reached,
	// so it gets picked up when one of the two initial MessageSenders calls messageCompleted() after processing its
	// first message, and gets refilled/populated.
	@org.junit.Test
	public void testServerConnections() throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}}, {{102, 1}, {102, 2}}};
		int[][][] expected_refills = new int[][][]{null, {{103, 1}}};
		String cfgxml = delivxml.replace("x2maxserverconnections", "maxserverconnections");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs4, true, 0);
		SenderFactory sndrfact = new SenderFactory(expected_senders, expected_refills, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
	}

	// Same as above, but a source-route kicks in for SPID=102, so maxserverconnections limit is never reached
	@org.junit.Test
	public void testMaxServerConnectionsWithSourceRoute() throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}}, {{102, 1}, {102,2}}, {{103, 1}}};
		String cfgxml = delivxml.replace("x2maxserverconnections", "maxserverconnections").replace("x2relay","relay");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs4, true, 0);
		SenderFactory sndrfact = new SenderFactory(expected_senders, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
	}

	@org.junit.Test
	public void testSourceRouteRefill() throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}}, {{102, 1}}};
		int[][][] expected_refills = new int[][][]{{{104, 1}}, {{103, 1}, {103, 2}}};
		String cfgxml = delivxml.replace("x2maxserverconnections", "maxserverconnections").replace("x2relay","relay");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs5, true, 0);
		SenderFactory sndrfact = new SenderFactory(expected_senders, expected_refills, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
	}

	// This is based on the manual Soak-Sink test. Just want to make sure entire cache is processed
	@org.junit.Test
	public void testBulk() throws Exception {
		String cfgxml = delivxml.replace("x50maxserverconnections", "maxserverconnections");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		int destcnt = 5;
		int maxserverconns = cfg.getInt("maxserverconnections", true, 0);
		org.junit.Assert.assertEquals(50, maxserverconns);
		java.util.ArrayList<String[]> lst = new java.util.ArrayList<String[]>();
		int spid = 0;
		for (int loop = 0; loop != 500; loop++) { //5x500 matches the expected cache capacity
			for (int dom = 1; dom <= destcnt; dom++) {
				String[] msg = {"tester"+dom+"@domain9.grey", "recip1@domain"+dom+".grey", Integer.toString(++spid), "1", "192.168.101.1"};
				lst.add(msg);
			}
		}
		String[][] msgs = lst.toArray(new String[lst.size()][]);
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs, true);
		SenderFactory sndrfact = new SenderFactory(null, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		com.grey.mailismus.mta.queue.Cache qc = (com.grey.mailismus.mta.queue.Cache)DynLoader.getField(fwd, "qcache");
		org.junit.Assert.assertEquals(2500, qc.capacity()); //the default for non-slaverelay mode
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
		org.junit.Assert.assertEquals(maxserverconns * destcnt, sndrfact.senders.size());
		for (int idx = 0; idx != sndrfact.senders.size(); idx++) {
			org.junit.Assert.assertEquals(10, sndrfact.senders.get(idx).msgcnt);
		}
	}

	@org.junit.Test
	public void testBulkMultiRecips() throws Exception {
		String cfgxml = delivxml.replace("x50maxserverconnections", "maxserverconnections");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		int destcnt = 5;
		int maxserverconns = cfg.getInt("maxserverconnections", true, 0);
		org.junit.Assert.assertEquals(50, maxserverconns);
		java.util.ArrayList<String[]> lst = new java.util.ArrayList<String[]>();
		int spid = 0;
		for (int loop = 0; loop != 250; loop++) { //5x250x2 matches the expected cache capacity
			for (int dom = 1; dom <= destcnt; dom++) {
				String sender = "tester"+dom+"@domain9.grey";
				String destdomain = "@domain"+dom+".grey";
				String spid_str = Integer.toString(++spid);
				for (int recip = 1; recip <= 2; recip++) {
					String[] msg = {sender, "recip"+recip+destdomain, spid_str, Integer.toString(recip), "192.168.101.1"};
					lst.add(msg);
				}
			}
		}
		String[][] msgs = lst.toArray(new String[lst.size()][]);
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs, true);
		SenderFactory sndrfact = new SenderFactory(null, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
		org.junit.Assert.assertEquals(maxserverconns * destcnt, sndrfact.senders.size());
		for (int idx = 0; idx != sndrfact.senders.size(); idx++) {
			org.junit.Assert.assertEquals(5, sndrfact.senders.get(idx).msgcnt); //2 recips per msg, so half the testBulk() figure
		}
	}

	@org.junit.Test
	public void testBulkSmarthost() throws Exception {
		String cfgxml = delivxml.replace("x1relay", "relay");
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "deliver");
		int destcnt = 5;
		java.util.ArrayList<String[]> lst = new java.util.ArrayList<String[]>();
		int spid = 0;
		for (int loop = 0; loop != 1000; loop++) { //5x1000 matches the expected cache capacity
			for (int dom = 1; dom <= destcnt; dom++) {
				String[] msg = {"tester"+dom+"@domain9.grey", "recip1@domain"+dom+".grey", Integer.toString(++spid), "1", "192.168.101.1"};
				lst.add(msg);
			}
		}
		String[][] msgs = lst.toArray(new String[lst.size()][]);
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs, true);
		SenderFactory sndrfact = new SenderFactory(null, true);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		com.grey.mailismus.mta.queue.Cache qc = (com.grey.mailismus.mta.queue.Cache)DynLoader.getField(fwd, "qcache");
		org.junit.Assert.assertEquals(5000, qc.capacity()); //the default for slaverelay mode
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, true);
		org.junit.Assert.assertEquals(500, sndrfact.senders.size()); //default maxconnections=500
		for (int idx = 0; idx != sndrfact.senders.size(); idx++) {
			org.junit.Assert.assertEquals(10, sndrfact.senders.get(idx).msgcnt);
		}
	}

	@org.junit.Test
	public void testSenderAllocation() throws Exception {
		testSenderAllocation(false);
	}

	@org.junit.Test
	public void testSenderAllocation_Deferred() throws Exception {
		testSenderAllocation(true);
	}

	private void testSenderAllocation(boolean deferred) throws Exception {
		int[][][] expected_senders = new int[][][]{{{101, 1}, {101, 3}}, {{101, 2}}, {{102, 1}}};
		XmlConfig cfg = XmlConfig.makeSection(delivxml, "deliver");
		MyQueueManager qmgr = new MyQueueManager(dsptch, msgs1, true);
		SenderFactory sndrfact = new SenderFactory(expected_senders, deferred);
		fwd = new Forwarder(dsptch, cfg, null, qmgr, null, this, sndrfact, sndrfact, dnsResolver);
		sndrfact.ctl = fwd;
		exec(qmgr, sndrfact, false);
	}

	private void exec(MyQueueManager qmgr, SenderFactory sndrfact, boolean slaverelay) {
		org.junit.Assert.assertEquals(slaverelay, fwd.getRouting().modeSlaveRelay());
		fwd.start();
		dsptch.start();
		Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(TimeOps.MSECS_PER_SECOND*20L, true);
		org.junit.Assert.assertEquals(qmgr.errmsg, Dispatcher.STOPSTATUS.STOPPED, stopsts);
		org.junit.Assert.assertTrue(qmgr.errmsg, dsptch.completedOK());
		org.junit.Assert.assertTrue(qmgr.errmsg, halted);
		if (qmgr.errmsg != null) org.junit.Assert.fail(qmgr.errmsg);
		if (sndrfact != null) {
			//can't test this in batchCompleted() because it's called before senderCompleted() returns
			org.junit.Assert.assertEquals(sndrfact.sender_cnt, sndrfact.sender_completion_cnt);
			org.junit.Assert.assertEquals(1, sndrfact.batch_completion_cnt);
		}
	}

	@Override
	public void eventIndication(Object obj, String eventId)
	{
		if (obj == dsptch) {
			halted = fwd.stop();
		} else if (obj == fwd) {
			halted = true;
		}
	}

	@Override
	public void batchCompleted(int qsize, Delivery.Stats stats) {
		dsptch.stop();
	}


	private static class MyQueueManager
		extends com.grey.mailismus.mta.queue.QueueManager
	{
		private final String[][] storedmsgs;
		private final boolean expect_success;
		private final int leftover_recips;
		public String errmsg;

		@Override
		public void determineOrphans(HashedSetInt candidates) {}
		@Override
		protected boolean storeMessage(com.grey.mailismus.mta.queue.SubmitHandle sph) {addQError("Unexpected storeMessages()"); return false;}
		@Override
		public int qsize(CharSequence sender, CharSequence recip, int flags) {return storedmsgs.length;}

		public MyQueueManager(Dispatcher d, String[][] m, boolean ok) throws java.io.IOException {
			this(d, m, ok, 0);
		}

		public MyQueueManager(Dispatcher d, String[][] m, boolean ok, int leftover) throws java.io.IOException {
			super(d, XmlConfig.NULLCFG, "utest_fwd");
			storedmsgs = m;
			expect_success = ok;
			leftover_recips = leftover;
		}

		@Override
		public void loadMessages(com.grey.mailismus.mta.queue.Cache cache, boolean get_bounces, boolean get_deferred) {
			if (get_bounces) addQError("Unexpected getBounces()");
			for (int idx = 0; idx != storedmsgs.length; idx++) {
				String[] msg = storedmsgs[idx];
				int qid = Integer.parseInt(msg[3]);
				int spid = Integer.parseInt(msg[2]);
				long recvtime = dsptch.getSystemTime();
				int iprecv = IP.convertDottedIP(msg[4]);
				ByteChars sndr = new ByteChars(msg[0]);
				EmailAddress recip = new EmailAddress(msg[1]);
				recip.decompose();
				cache.addEntry(qid, spid, recvtime, iprecv, sndr, recip.domain, recip.mailbox, 0, 0);
			}
		}

		@Override
		public void updateMessages(com.grey.mailismus.mta.queue.Cache cache, boolean expired) {
			if (cache.size() != storedmsgs.length) addQError("Updated cache="+cache.size()+" vs "+storedmsgs.length+" - "+cache);
			for (int idx = 0; idx != storedmsgs.length - leftover_recips; idx++) {
				MessageRecip recip = cache.get(idx);
				if (recip.qstatus != MessageRecip.STATUS_DONE) addQError("recip-"+idx+": qstatus not DONE - "+recip);
				if (expect_success) {
					if (recip.smtp_status != Protocol.REPLYCODE_OK) addQError("recip-"+idx+": smtp-status not OK - "+recip);
				} else {
					if (recip.smtp_status < Protocol.PERMERR_BASE) addQError("recip-"+idx+": smtp-status not PERMERR - "+recip);
				}
			}
			for (int idx = storedmsgs.length - leftover_recips; idx != storedmsgs.length; idx++) {
				MessageRecip recip = cache.get(idx);
				if (recip.qstatus != MessageRecip.STATUS_READY) addQError("recip-"+idx+": leftover qstatus not READY - "+recip);
			}
		}

		void addError(String s) {
			String pfx = (errmsg == null ? "" : errmsg+"\n");
			errmsg = pfx+s;
			org.junit.Assert.fail(s);
		}

		void addQError(String s) {
			addError("QueueManager: "+s);
		}
	}


	private static class MySender
		implements Delivery.MessageSender,
			TimerNAF.Handler
	{
		private final Delivery.MessageParams msgparams = new Delivery.MessageParams();
		private final SenderFactory mgr;
		private final int id;
		private TimerNAF tmr_report;
		private int seqno; //sequence number amongst Sender set - first Sender to be launched is zero
		public int msgcnt;

		@Override public String getLogID() {return "MySender-"+id;}
		@Override public Delivery.MessageParams getMessageParams() {return msgparams;}
		@Override public short getDomainError() {return 0;}
		@Override public void setEventListener(EventListenerNAF l) {}
		@Override public String toString() {return "MySender="+getLogID();}

		public MySender(int id, SenderFactory fact) {this.id=id; mgr=fact;}

		@Override
		public void start(Delivery.Controller ctl) {
			seqno = mgr.sender_cnt++;
			int[][] exp = null;
			if (mgr.expected_msgs != null) {
				if (seqno >= mgr.expected_msgs.length) {
					mgr.addError(seqno, "Only expected "+mgr.expected_msgs.length+" MessageSenders");
					return;
				}
				exp = mgr.expected_msgs[seqno];
			}
			processMessage(exp);
		}

		private void processMessage(int[][] exp) {
			if (exp != null) {
				if (msgparams.recipCount() != exp.length) mgr.addError(seqno, "recips="+msgparams.recipCount()+" vs "+exp.length);
			}
			for (int idx = 0; idx != msgparams.recipCount(); idx++) {
				MessageRecip recip = msgparams.getRecipient(idx);
				if (exp != null) {
					int exp_spid = exp[idx][0];
					int exp_qid = exp[idx][1];
					if (recip.spid != exp_spid) mgr.addError(seqno, "recip-"+idx+": SPID="+recip.spid+" vs "+exp_spid+" - "+recip);
					if (recip.qid != exp_qid) mgr.addError(seqno, "recip-"+idx+": QID="+recip.qid+" vs "+exp_qid+" - "+recip);
				}
				if (recip.qstatus != MessageRecip.STATUS_BUSY) mgr.addError(seqno, "recip-"+idx+" not BUSY - "+recip);
				recip.qstatus = MessageRecip.STATUS_DONE;
				recip.smtp_status = Protocol.REPLYCODE_OK;
			}
			msgcnt++;
			if (mgr.deferred_cb) {
				tmr_report = mgr.ctl.getDispatcher().setTimer(0, 0, this, Boolean.TRUE);
			} else {
				reportCompletion(true);
			}
		}

		private void reportCompletion(boolean report_msg) {
			if (report_msg) {
				int[][] exp = null;
				if (mgr.expected_refills != null && msgcnt == 1 && seqno < mgr.expected_refills.length) exp = mgr.expected_refills[seqno];
				mgr.ctl.messageCompleted(this);
				if (msgparams.recipCount() == 0) {
					if (exp != null) mgr.addError(seqno, "Expected another message after messageCompleted()");
				} else {
					//tests which are not interested in checking the payload would have null mgr.expected_msgs
					if (exp == null && mgr.expected_msgs != null) mgr.addError(seqno, "recips="+msgparams.recipCount()+" after messageCompleted() - "+msgparams.getRecipient(0));
					mgr.refill_cnt++;
					processMessage(exp);
					return;
				}
				if (mgr.deferred_cb) {
					tmr_report = mgr.ctl.getDispatcher().setTimer(0, 0, this, Boolean.FALSE);
					return;
				}
			}
			mgr.ctl.senderCompleted(this);
			mgr.sender_completion_cnt++;
		}

		@Override
		public boolean stop() {
			if (tmr_report != null) {
				tmr_report.cancel();
				tmr_report = null;
			}
			return true;
		}

		@Override
		public void timerIndication(TimerNAF tmr, Dispatcher d) {
			tmr_report = null;
			Boolean msg_only = (Boolean)tmr.getAttachment();
			reportCompletion(msg_only.booleanValue());
		}

		@Override
		public void eventError(TimerNAF tmr, Dispatcher d, Throwable ex) {
			mgr.addError(seqno, "Sender="+this+" has eventError="+ex);
		}
	}


	private static class SenderFactory
		implements com.grey.base.collections.GenericFactory<Delivery.MessageSender>,
			Forwarder.BatchCallback,
			TimerNAF.Handler
	{
		public final java.util.ArrayList<MySender> senders = new java.util.ArrayList<MySender>();
		public final int[][][] expected_msgs; //expected messages, per MessageSender and per MessageRecip
		public final int[][][] expected_refills; //expected message after possible refill in messageCompleted()
		public final boolean deferred_cb;
		public Delivery.Controller ctl;
		public int sender_cnt;
		public int sender_completion_cnt;
		public int batch_completion_cnt;
		public int refill_cnt;
		private int next_id;

		@Override
		public MySender factory_create() {
			MySender sender = new MySender(++next_id, this);
			senders.add(sender);
			return sender;
		}

		public SenderFactory(int[][][] exp, boolean deferred) {
			this(exp, null, deferred);
		}

		public SenderFactory(int[][][] exp, int[][][] refills, boolean deferred) {
			expected_msgs = exp;
			expected_refills = refills;
			deferred_cb = deferred;
		}

		void addError(int id, String s) {
			MyQueueManager qmgr = (MyQueueManager)ctl.getQueue();
			qmgr.addError("MessageSender="+id+": "+s);
		}

		@Override
		public void batchCompleted(int qsize, Delivery.Stats stats) {
			if (deferred_cb) {
				ctl.getDispatcher().setTimer(0, 0, this);
			} else {
				ctl.getDispatcher().stop();
			}
			if (expected_msgs != null) {
				int relaycnt = 0;
				for (int idx = 0; idx != expected_msgs.length; idx++) {
					int[][] sendermsgs = expected_msgs[idx];
					relaycnt += sendermsgs.length;
				}
				if (expected_refills != null) {
					for (int idx = 0; idx != expected_refills.length; idx++) {
						int[][] sendermsgs = expected_refills[idx];
						if (sendermsgs != null) relaycnt += sendermsgs.length;
					}
				}
				if (relaycnt != stats.remotecnt) addError(0, "Batch relaycnt="+stats.remotecnt+" vs expected="+relaycnt+" - "+stats);
				if (sender_cnt != expected_msgs.length) addError(0, "MessageSenders="+sender_cnt+" vs expected="+expected_msgs.length);
			}
			if (stats.localcnt+stats.localfailcnt+stats.remotefailcnt!=0) addError(0, "Unexpected batch stats - "+stats);
			if (sender_cnt != stats.conncnt) addError(0, "Batch conncnt="+stats.conncnt+" vs expected="+sender_cnt+" - "+stats);
			if (sender_cnt + refill_cnt != stats.sendermsgcnt) addError(0, "Batch msgcnt="+stats.sendermsgcnt+" vs refills="+refill_cnt+" - "+stats);
			batch_completion_cnt++;
		}

		@Override
		public void timerIndication(TimerNAF tmr, Dispatcher d) {
			ctl.getDispatcher().stop();
		}

		@Override
		public void eventError(TimerNAF tmr, Dispatcher d, Throwable ex) {
			addError(0, "SenderFactory has eventError="+ex);
		}
	}
}

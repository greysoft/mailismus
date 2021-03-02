/*
 * Copyright 2011-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.utils.DynLoader;
import com.grey.base.collections.HashedMapIntInt;
import com.grey.base.collections.HashedSet;
import com.grey.base.collections.HashedSetInt;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.DispatcherDef;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.TestSupport;
import com.grey.logging.Logger;

public abstract class ManagerTest
{
	private static final int BULKCACHESIZE = 29;
	private static final int DFLT_BULKMSGCNT = 5000;
	protected static final Logger logger = com.grey.logging.Factory.getLoggerNoEx("qmgrtest");

	private String qcfgxml = "<queue class='xclass'>"
			+"<spool><xmaxspidcache>-1</xmaxspidcache><xhardlinks>true</xhardlinks></spool>"
			+"</queue>";

	private final String testrootpath = TestSupport.initPaths(getClass());
	private Dispatcher dsptch;
	private Cache qcache;
	private int update_unprocessed_cnt; //number of unprocessed messages left behind by phase_update()
	private int update_deferred_cnt; //number of deferred messages (ie. temp errors) left behind by phase_update()
	private int spool_size;

	protected Manager qmgr;
	protected AppConfig appcfg;

	protected abstract Class<?> getQueueClass();
	protected boolean isTestSuiteRunnable() {return true;}
	protected boolean hasDatabase() {return false;}
	protected String getQueueConfig() {return null;}
	protected int getBulkMessageCount() {return DFLT_BULKMSGCNT;}
	protected boolean supportsHardLinks() {return true;}

	@org.junit.Rule
	public final org.junit.rules.TestRule testwatcher = new org.junit.rules.TestWatcher() {
		@Override public void starting(org.junit.runner.Description d) {
			System.out.println("Starting test="+d.getMethodName()+" - "+d.getClassName());
		}
	};

	@org.junit.Before
	public void init() throws java.io.IOException, java.net.URISyntaxException
	{
		if (!isTestSuiteRunnable()) return;
		qcfgxml = qcfgxml.replace("xclass", getQueueClass().getName());
		String qcfgxml_extra = getQueueConfig();
		if (qcfgxml_extra != null) qcfgxml = qcfgxml.replace("</queue>", qcfgxml_extra+"</queue>");
		String dname = "qmgrtest-"+getQueueClass().getName();
		ApplicationContextNAF appctx = ApplicationContextNAF.create(null);
		DispatcherDef def = new DispatcherDef.Builder().withName(dname).build();
		dsptch = Dispatcher.create(appctx, def, logger);
		FileOps.ensureDirExists(dsptch.getApplicationContext().getConfig().getPathTemp());
		appcfg = createAppConfig(dsptch, hasDatabase());
		qmgr = createManager(qcfgxml, "utest");
		org.junit.Assert.assertEquals(getQueueClass(), qmgr.getClass());
		HashedMapIntInt refcnt = (HashedMapIntInt)getSpoolField(qmgr, "spids_refcnt");
		org.junit.Assert.assertEquals(0, refcnt.size());
	}

	@org.junit.After
	public void shutdown() throws java.io.IOException
	{
		if (dsptch != null) dsptch.stop();
		if (qmgr != null) qmgr.stop();
		dsptch = null;
		qmgr = null;
	}

	@org.junit.Test
	public void testMessageLifecycle() throws Exception
	{
		org.junit.Assume.assumeTrue(isTestSuiteRunnable());
		org.junit.Assert.assertNotNull(getSpoolField(qmgr, "spids_refcnt"));
		org.junit.Assert.assertTrue((int)getSpoolField(qmgr, "max_spidrefs") > 0);
		org.junit.Assert.assertFalse(isHardLinked(qmgr));
		execMessageLifecycle();
		HashedMapIntInt refcnt = (HashedMapIntInt)ManagerTest.getSpoolField(qmgr, "spids_refcnt");
		org.junit.Assert.assertEquals(0, refcnt.size());
	}

	// This ensures that determineOrphans() works, as it wouldn't normally get exercised
	@org.junit.Test
	public void testMessageLifecycle_NoSpidRefcnt() throws Exception
	{
		org.junit.Assume.assumeTrue(isTestSuiteRunnable());
		qmgr.stop();
		qmgr = null;
		String xmlcfg = qcfgxml.replace("xmaxspidcache", "maxspidcache");
		qmgr = createManager(xmlcfg, "utest-nospidrefcnt");
		org.junit.Assert.assertNull(getSpoolField(qmgr, "spids_refcnt"));
		org.junit.Assert.assertEquals(-1, (int)getSpoolField(qmgr, "max_spidrefs"));
		org.junit.Assert.assertFalse(isHardLinked(qmgr));
		execMessageLifecycle();
	}

	@org.junit.Test
	public void testMessageLifecycle_HardLinkedSpools() throws Exception
	{
		boolean is_runnable = isTestSuiteRunnable() && supportsHardLinks() && platformSupportsHardLinks(testrootpath);
		org.junit.Assume.assumeTrue(is_runnable);
		qmgr.stop();
		qmgr = null;
		String xmlcfg = qcfgxml.replace("xhardlinks", "hardlinks");
		qmgr = createManager(xmlcfg, "utest-hardlinks");
		org.junit.Assert.assertTrue(isHardLinked(qmgr));
		org.junit.Assert.assertNull(getSpoolField(qmgr, "spids_refcnt"));
		org.junit.Assert.assertEquals(-1, (int)getSpoolField(qmgr, "max_spidrefs"));
		execMessageLifecycle();
	}

	@org.junit.Test
	public void testLoadSPIDs() throws Exception
	{
		org.junit.Assume.assumeTrue(isTestSuiteRunnable());
		HashedMapIntInt refcnt = (HashedMapIntInt)getSpoolField(qmgr, "spids_refcnt");
		//submit a multi-recipient message
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("user1a@domain1.org"));
		recips.add(new EmailAddress("user1b@domain1.org"));
		ByteChars sndr = new ByteChars("the.sender@somedomain.com");
		ByteChars msgbody = new ByteChars("This is message body 1\n");
		int spid1 = qmgr.submit(sndr, recips, null, IP.convertDottedIP("192.168.101.1"), msgbody);
		org.junit.Assert.assertTrue(spid1 != 0);
		int qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(2, qsz);
		org.junit.Assert.assertEquals(1, spoolSize(qmgr));
		org.junit.Assert.assertEquals(1, refcnt.size());
		//submit a single-recipient message
		recips.clear();
		recips.add(new EmailAddress("user2@domain2.org"));
		sndr = new ByteChars("the.sender@somedomain.com");
		msgbody = new ByteChars("This is message body 2\n");
		int spid2 = qmgr.submit(sndr, recips, null, IP.convertDottedIP("192.168.101.2"), msgbody);
		org.junit.Assert.assertTrue(spid2 != 0 && Math.abs(spid2-spid1) == 1);
		qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(3, qsz);
		org.junit.Assert.assertEquals(2, spoolSize(qmgr));
		org.junit.Assert.assertEquals(1, refcnt.size());
		org.junit.Assert.assertEquals(2, refcnt.get(spid1));
		printQueue("after testLoadSPIDs submissions");

		//close and reset, then make sure SPID refs are loaded on queue startup
		qmgr.stop();
		qmgr = null;
		refcnt.clear();
		qmgr = createManager(qcfgxml, "utest-loadspids");
		qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(3, qsz);
		org.junit.Assert.assertEquals(2, spoolSize(qmgr));
		org.junit.Assert.assertEquals(1, refcnt.size());
		org.junit.Assert.assertEquals(2, refcnt.get(spid1));
	}

	@org.junit.Test
	public void testBulkDelivery() throws Exception
	{
		final int bulkmsgcnt = getBulkMessageCount();
		org.junit.Assume.assumeTrue(isTestSuiteRunnable() && bulkmsgcnt != 0);
		final Manager qmgr_submit = qmgr;
		final HashedSetInt submitted_spids = new HashedSetInt();
		final HashedSet<ByteChars> submitted_recips = new HashedSet<ByteChars>();
		TestSupport.TestRunnerThread submit = new TestSupport.TestRunnerThread("utest-bulkdlv-submit") {
			@Override
			public void execute() throws java.io.IOException {
				java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
				int storecnt = 0;
				for (int loop = 0; loop != bulkmsgcnt; loop++) {
					recips.clear();
					recips.add(new EmailAddress("recip"+loop+"@elsewhere"+loop+".org"));
					ByteChars sender = new ByteChars("sender"+loop+"@somewhere.org");
					SubmitHandle sph = qmgr_submit.startSubmit(sender, recips, null, IP.IP_LOCALHOST);
					synchronized (submitted_spids) {
						submitted_spids.add(sph.spid);
						submitted_recips.add(sph.recips.get(0).full);
					}
					if (qmgr_submit.endSubmit(sph, false)) storecnt++;
				}
				org.junit.Assert.assertEquals(bulkmsgcnt, storecnt);
			}
		};
		TestSupport.TestRunnerThread delivery = new TestSupport.TestRunnerThread("utest-bulkdlv-dlv") {
			private final Manager qmgr2 = createManager(null, getName());
			private final Cache qcache2 = qmgr2.initCache(BULKCACHESIZE);
			private int loadcnt;
			@Override
			public void execute() throws java.io.IOException {
				int delivcnt = 0;
				int emptycnt = 0;
				while (emptycnt != 5) {
					int cnt = execBatch();
					delivcnt += cnt;
					if (cnt == 0) {
						if (delivcnt == bulkmsgcnt) emptycnt++;
					} else {
						emptycnt = 0;
					}
				}
				qmgr2.stop();
				org.junit.Assert.assertEquals(loadcnt, delivcnt);
			}

			private int execBatch() throws java.io.IOException {
				ByteChars bctmp = new ByteChars();
				qcache2.clear();
				qmgr2.getMessages(qcache2);
				if (qcache2.size() == 0) return 0;
				qcache2.sort();
				for (int idx = 0; idx != qcache2.size(); idx++) {
					MessageRecip mr = qcache2.get(idx);
					mr.qstatus = MessageRecip.STATUS_DONE;
					mr.smtp_status = Protocol.REPLYCODE_OK;
					bctmp.populate(mr.mailbox_to).append('@').append(mr.domain_to);
					synchronized (submitted_spids) {
						org.junit.Assert.assertTrue(mr.toString(), submitted_spids.remove(mr.spid));
						org.junit.Assert.assertTrue(mr.toString(), submitted_recips.remove(bctmp));
					}
				}
				loadcnt += qcache2.size();
				return qmgr2.messagesProcessed(qcache2);
			}
		};
		delivery.start();
		submit.start();
		long timelmt = System.currentTimeMillis() + (5 * TimeOps.MSECS_PER_MINUTE);
		submit.waitfor(timelmt - System.currentTimeMillis());
		delivery.waitfor(timelmt - System.currentTimeMillis());
		synchronized (submitted_spids) {
			org.junit.Assert.assertEquals(0, submitted_recips.size());
			org.junit.Assert.assertEquals(0, submitted_spids.size());
		}
		verifyEmptyQueue(qmgr);
	}

	@org.junit.Test
	public void testBulkFailure() throws Exception
	{
		final int bulkmsgcnt = getBulkMessageCount();
		org.junit.Assume.assumeTrue(isTestSuiteRunnable() && bulkmsgcnt != 0);
		final Manager qmgr_submit = qmgr;
		final HashedSetInt submitted_spids = new HashedSetInt();
		final HashedSet<ByteChars> submitted_recips = new HashedSet<ByteChars>();
		TestSupport.TestRunnerThread submit = new TestSupport.TestRunnerThread("utest-bulkfail-submit") {
			@Override
			public void execute() throws java.io.IOException {
				java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
				int storecnt = 0;
				for (int loop = 0; loop != bulkmsgcnt; loop++) {
					recips.clear();
					recips.add(new EmailAddress("goodrecip"+loop+"@elsewhere"+loop+".org"));
					recips.add(new EmailAddress("badrecip"+loop+"@elsewhere"+loop+".org"));
					ByteChars sender = new ByteChars("sender"+loop+"@somewhere.org");
					SubmitHandle sph = qmgr_submit.startSubmit(sender, recips, null, IP.IP_LOCALHOST);
					synchronized (submitted_spids) {
						submitted_spids.add(sph.spid);
						submitted_recips.add(sph.recips.get(0).full);
						submitted_recips.add(sph.recips.get(1).full);
					}
					if (qmgr_submit.endSubmit(sph, false)) storecnt++;
				}
				org.junit.Assert.assertEquals(bulkmsgcnt, storecnt);
			}
		};
		TestSupport.TestRunnerThread delivery = new TestSupport.TestRunnerThread("utest-bulkfail-dlv") {
			private final Manager qmgr2 = createManager(null, getName());
			private final Cache qcache2 = qmgr2.initCache(BULKCACHESIZE);
			private int loadcnt;
			@Override
			public void execute() throws java.io.IOException {
				final int exp_delivcnt = bulkmsgcnt*3; //x3 due to 2 recips plus 1 NDR per message
				int delivcnt = 0;
				int emptycnt = 0;
				while (emptycnt != 5) {
					int cnt = execBatch();
					delivcnt += cnt;
					if (cnt == 0) {
						if (delivcnt == exp_delivcnt) emptycnt++;
					} else {
						emptycnt = 0;
					}
				}
				qmgr2.stop();
				org.junit.Assert.assertEquals(loadcnt, delivcnt);
			}

			private int execBatch() throws java.io.IOException {
				ByteChars bctmp = new ByteChars();
				qcache2.clear();
				qmgr2.getMessages(qcache2);
				if (qcache2.size() == 0) return 0;
				qcache2.sort();
				for (int idx = 0; idx != qcache2.size(); idx++) {
					MessageRecip mr = qcache2.get(idx);
					mr.qstatus = MessageRecip.STATUS_DONE;
					if (mr.mailbox_to.startsWith("badrecip")) {
						mr.smtp_status = Protocol.PERMERR_BASE;
					} else {
						mr.smtp_status = Protocol.REPLYCODE_OK;
					}
					bctmp.populate(mr.mailbox_to).append('@').append(mr.domain_to);
					synchronized (submitted_spids) {
						submitted_spids.remove(mr.spid);
						submitted_recips.remove(bctmp);
					}
				}
				loadcnt += qcache2.size();
				return qmgr2.messagesProcessed(qcache2);
			}
		};
		TestSupport.TestRunnerThread reporting = new TestSupport.TestRunnerThread("utest-bulkfail-rpt") {
			private final Manager qmgr2 = createManager(null, getName());
			private final Cache qcache2 = qmgr2.initCache(BULKCACHESIZE);
			private int loadcnt;
			private int storecnt;
			@Override
			public void execute() throws java.io.IOException {
				int delivcnt = 0;
				int emptycnt = 0;
				while (emptycnt != 5) {
					int cnt = execBatch();
					delivcnt += cnt;
					if (cnt == 0) {
						if (delivcnt == bulkmsgcnt) emptycnt++;
					} else {
						emptycnt = 0;
					}
				}
				qmgr2.stop();
				org.junit.Assert.assertEquals(loadcnt, delivcnt);
				org.junit.Assert.assertEquals(bulkmsgcnt, storecnt);
			}

			private int execBatch() throws java.io.IOException {
				java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
				qcache2.clear();
				qmgr2.getBounces(qcache2);
				if (qcache2.size() == 0) return 0;
				qcache2.sort();
				for (int idx = 0; idx != qcache2.size(); idx++) {
					//submit NDR
					MessageRecip mr = qcache2.get(idx);
					mr.qstatus = MessageRecip.STATUS_DONE;
					recips.clear();
					recips.add(new EmailAddress(mr.sender));
					SubmitHandle sph = qmgr2.startSubmit(null, recips, null, 0);
					synchronized (submitted_spids) {
						submitted_spids.add(sph.spid);
						submitted_recips.add(sph.recips.get(0).full);
					}
					if (qmgr2.endSubmit(sph, false)) storecnt++;
				}
				loadcnt += qcache2.size();
				return qmgr2.bouncesProcessed(qcache2);
			}
		};
		reporting.start();
		delivery.start();
		submit.start();
		long timelmt = System.currentTimeMillis() + (5 * TimeOps.MSECS_PER_MINUTE);
		submit.waitfor(timelmt - System.currentTimeMillis());
		delivery.waitfor(timelmt - System.currentTimeMillis());
		reporting.waitfor(timelmt - System.currentTimeMillis());
		synchronized (submitted_spids) {
			org.junit.Assert.assertEquals(0, submitted_recips.size());
			org.junit.Assert.assertEquals(0, submitted_spids.size());
		}
		verifyEmptyQueue(qmgr);
	}

	private void execMessageLifecycle() throws Exception
	{
		// test an empty-queue fetch first
		verifyEmptyQueue(qmgr);
		qcache = qmgr.initCache(1);
		qmgr.getMessages(qcache);
		org.junit.Assert.assertEquals(0, qcache.size());
		verifyEmptyQueue(qmgr);

		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		int uniqcnt = phase_submit(recips);
		printQueue("after Submit");
		int delcnt = qmgr.housekeep();
		org.junit.Assert.assertEquals(0, delcnt);
		phase_load(uniqcnt);
		int bounce_cnt = phase_update();
		int ndrcnt = phase_bounces(bounce_cnt);
		delcnt = qmgr.housekeep();
		org.junit.Assert.assertEquals(0, delcnt);
		phase_drainqueue(ndrcnt);
		delcnt = qmgr.housekeep();
		org.junit.Assert.assertEquals(0, delcnt);
	}

	private int phase_submit(java.util.ArrayList<EmailAddress> recips) throws java.io.IOException
	{
		submitMessage(true, recips);
		verifyEmptyQueue(qmgr);
		int uniqcnt = submitMessage(false, recips);
		spool_size = (isHardLinked(qmgr) ? recips.size() : 1);
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));
		validateQueue(uniqcnt, uniqcnt, 0);
		return uniqcnt;
	}

	private void phase_load(int expectcnt) throws java.io.IOException
	{
		final int undersized_capacity = 2;
		qcache = qmgr.initCache(undersized_capacity);
		org.junit.Assert.assertEquals(0, qcache.size());
		qmgr.getMessages(qcache);
		org.junit.Assert.assertEquals(undersized_capacity, qcache.size());

		qcache.clear();
		org.junit.Assert.assertEquals(0, qcache.size());
		qcache = qmgr.initCache(100);
		qmgr.getMessages(qcache);
		org.junit.Assert.assertEquals(expectcnt, qcache.size());
	}

	private int phase_update() throws java.io.IOException
	{
		int recips_total = qcache.size();
		int updated_cnt = 0;
		int bounce_cnt = 0;

		for (int idx = 0; idx != recips_total; idx++) {
			MessageRecip recip = qcache.get(idx);
			java.nio.file.Path fh = qmgr.getMessage(recip.spid, recip.qid);
			org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
			if (StringOps.sameSeq("unprocessed.recip", recip.mailbox_to)) continue;
			recip.qstatus = MessageRecip.STATUS_DONE;
			recip.retrycnt += idx;
			updated_cnt++;

			if (StringOps.sameSeq("domain1.org", recip.domain_to)) {
				recip.smtp_status = Protocol.REPLYCODE_OK;
			} else if (StringOps.sameSeq("nosuchdomain.org", recip.domain_to)) {
				recip.smtp_status = Protocol.REPLYCODE_PERMERR_ADDR;
				bounce_cnt++;
			} else if (StringOps.sameSeq("unreachabledomain.org", recip.domain_to)) {
				recip.smtp_status = Protocol.REPLYCODE_TMPERR_CONN;
				update_deferred_cnt++;
			} else if (recip.domain_to == null) {
				if (StringOps.sameSeq("temp.bad.localuser", recip.mailbox_to)) {
					recip.smtp_status = Protocol.REPLYCODE_TMPERR_LOCAL;
					update_deferred_cnt++;
				} else {
					recip.smtp_status = Protocol.REPLYCODE_PERMERR_MISC;
					bounce_cnt++;
				}
			}
		}
		update_unprocessed_cnt = recips_total - updated_cnt;
		int cnt = qmgr.messagesProcessed(qcache);
		spool_size = (isHardLinked(qmgr) ? recips_total - updated_cnt + bounce_cnt + update_deferred_cnt : 1);
		int qsz = qmgr.qsize(Manager.SHOWFLAG_NEW);
		printQueue("after Update");
		org.junit.Assert.assertEquals(updated_cnt, cnt);
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));
		if (qsz != -1) {
			org.junit.Assert.assertEquals(recips_total - updated_cnt, qsz);
			org.junit.Assert.assertEquals(bounce_cnt, qmgr.qsize(Manager.SHOWFLAG_BOUNCES));
			org.junit.Assert.assertEquals(update_deferred_cnt, qmgr.qsize(Manager.SHOWFLAG_TEMPERR));
		}

		// if we reload the "ready" recipients, we should only get back the ones we didn't mark as done
		qcache.clear();
		qmgr.getMessages(qcache);
		org.junit.Assert.assertEquals(update_unprocessed_cnt, qcache.size());
		MessageRecip recip = qcache.get(0);
		org.junit.Assert.assertTrue(StringOps.sameSeq("unprocessed.recip", recip.mailbox_to));
		org.junit.Assert.assertTrue(StringOps.sameSeq("any.old.domain.org", recip.domain_to));
		validateQueue(update_deferred_cnt + update_unprocessed_cnt + bounce_cnt, update_unprocessed_cnt, bounce_cnt);
		for (int idx = 0; idx != qcache.size(); idx++) {
			recip = qcache.get(idx);
			java.nio.file.Path fh = qmgr.getMessage(recip.spid, recip.qid);
			org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
		}
		return bounce_cnt;
	}

	// In real life, these are the operations that would be done by the NDR thread
	private int phase_bounces(int bounce_cnt) throws java.io.IOException
	{
		ByteChars msgbody = new ByteChars("This is a bounce report\n");
		String duprecip = "duplicate.recip@anotherdomain.com";
		int total;

		// expire (remove) all but one of the bounces
		qcache.clear();
		qmgr.getBounces(qcache);
		org.junit.Assert.assertEquals(bounce_cnt, qcache.size());

		for (int idx = 0; idx < qcache.size() - 1; idx++) {
			MessageRecip recip = qcache.get(idx);
			recip.qstatus = MessageRecip.STATUS_DONE;
			java.nio.file.Path fh = qmgr.getMessage(recip.spid, recip.qid);
			org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
		}
		MessageRecip recip = qcache.get(qcache.size() - 1);
		java.nio.file.Path fh = qmgr.getMessage(recip.spid, recip.qid);
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
		String bounced_sender = (recip.sender == null ? null : recip.sender.toString());
		String bounced_recip_dom = (recip.domain_to == null ? null : recip.domain_to.toString());
		String bounced_recip_mbx = (recip.mailbox_to == null ? null : recip.mailbox_to.toString());
		int cnt = qmgr.bouncesProcessed(qcache);
		org.junit.Assert.assertEquals(bounce_cnt - 1, cnt);
		if (isHardLinked(qmgr)) spool_size -= (bounce_cnt - 1);
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));
		org.junit.Assert.assertEquals(0, qcache.size());
		printQueue("after Bounces-Expiry1");

		// reload the sole remaining bounce, and verify it is as we expected
		qmgr.getBounces(qcache);
		org.junit.Assert.assertEquals(1, qcache.size());
		recip = qcache.get(0);
		fh = qmgr.getMessage(recip.spid, recip.qid);
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
		org.junit.Assert.assertTrue(StringOps.sameSeq(recip.sender, bounced_sender));
		org.junit.Assert.assertTrue(StringOps.sameSeq(recip.domain_to, bounced_recip_dom));
		org.junit.Assert.assertTrue(StringOps.sameSeq(recip.mailbox_to, bounced_recip_mbx));
		// verify that no messages exist addressed to the sender of the last remaining bounced message, or our proposed duplicate recip
		total = qmgr.qsize(null, recip.sender, 0);
		if (total != -1) org.junit.Assert.assertEquals(0, total);
		total = qmgr.qsize(null, duprecip, 0);
		if (total != -1) org.junit.Assert.assertEquals(0, total);

		// submit the bounce report (fake body) corresponding to the remaining bounce
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		java.util.ArrayList<EmailAddress> duprecips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress(recip.sender));
		duprecips.add(new EmailAddress(duprecip));
		int ndrcnt_exp = 0;
		int spid = qmgr.submit(null, recips, null, IP.convertDottedIP("192.168.101.2"), msgbody);
		org.junit.Assert.assertTrue(spid != 0);
		fh = qmgr.getMessage(spid, 1);
		ndrcnt_exp++;
		spool_size++;
		int spid2 = qmgr.submitCopy(spid, 1, null, duprecips); //submit a copy of the report to duprecip as well
		org.junit.Assert.assertEquals(spid+2, spid2);
		ndrcnt_exp++;
		spool_size++;
		if (!isHardLinked(qmgr)) org.junit.Assert.assertEquals(3, spool_size); //sanity check
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));
		printQueue("after NDR-Submit");

		verifyExport(spid, 1, java.nio.file.Files.size(fh));
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));

		// reload pending messages to verify that the new report messages do exist
		total = qmgr.qsize(null, recip.sender, Manager.SHOWFLAG_NEW);
		if (total != -1) org.junit.Assert.assertEquals(1, total);
		total = qmgr.qsize(null, duprecip, Manager.SHOWFLAG_NEW);
		if (total != -1) org.junit.Assert.assertEquals(1, total);
		qcache.clear();
		qmgr.getMessages(qcache);
		int ndr_cnt = 0;
		boolean found_sndr = false;
		boolean found_dup = false;

		for (int idx = 0; idx != qcache.size(); idx++) {
			recip = qcache.get(idx);
			fh = qmgr.getMessage(recip.spid, recip.qid);
			org.junit.Assert.assertTrue("spool="+fh+" for "+recip, java.nio.file.Files.exists(fh));
			if (recip.sender != null) continue;
			ndr_cnt++;
			String addr = recip.mailbox_to.toString();
			if (recip.domain_to != null) addr += EmailAddress.DLM_DOM+recip.domain_to.toString();
			if (StringOps.sameSeq(addr, bounced_sender)) found_sndr = true;
			if (StringOps.sameSeq(addr, duprecip)) found_dup = true;
		}
		org.junit.Assert.assertEquals(ndrcnt_exp, ndr_cnt);
		org.junit.Assert.assertTrue(found_sndr);
		org.junit.Assert.assertTrue(found_dup);
		org.junit.Assert.assertEquals(ndrcnt_exp + update_unprocessed_cnt, qcache.size());

		// expire the original bounced message
		qcache.clear();
		qmgr.getBounces(qcache);
		org.junit.Assert.assertEquals(1, qcache.size());
		qcache.get(0).qstatus = MessageRecip.STATUS_DONE;
		cnt = qmgr.bouncesProcessed(qcache);
		org.junit.Assert.assertEquals(1, cnt);
		if (isHardLinked(qmgr)) spool_size--;
		if (!isHardLinked(qmgr)) org.junit.Assert.assertEquals(3, spool_size);
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));
		printQueue("after Bounces-Expiry2");
		// ...and verify it got removed from the queue
		qcache.clear();
		qmgr.getBounces(qcache);
		org.junit.Assert.assertEquals(0, qcache.size());
		org.junit.Assert.assertEquals(spool_size, spoolSize(qmgr));
		return ndrcnt_exp;
	}

	private void phase_drainqueue(int ndrcnt) throws java.io.IOException
	{
		// first verify how many pending messages we would obtain now
		qcache.clear();
		qmgr.getMessages(qcache);
		org.junit.Assert.assertEquals(update_unprocessed_cnt + ndrcnt, qcache.size());

		// now artificially advance our clock to get them all
		int proc_cnt = update_deferred_cnt + update_unprocessed_cnt + ndrcnt;
		TestSupport.setTime(dsptch, dsptch.getSystemTime()+TimeOps.MSECS_PER_DAY);
		qcache.clear();
		qmgr.getMessages(qcache);
		org.junit.Assert.assertEquals(proc_cnt, qcache.size());

		for (int idx = 0; idx != qcache.size(); idx++) {
			MessageRecip recip = qcache.get(idx);
			recip.qstatus = MessageRecip.STATUS_DONE;
			recip.smtp_status = Protocol.REPLYCODE_OK;
			java.nio.file.Path fh = qmgr.getMessage(recip.spid, recip.qid);
			org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
		}
		int cnt = qmgr.bouncesProcessed(qcache);
		org.junit.Assert.assertEquals(proc_cnt, cnt);
		spool_size = 0;
		verifyEmptyQueue(qmgr);
		printQueue("after Final-Delivery");

		// verify they're all gone
		TestSupport.setTime(dsptch, dsptch.getSystemTime()+TimeOps.MSECS_PER_DAY);
		qcache.clear();
		qmgr.getMessages(qcache);
		verifyEmptyQueue(qmgr);
	}

	private int submitMessage(boolean rollback, java.util.ArrayList<EmailAddress> recips) throws java.io.IOException
	{
		recips.clear();
		ByteChars sndr = new ByteChars("the.sender@somedomain.com");
		recips.add(new EmailAddress("good.user1@domain1.org"));
		recips.add(new EmailAddress("perm.bad.user1@nosuchdomain.org"));
		recips.add(new EmailAddress("temp.bad.localuser"));
		recips.add(new EmailAddress("good.user2@domain1.org"));
		recips.add(new EmailAddress("temp.bad.user1@unreachabledomain.org"));
		recips.add(new EmailAddress("unprocessed.recip@any.old.domain.org"));
		recips.add(new EmailAddress("perm.bad.user2@nosuchdomain.org"));
		recips.add(new EmailAddress("perm.bad.localuser"));
		recips.add(new EmailAddress("temp.bad.user2@unreachabledomain.org"));
		recips.add(new EmailAddress("perm.bad.user3@nosuchdomain.org"));
		int msgcnt = recips.size();
		ByteChars msgbody = new ByteChars("This is the message body\n");

		SubmitHandle msgh = qmgr.startSubmit(sndr, recips, null, IP.convertDottedIP("192.168.101.1"));
		int spid = msgh.spid;
		java.nio.file.Path fh = qmgr.getMessage(spid, msgcnt);
		msgh.write(msgbody);
		msgh.write(msgbody);
		boolean sts = qmgr.endSubmit(msgh, rollback);
		org.junit.Assert.assertTrue(sts);
		if (rollback) {
			org.junit.Assert.assertFalse(java.nio.file.Files.exists(fh));
		} else {
			org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
			org.junit.Assert.assertEquals(msgbody.length() * 2, java.nio.file.Files.size(fh));
		}

		if (!rollback) {
			verifyExport(spid, 1, java.nio.file.Files.size(fh));
			int total = qmgr.qsize(sndr.toString(), null, 0);
			if (total != -1) {
				org.junit.Assert.assertEquals(msgcnt, total);
				total = qmgr.qsize(sndr.toString()+"x", null, 0);
				org.junit.Assert.assertEquals(0, total);
			}
		}
		return msgcnt;
	}

	private void verifyExport(int spid, int qid, long expectsize) throws java.io.IOException
	{
		String exportpath = dsptch.getApplicationContext().getConfig().getPathVar()+"/exports";
		java.nio.file.Path fh = qmgr.exportMessage(spid, qid, exportpath);
		String pthnam = fh.toAbsolutePath().toString();
		exportpath = java.nio.file.Paths.get(exportpath).toAbsolutePath().toString();
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh));
		org.junit.Assert.assertEquals(expectsize, java.nio.file.Files.size(fh));
		org.junit.Assert.assertTrue(pthnam, pthnam.startsWith(exportpath));
	}

	private void validateQueue(int total_exp, int newcnt_exp, int bouncecnt_exp) throws java.io.IOException
	{
		StringBuilder diagtxt = new StringBuilder();
		int diagcnt = 0;
		if (qmgr.supportsShow()) {diagcnt = qmgr.show(null, null, 0, 0, diagtxt);}
		boolean ok = false;
		try {
			int total = qmgr.qsize(0);
			int newcnt = qmgr.qsize(Manager.SHOWFLAG_NEW);
			int bouncecnt = qmgr.qsize(Manager.SHOWFLAG_BOUNCES);
			if (total != -1) {
				org.junit.Assert.assertEquals(total_exp, total);
				org.junit.Assert.assertEquals(newcnt_exp, newcnt);
				org.junit.Assert.assertEquals(bouncecnt_exp, bouncecnt);
			}
			total = qmgr.qsize(null, "no'match", 0);
			if (total != -1) org.junit.Assert.assertEquals(0, total);
			total = qmgr.qsize(null, "bad.user", 0);
			if (total != -1) org.junit.Assert.assertEquals(5, total);
			ok = true;
		} finally {
			if (!ok) System.out.println("ValidateQueue="+diagcnt+": "+diagtxt);
		}
	}

	private void printQueue(String msg) throws java.io.IOException
	{
		if (!qmgr.supportsShow()) return;
		StringBuilder sb = new StringBuilder();
		int total = qmgr.show(null, null, 0, 0, sb);
		if (SysProps.get("grey.test.mta.printq", false)) {
			System.out.println("SHOWQ "+msg+": Total="+total+"\n"+sb);
		}
	}

	Manager createManager(String xmlcfg, String name) throws java.io.IOException
	{
		if (xmlcfg == null) xmlcfg = qcfgxml;
		XmlConfig qcfg = XmlConfig.makeSection(xmlcfg, "/queue");
		return QueueFactory.init(new QueueFactory(), dsptch, qcfg, appcfg, name);
	}

	private static AppConfig createAppConfig(Dispatcher dsptch, boolean withDB) throws java.io.IOException
	{
		String dbjar = (withDB ? SysProps.get(TestSupport.SYSPROP_DBJAR, null) : null);
		try {
			if (dbjar != null) DynLoader.load(dbjar);
		} catch (Throwable ex) {
			throw new java.io.IOException("Failed to load resources - DB-JAR="+dbjar, ex);
		}
		String dbcfg = (withDB ? "<database/>" : "");
		String cfgtxt = "<mailserver><application>"+dbcfg+"</application></mailserver>";
		String cfgfile =  dsptch.getApplicationContext().getConfig().getPathTemp()+"/mailismus-conf.xml";
		FileOps.writeTextFile(cfgfile, cfgtxt);
		return AppConfig.get(cfgfile, dsptch);
	}

	static void verifyEmptyQueue(Manager qmgr) throws java.io.IOException {
		verifyEmptySpool(qmgr);
		int qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(0, qsz);
	}

	static void verifyEmptySpool(Manager qmgr) {
		HashedMapIntInt refcnt = (HashedMapIntInt)ManagerTest.getSpoolField(qmgr, "spids_refcnt");
		org.junit.Assert.assertEquals(0, spoolSize(qmgr));
		if (refcnt != null) org.junit.Assert.assertEquals(refcnt.toString(), 0, refcnt.size());
	}

	static boolean isHardLinked(Manager qmgr) {
		return qmgr.getSpooler().isHardLinked();
	}

	static Object getSpoolField(Manager qmgr, String fldnam) {
		return DynLoader.getField(qmgr.getSpooler(), fldnam);
	}

	static int spoolSize(Manager qmgr) {
		java.nio.file.Path pth = (java.nio.file.Path)getSpoolField(qmgr, "dhroot");
		return FileOps.countFiles(pth.toFile(), true);
	}

	static boolean platformSupportsHardLinks(String pthnam) throws Exception
	{
		java.lang.reflect.Method meth = Spooler.class.getDeclaredMethod("verifySupportsHardLinks", new Class[]{java.nio.file.Path.class});
		meth.setAccessible(true);
		Exception ex_ln = (Exception)meth.invoke(null, new Object[]{java.nio.file.Paths.get(pthnam)});
		return (ex_ln == null);
	}
}
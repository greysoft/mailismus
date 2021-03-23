/*
 * Copyright 2016-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import java.util.concurrent.atomic.AtomicInteger;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.utils.FileOps;
import com.grey.base.collections.HashedSetInt;
import com.grey.base.config.XmlConfig;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.config.DispatcherConfig;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.TestSupport;
import com.grey.logging.Logger;

public class BaseManagerTest
{
	private static final Logger logger = com.grey.logging.Factory.getLoggerNoEx("qmgrbasetest");
	private final String testrootpath = TestSupport.initPaths(getClass());
	private final ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, true);

	private final Dispatcher dsptch;
	private QueueManager qmgr;

	public BaseManagerTest() throws Exception {
		dsptch = Dispatcher.create(appctx, new DispatcherConfig.Builder().withName("qmgrbasetest").build(), logger);
		FileOps.ensureDirExists(appctx.getConfig().getPathTemp());
	}

	@org.junit.After
	public void shutdown() throws java.io.IOException
	{
		if (qmgr != null) qmgr.stop();
		qmgr = null;
	}

	@org.junit.Test
	public void testSubmitFail() throws Exception
	{
		String xmlcfg = "<queue class='"+TestManager_SubmitFails.class.getName()+"'/>";
		execSubmitFail(xmlcfg);
	}

	@org.junit.Test
	public void testSubmitFail_Hardlinks() throws Exception
	{
		org.junit.Assume.assumeTrue(ManagerTest.platformSupportsHardLinks(testrootpath));
		String xmlcfg = "<queue class='"+TestManager_SubmitFails.class.getName()+"'>"
				+"<spool><hardlinks>true</hardlinks></spool>"
				+"</queue>";
		execSubmitFail(xmlcfg);
	}

	@org.junit.Test
	public void testCopyFail() throws Exception
	{
		String xmlcfg = "<queue class='"+TestManager.class.getName()+"'/>";
		XmlConfig qcfg = XmlConfig.makeSection(xmlcfg, "/queue");
		qmgr = QueueFactory.init(new QueueFactory(), dsptch, qcfg, null, "testqmgr-copyfail");
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1@domain1.org"));
		ByteChars sndr = new ByteChars("the.sender@somedomain.com");
		int spid = qmgr.submitCopy(1, 0, sndr, recips); //spid=1 should be non-existent, so copy will fail
		org.junit.Assert.assertEquals(0, spid);
		ManagerTest.verifyEmptyQueue(qmgr);
	}

	@org.junit.Test
	public void testCopy_HardLinks() throws Exception
	{
		org.junit.Assume.assumeTrue(ManagerTest.platformSupportsHardLinks(testrootpath));
		String xmlcfg = "<queue class='"+TestManager.class.getName()+"'>"
				+"<spool><hardlinks>true</hardlinks></spool>"
				+"</queue>";
		XmlConfig qcfg = XmlConfig.makeSection(xmlcfg, "/queue");
		qmgr = QueueFactory.init(new QueueFactory(), dsptch, qcfg, null, "testqmgr-copyhardlinks");
		//submit original message
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1a@domain1.org"));
		recips.add(new EmailAddress("recip1b@domain1.org"));
		int recipcnt1 = recips.size();
		ByteChars sndr = new ByteChars("sender@somedomain.com");
		ByteChars msgbody = new ByteChars("This is message body 1\n");
		int spid1 = qmgr.submit(sndr, recips, null, IP.convertDottedIP("192.168.101.1"), msgbody);
		org.junit.Assert.assertTrue(spid1 != 0);
		int qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(1, qsz);
		org.junit.Assert.assertEquals(recipcnt1, ManagerTest.spoolSize(qmgr));
		//now submit a copy of it
		recips.clear();
		recips.add(new EmailAddress("recip2a@domain1.org"));
		recips.add(new EmailAddress("recip2b@domain1.org"));
		recips.add(new EmailAddress("recip2c@domain1.org"));
		int recipcnt2 = recips.size();
		int spid2 = qmgr.submitCopy(spid1, 0, sndr, recips);
		org.junit.Assert.assertEquals(spid1+2, spid2);
		qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(2, qsz);
		org.junit.Assert.assertEquals(recipcnt1+recipcnt2, ManagerTest.spoolSize(qmgr));

		//make sure the initial message's spools are all links to each other
		java.nio.file.Path fh1 = qmgr.getMessage(spid1, 0);
		java.nio.file.Path fh = qmgr.getMessage(spid1, 1);
		org.junit.Assert.assertEquals(fh1, fh);
		org.junit.Assert.assertEquals(msgbody.length(), java.nio.file.Files.size(fh1));
		fh = qmgr.getMessage(spid1, 2);
		org.junit.Assert.assertNotEquals(fh1, fh);
		org.junit.Assert.assertTrue(java.nio.file.Files.isSameFile(fh, fh1));
		org.junit.Assert.assertEquals(msgbody.length(), java.nio.file.Files.size(fh));

		//now make sure the second message's spools are all linked to each other, but not to the first message
		java.nio.file.Path fh2 = qmgr.getMessage(spid2, 0);
		fh = qmgr.getMessage(spid2, 1);
		org.junit.Assert.assertEquals(fh2, fh);
		org.junit.Assert.assertEquals(msgbody.length(), java.nio.file.Files.size(fh2));
		org.junit.Assert.assertNotEquals(fh1, fh2);
		org.junit.Assert.assertFalse(java.nio.file.Files.isSameFile(fh1, fh2));
		for (int idx = 2; idx <= recipcnt2; idx++) {
			fh = qmgr.getMessage(spid2, idx);
			org.junit.Assert.assertNotEquals(fh2, fh);
			org.junit.Assert.assertTrue(java.nio.file.Files.isSameFile(fh, fh2));
			org.junit.Assert.assertEquals(msgbody.length(), java.nio.file.Files.size(fh));
		}
	}

	// this test fails on the creation of the initial spool file
	@org.junit.Test
	public void testSpoolerCollision() throws Exception
	{
		String xmlcfg = "<queue class='"+TestManager.class.getName()+"'/>";
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1@domain1.org"));
		execSpoolerCollision(xmlcfg, recips);
	}

	// this test fails on the creation of the final hard-link to the spoolfile
	@org.junit.Test
	public void testSpoolerCollision_HardLinkedSpools() throws Exception
	{
		org.junit.Assume.assumeTrue(ManagerTest.platformSupportsHardLinks(testrootpath));
		String xmlcfg = "<queue class='"+TestManager.class.getName()+"'>"
				+"<spool><hardlinks>true</hardlinks></spool>"
				+"</queue>";
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		for (int idx=0; idx<3; idx++) recips.add(new EmailAddress("recip"+idx+"@domain1.org"));
		execSpoolerCollision(xmlcfg, recips);
	}

	private void execSpoolerCollision(String xmlcfg, java.util.ArrayList<EmailAddress> recips) throws Exception
	{
		XmlConfig qcfg = XmlConfig.makeSection(xmlcfg, "/queue");
		qmgr = QueueFactory.init(new QueueFactory(), dsptch, qcfg, null, "testqmgr-spoolcollision");
		// create a file that will clash with next SPID
		int num_recips = recips.size();
		int next_spid = predictNextSPID(qmgr, num_recips);
		String rogue_body = "This is a rogue file";
		java.nio.file.Path fh_rogue = qmgr.getMessage(next_spid, num_recips);
		FileOps.ensureDirExists(FileOps.parentDirectory(fh_rogue));
		FileOps.writeTextFile(fh_rogue.toFile(), rogue_body, false);
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh_rogue));

		//submit a message - should collide on spool creation
		ByteChars sndr = new ByteChars("the.sender@somedomain.com");
		ByteChars msgbody = new ByteChars("This is the message body");
		try {
			qmgr.submit(sndr, recips, null, IP.convertDottedIP("192.168.101.1"), msgbody);
			org.junit.Assert.fail("Submit is expected to fail due to spool collision");
		} catch (java.io.IOException ex) {}
		int qsz = qmgr.qsize(0);
		if (qsz != -1) org.junit.Assert.assertEquals(0, qsz);
		org.junit.Assert.assertEquals(1, ManagerTest.spoolSize(qmgr)); //the rogue file
		for (int idx=1; idx<num_recips; idx++) org.junit.Assert.assertFalse(java.nio.file.Files.exists(qmgr.getMessage(next_spid, idx)));
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(fh_rogue));
		String txt = FileOps.readAsText(fh_rogue.toFile(), null);
		org.junit.Assert.assertEquals(rogue_body, txt);
	}

	private void execSubmitFail(String xmlcfg) throws Exception
	{
		XmlConfig qcfg = XmlConfig.makeSection(xmlcfg, "/queue");
		qmgr = QueueFactory.init(new QueueFactory(), dsptch, qcfg, null, "testqmgr-submitfail");
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1@domain1.org"));
		recips.add(new EmailAddress("recip2@domain1.org"));
		recips.add(new EmailAddress("recip3@domain1.org"));
		ByteChars sndr = new ByteChars("sender@somedomain.com");
		ByteChars msgbody = new ByteChars("The message body\n");
		try {
			qmgr.submit(sndr, recips, null, IP.convertDottedIP("192.168.101.1"), msgbody);
			org.junit.Assert.fail("Submit is expected to fail on "+qmgr);
		} catch (QException ex) {
			org.junit.Assert.assertTrue(ex.getMessage().startsWith("Submit failed on spid="));
		}
		ManagerTest.verifyEmptyQueue(qmgr);
	}

	private static int predictNextSPID(QueueManager qmgr, int recipcnt)
	{
		Object spidgen = ManagerTest.getSpoolField(qmgr, "spidgen");
		AtomicInteger gen = (AtomicInteger)DynLoader.getField(spidgen, recipcnt == 1 ? "nextspid_solo" : "nextspid_multi");
		return gen.get() + 2;
	}


	public static class TestManager_SubmitFails extends TestManager
	{
		public TestManager_SubmitFails(Dispatcher d, XmlConfig qcfg, AppConfig appcfg, String name) throws java.io.IOException {
			super(d, qcfg, appcfg, name);
		}
		@Override
		protected boolean storeMessage(SubmitHandle sph) {throw new QException("Simulating ctl-store failure");}
	}

	public static class TestManager extends QueueManager
	{
		public TestManager(Dispatcher d, XmlConfig qcfg, AppConfig appcfg, String name) throws java.io.IOException {
			super(d, qcfg, name);
		}
		@Override
		protected boolean storeMessage(SubmitHandle sph) {return true;}
		@Override
		protected void loadMessages(Cache cache, boolean get_bounces, boolean get_deferred) {}
		@Override
		protected void updateMessages(Cache cache, boolean is_bounces_batch) {}
		@Override
		protected void determineOrphans(HashedSetInt orphan_candidates) {}
	}
}
/*
 * Copyright 2013-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.TimeOps;
import com.grey.mailismus.TestSupport;
import com.grey.naf.ApplicationContextNAF;

public class SpoolerTest
{
	private static final String testrootpath = TestSupport.initPaths(SpoolerTest.class);
	private static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("no-such-logger");
	private final Spooler spool;
	private final java.io.File dhroot;
	private final java.lang.reflect.Method method_del;

	public SpoolerTest() throws Exception
	{
		String cfgpath = TestSupport.getResourcePath("/mtanaf.xml", getClass());
		com.grey.naf.NAFConfig nafcfg = com.grey.naf.NAFConfig.load(cfgpath);
		ApplicationContextNAF appctx = ApplicationContextNAF.create(null, nafcfg);
		FileOps.deleteDirectory(nafcfg.getPathVar());
		spool = new Spooler(appctx, XmlConfig.getSection(cfgpath, "naf"), logger, "utest");
		java.nio.file.Path pth = (java.nio.file.Path) DynLoader.getField(spool, "dhroot");
		dhroot = pth.toFile();
		method_del = Spooler.class.getDeclaredMethod("delete", new Class[] { java.nio.file.Path.class });
		method_del.setAccessible(true);
		org.junit.Assert.assertEquals(0, FileOps.countFiles(dhroot, true));
	}

	@org.junit.Test
	public void testCreate() throws Exception
	{
		// create a spool file for a single-recip message
		ByteChars sndr = new ByteChars("senderA");
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1A"));
		byte[] content = new byte[] { 1, 2 };
		SubmitHandle sph = spool.create(sndr, recips, null, 0, false);
		int spid1 = sph.spid;
		java.io.File fh1 = spool.getMessage(spid1, 0).toFile();
		org.junit.Assert.assertFalse(Spooler.isMultiSPID(spid1));
		org.junit.Assert.assertTrue(fh1.exists());
		org.junit.Assert.assertEquals(0, fh1.length());
		org.junit.Assert.assertEquals(1, FileOps.countFiles(dhroot, true));
		sph.write(content, 0, content.length);
		boolean ok = commit(spool, sph);
		org.junit.Assert.assertTrue(ok);
		org.junit.Assert.assertEquals(1, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertEquals(content.length, fh1.length());

		// create a spool file for a multi-recip message
		sndr = new ByteChars("senderB");
		recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1B"));
		recips.add(new EmailAddress("recip2B"));
		sph = spool.create(sndr, recips, null, 0, true);
		int spid2 = sph.spid;
		java.io.File fh2 = spool.getMessage(spid2, 0).toFile();
		org.junit.Assert.assertTrue(Spooler.isMultiSPID(spid2));
		org.junit.Assert.assertTrue(fh2.exists());
		org.junit.Assert.assertEquals(0, fh2.length());
		org.junit.Assert.assertEquals(2, FileOps.countFiles(dhroot, true));
		ok = commit(spool, sph);
		org.junit.Assert.assertTrue(ok);
		org.junit.Assert.assertEquals(2, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertEquals(0, fh2.length());
		org.junit.Assert.assertTrue(fh2.exists());
		org.junit.Assert.assertTrue(fh1.exists());

		// create another single-recip spool
		sndr = new ByteChars("senderC");
		recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1C"));
		sph = spool.create(sndr, recips, null, 0, false);
		int spid3 = sph.spid;
		java.io.File fh3 = spool.getMessage(spid3, 0).toFile();
		org.junit.Assert.assertFalse(Spooler.isMultiSPID(spid3));
		org.junit.Assert.assertTrue(fh3.exists());
		org.junit.Assert.assertEquals(3, FileOps.countFiles(dhroot, true));
		ok = commit(spool, sph);
		org.junit.Assert.assertTrue(ok);
		org.junit.Assert.assertEquals(3, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertTrue(fh1.exists());
		org.junit.Assert.assertTrue(fh2.exists());

		// create another multi-recip spool
		sndr = new ByteChars("senderD");
		recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1D"));
		recips.add(new EmailAddress("recip2D"));
		recips.add(new EmailAddress("recip3D"));
		sph = spool.create(sndr, recips, null, 0, true);
		int spid4 = sph.spid;
		java.io.File fh4 = spool.getMessage(spid4, 0).toFile();
		org.junit.Assert.assertTrue(Spooler.isMultiSPID(spid4));
		org.junit.Assert.assertTrue(fh4.exists());
		org.junit.Assert.assertEquals(4, FileOps.countFiles(dhroot, true));
		ok = commit(spool, sph);
		org.junit.Assert.assertTrue(ok);
		org.junit.Assert.assertEquals(4, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertTrue(fh1.exists());
		org.junit.Assert.assertTrue(fh2.exists());
		org.junit.Assert.assertTrue(fh3.exists());
		org.junit.Assert.assertTrue(fh4.exists());

		// exercise housekeeping, but don't delete anything
		int fcnt = FileOps.countFiles(dhroot, true);
		int delcnt = spool.housekeep(System.currentTimeMillis() - TimeOps.MSECS_PER_DAY);
		org.junit.Assert.assertEquals(0, delcnt);
		org.junit.Assert.assertEquals(fcnt, FileOps.countFiles(dhroot, true));

		// now delete all the spools, one by one
		deleteSpool(spid1, 0);
		org.junit.Assert.assertEquals(3, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertFalse(fh1.exists());
		org.junit.Assert.assertTrue(fh2.exists());
		org.junit.Assert.assertTrue(fh3.exists());
		org.junit.Assert.assertTrue(fh4.exists());

		boolean delsts = fh2.delete(); // manually delete spoolfile beforehand,
										// to test absence
		org.junit.Assert.assertTrue(delsts);
		deleteSpool(spid2, 0);
		org.junit.Assert.assertEquals(2, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertFalse(fh1.exists());
		org.junit.Assert.assertFalse(fh2.exists());
		org.junit.Assert.assertTrue(fh3.exists());
		org.junit.Assert.assertTrue(fh4.exists());

		deleteSpool(spid3, 99);
		org.junit.Assert.assertEquals(1, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertFalse(fh1.exists());
		org.junit.Assert.assertFalse(fh2.exists());
		org.junit.Assert.assertFalse(fh3.exists());
		org.junit.Assert.assertTrue(fh4.exists());

		deleteSpool(spid4, 1);
		org.junit.Assert.assertEquals(0, FileOps.countFiles(dhroot, true));
		org.junit.Assert.assertFalse(fh1.exists());
		org.junit.Assert.assertFalse(fh2.exists());
		org.junit.Assert.assertFalse(fh3.exists());
		org.junit.Assert.assertFalse(fh4.exists());
	}

	@org.junit.Test
	public void testExport() throws Exception {
		ByteChars sndr = new ByteChars("senderA");
		java.util.ArrayList<EmailAddress> recips = new java.util.ArrayList<EmailAddress>();
		recips.add(new EmailAddress("recip1A"));
		recips.add(new EmailAddress("recip1B"));
		byte[] content = new byte[] { 1, 2, 3, 4 };
		SubmitHandle sph = spool.create(sndr, recips, null, 0, false);
		int spid = sph.spid;
		sph.write(content, 0, content.length);
		boolean ok = commit(spool, sph);
		org.junit.Assert.assertTrue(ok);
		java.io.File exportdir = new java.io.File(testrootpath + "/nosuchdir");
		java.nio.file.Path exportpath = spool.export(spid, 2, exportdir.getAbsolutePath());
		java.io.File fh = exportpath.toFile();
		org.junit.Assert.assertEquals(content.length, fh.length());
		org.junit.Assert.assertEquals(exportdir, fh.getParentFile());
	}

	@org.junit.Test
	public void testSPID() {
		org.junit.Assert.assertEquals("0000" + "0000", spool.externalSPID(0).toString());
		org.junit.Assert.assertEquals("0000" + "0001", spool.externalSPID(1).toString());
		org.junit.Assert.assertEquals("FFFF" + "FFFF", spool.externalSPID(-1).toString());
		org.junit.Assert.assertEquals("12345678", spool.externalSPID(0x12345678).toString());
		org.junit.Assert.assertEquals("02345678", spool.externalSPID(0x2345678).toString());
		org.junit.Assert.assertEquals("80345678", spool.externalSPID(0x80345678).toString());
		org.junit.Assert.assertEquals("AB345679", spool.externalSPID(0xab345679).toString());
		org.junit.Assert.assertEquals("F0345678", spool.externalSPID(0xf0345678).toString());
	}

	private void deleteSpool(int spid, int qid) throws Exception {
		java.nio.file.Path fh = spool.getMessage(spid, qid);
		boolean ok = (boolean) method_del.invoke(spool, new Object[] { fh });
		org.junit.Assert.assertTrue(ok);
	}

	private static boolean commit(Spooler s, SubmitHandle sph) {
		boolean ok = sph.close(logger);
		s.releaseHandle(sph);
		return ok;
	}
}
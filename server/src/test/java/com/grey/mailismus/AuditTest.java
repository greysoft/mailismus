/*
 * Copyright 2012-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;

public class AuditTest
{
	private static final String logpath = TestSupport.initPaths(AuditTest.class)+"/audit.log";
	private static final String CFGDEF = "audit";

	private static final String cfgxml =
			"<parent>"
				+"<"+CFGDEF+" rot=\"never\">"+logpath+"</"+CFGDEF+">"
			+"</parent>";

	@org.junit.Test
	public void testON() throws java.io.IOException
	{
		java.io.File fh = new java.io.File(logpath);
		fh.delete();
		org.junit.Assert.assertFalse(fh.exists());

		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "/parent");
		Audit log = Audit.create("utest1", CFGDEF, null, cfg);
		java.io.File fh_actual = new java.io.File(log.getActivePath());
		org.junit.Assert.assertEquals(fh.getCanonicalPath(), fh_actual.getCanonicalPath());
		String pthnam_tmpl = log.getPath();
		org.junit.Assert.assertTrue(pthnam_tmpl.equals(fh_actual.getCanonicalPath()));

		String action = "my_action_99";
		long systime = System.currentTimeMillis();
		com.grey.mailismus.mta.queue.MessageRecip recip = new com.grey.mailismus.mta.queue.MessageRecip();
		recip.set(12, 13, systime-TimeOps.MSECS_PER_MINUTE-300, 1234,
				new ByteChars("sender1"), new ByteChars("domto1"), new ByteChars("mbx1"), (short)2, (short)99);
		log.log(action, recip, true, systime, "spid1");
		recip.set(13, 37, systime-999, 0, new ByteChars("sender2"), null, new ByteChars("mbx2"), (short)0, (short)0);
		recip.ip_send = 4324234;
		log.log("another-action", recip, false, systime, "spid2");
		systime += TimeOps.MSECS_PER_HOUR + 1;
		recip.sender = null;
		log.log("final-action", recip, false, systime, "spid3");
		log.close();
		log.close(); //make sure double-close is safe (albeit it we have no flusher)
		org.junit.Assert.assertTrue(fh.exists());
		org.junit.Assert.assertTrue(fh.length() != 0);
		String txt = FileOps.readAsText(fh, null);
		org.junit.Assert.assertTrue(txt.indexOf(action) != -1);
	}

	@org.junit.Test
	public void testOFF() throws java.io.IOException
	{
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "/parent");
		org.junit.Assert.assertTrue(cfg.exists());
		Audit log = Audit.create("utest2", "missing-config", null, cfg);
		org.junit.Assert.assertNull(log);

		String cfgxml2 = cfgxml.replace(">"+logpath+"<", "><");
		cfg = XmlConfig.makeSection(cfgxml2, "/parent");
		org.junit.Assert.assertTrue(cfg.exists());
		log = Audit.create("utest3", CFGDEF, null, cfg);
		org.junit.Assert.assertNull(log);
	}
}
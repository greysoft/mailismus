/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;

public class TranscriptTest
{
	private static final String logpath = TestSupport.initPaths(TranscriptTest.class)+"/transcript.log";
	private static final String CFGDEF = "transcript";

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
		Transcript log = Transcript.create(null, cfg, CFGDEF);

		String evtmsg = "This is a notable event";
		com.grey.base.utils.ByteChars bc = new com.grey.base.utils.ByteChars("Protocol Command blah");
		long systime = System.currentTimeMillis();

		// transcript is not opened until first write
		log.connection_in("E2", "192.168.100.1", 25001, "192.168.200.1", 25002, systime, true);
		java.io.File fh_actual = new java.io.File(log.getActivePath());
		org.junit.Assert.assertEquals(fh.getCanonicalPath(), fh_actual.getCanonicalPath());
	
		systime += TimeOps.MSECS_PER_MINUTE;
		log.data_out("E23", bc, systime);
		systime += TimeOps.MSECS_PER_MINUTE;
		log.event("E921", evtmsg, systime);
		systime += TimeOps.MSECS_PER_MINUTE;
		log.close(systime);
		systime += TimeOps.MSECS_PER_MINUTE;
		log.close(systime); //make sure double-close is safe (albeit it we have no flusher)
		org.junit.Assert.assertTrue(fh.exists());
		org.junit.Assert.assertTrue(fh.length() != 0);
		String txt = FileOps.readAsText(fh, null);
		org.junit.Assert.assertTrue(txt.indexOf(evtmsg) != -1);
	}

	@org.junit.Test
	public void testOFF() throws java.io.IOException
	{
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "/parent");
		org.junit.Assert.assertTrue(cfg.exists());
		Transcript log = Transcript.create(null, cfg, "missing-config");
		org.junit.Assert.assertNull(log);

		String cfgxml2 = cfgxml.replace(">"+logpath+"<", "><");
		cfg = XmlConfig.makeSection(cfgxml2, "/parent");
		org.junit.Assert.assertTrue(cfg.exists());
		log = Transcript.create(null, cfg, CFGDEF);
		org.junit.Assert.assertNull(log);
	}
}
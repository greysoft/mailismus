/*
 * Copyright 2011-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.IP;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.config.DispatcherConfig;
import com.grey.mailismus.DBHandle;
import com.grey.mailismus.TestSupport;

public class GreylistTest
{
	private static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("");
	static {
		TestSupport.initPaths(GreylistTest.class);
		TestSupport.loadDBDriver(logger);
	}
	private static final String WHITELIST_IP = "101.1.2.3";
	private static final String WHITELIST_FILE = "greywhite.txt";

	private static final String cfgxml =
		"<greylist>"
			+"<xwhitelist>"
				+"<sourcefile>"+NAFConfig.DIRTOKEN_VAR+"/"+WHITELIST_FILE+"</sourcefile>"
				+"<mem_threshold>1</mem_threshold>"
			+"</xwhitelist>"
			+"<quarantine_interval>5s</quarantine_interval>"
			+"<retry_interval>50s</retry_interval>"
			+"<expiry_interval>100s</expiry_interval>"
			+"<updates_freeze>10s</updates_freeze>"
			+"<xprimitive_ops>Y</xprimitive_ops>"
		+"</greylist>";

	private Greylist grylst;

	@org.junit.Test
	public void testSuite() throws java.io.IOException, java.net.URISyntaxException
	{
		org.junit.Assume.assumeTrue(TestSupport.HAVE_DBDRIVERS);
		String cfgpath = TestSupport.getResourcePath("/mtanaf.xml", getClass());
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, cfgpath, true, logger);
		FileOps.deleteDirectory(appctx.getNafConfig().getPathVar());
		XmlConfig dcfg = appctx.getNafConfig().getDispatcherConfigNode("testmtadispatcher1");
		DispatcherConfig def = DispatcherConfig.builder().withXmlConfig(dcfg).withAppContext(appctx).build();
		Dispatcher dsptch = Dispatcher.create(def);

		String pthnam = dsptch.getApplicationContext().getNafConfig().getPathVar()+"/"+WHITELIST_FILE;
		java.io.File fh = new java.io.File(pthnam);
		FileOps.writeTextFile(fh, WHITELIST_IP, false);

		try {
			// Test primitive DB ops
			String xml = cfgxml.replace("xprimitive_ops", "primitive_ops");
			int cnt = runTests(dsptch, xml, false, 0);
	
			// Test stored procedure
			// NB: This will be a repeat of primtives test if stored procs not supported by DB - that's ok, it still tests reopening existing DB
			xml = cfgxml.replace("xwhitelist", "whitelist");
			runTests(dsptch, xml, true, cnt);
		} finally {
			if (grylst != null) grylst.close();
			grylst = null;
		}
		dsptch.stop();
	}

	private int runTests(Dispatcher dsptch, String xml, boolean withWhitelist, int prevtotal)
			throws java.io.IOException
	{
		XmlConfig cfg = XmlConfig.makeSection(xml, "/greylist");
		final DBHandle.Type dbtype = new DBHandle.Type(cfg, dsptch.getApplicationContext().getNafConfig(), dsptch.getLogger());
		grylst = new Greylist(dsptch, dbtype, cfg);
		int cnt = grylst.reset();
		org.junit.Assert.assertEquals(prevtotal, cnt);

		int ip = IP.convertDottedIP("192.168.200.1");
		int ipwhite = IP.convertDottedIP(WHITELIST_IP);
		ByteChars addrfrom = new ByteChars("sender1@domain1.net");
		ByteChars addrto = new ByteChars("recip1@domain1.com");
		ByteChars addrto2 = new ByteChars("recip2@domain1.com");

		int[] totals = grylst.count();
		org.junit.Assert.assertEquals(0, totals[0]);
		org.junit.Assert.assertEquals(0, totals[1]);

		long time0 = System.currentTimeMillis();
		TestSupport.setTime(dsptch, time0);
		long validtill = grylst.lookup(ip, addrfrom, addrto);
		org.junit.Assert.assertEquals(Greylist.LOOKUP_ABSENT, validtill);
		boolean pass = grylst.vet(ip, addrfrom, addrto);
		totals = grylst.count();
		org.junit.Assert.assertFalse(pass);
		org.junit.Assert.assertEquals(1, totals[0]);
		org.junit.Assert.assertEquals(1, totals[1]);
		validtill = grylst.lookup(ip, addrfrom, addrto);
		printList("After first attempt at time0="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		org.junit.Assert.assertEquals(0, validtill);

		// repeat within quarantine period
		TestSupport.setTime(dsptch, dsptch.getSystemTime()+1);
		pass = grylst.vet(ip, addrfrom, addrto);
		printList("After rapid repeat at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertFalse(pass);
		org.junit.Assert.assertEquals(1, totals[0]);
		org.junit.Assert.assertEquals(1, totals[1]);

		if (withWhitelist)
		{
			// verify this has no effect on the greylist
			pass = grylst.vet(ipwhite, addrfrom, addrto2);
			totals = grylst.count();
			org.junit.Assert.assertTrue(pass);
			org.junit.Assert.assertEquals(1, totals[0]);
			org.junit.Assert.assertEquals(1, totals[1]);
			validtill = grylst.lookup(ipwhite, addrfrom, addrto2);
			org.junit.Assert.assertEquals(Greylist.LOOKUP_WHITE, validtill);
		}

		// insert another tuple - this tests a null sender, and it should also get greylisted
		validtill = grylst.lookup(ip, null, addrto2);
		org.junit.Assert.assertEquals(Greylist.LOOKUP_ABSENT, validtill);
		pass = grylst.vet(ip, null, addrto2);
		printList("After second tuple at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertFalse(pass);
		org.junit.Assert.assertEquals(2, totals[0]);
		org.junit.Assert.assertEquals(2, totals[1]);

		// Repeat tuple 1 after quarantine period
		// Recreate the recip-address to make sure SQL VARBINARY matches on contents, not memory adddress
		addrto = new ByteChars(addrto);
		long time1 = time0 + grylst.qtine_interval;
		TestSupport.setTime(dsptch, time1);
		pass = grylst.vet(ip, addrfrom, addrto);
		printList("After successful retry at time1="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertTrue(pass);
		org.junit.Assert.assertEquals(2, totals[0]);
		org.junit.Assert.assertEquals(1, totals[1]);
		validtill = grylst.lookup(ip, addrfrom, addrto);
		org.junit.Assert.assertTrue(validtill > 0);

		// repeat tuple 1 within updates-freeze interval
		TestSupport.setTime(dsptch, dsptch.getSystemTime() + grylst.updates_freeze - 1);
		pass = grylst.vet(ip, addrfrom, addrto);
		printList("After rapid follow-up at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertTrue(pass);
		org.junit.Assert.assertEquals(2, totals[0]);
		org.junit.Assert.assertEquals(1, totals[1]);

		// first purge - should do nothing
		cnt = grylst.purge();
		printList("After first purge at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertEquals(0, cnt);
		org.junit.Assert.assertEquals(2, totals[0]);
		org.junit.Assert.assertEquals(1, totals[1]);

		// second purge - should delete both, thus verifying that last repeat of tuple 1 didn't update the last-recv timestamp
		TestSupport.setTime(dsptch, time1 + grylst.expiry_interval);
		cnt = grylst.purge();
		printList("After second purge at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertEquals(2, cnt);
		org.junit.Assert.assertEquals(0, totals[0]);
		org.junit.Assert.assertEquals(0, totals[1]);

		// repeat first tuple, but this time do follow-up outside updates-freeze interval
		TestSupport.setTime(dsptch, time0);
		pass = grylst.vet(ip, addrfrom, addrto);
		org.junit.Assert.assertFalse(pass);
		TestSupport.setTime(dsptch, time1);
		pass = grylst.vet(ip, addrfrom, addrto);
		org.junit.Assert.assertTrue(pass);
		TestSupport.setTime(dsptch, dsptch.getSystemTime() + grylst.updates_freeze);
		pass = grylst.vet(ip, addrfrom, addrto);
		org.junit.Assert.assertTrue(pass);
		printList("After rewinding and refreshing last-recv at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));

		// purge again - this time our tuple should escape the chop, as it's last-recv time should have been updated above
		TestSupport.setTime(dsptch, time1 + grylst.expiry_interval);
		cnt = grylst.purge();
		printList("After final purge at time="+dsptch.getSystemTime()+" - "+TimeOps.makeTimeLogger(dsptch.getSystemTime(), null, true, false));
		totals = grylst.count();
		org.junit.Assert.assertEquals(0, cnt);
		org.junit.Assert.assertEquals(1, totals[0]);
		org.junit.Assert.assertEquals(0, totals[1]);

		grylst.close();
		grylst = null;
		return totals[0];
	}

	private void printList(String msg) throws java.io.IOException
	{
		StringBuilder sb = new StringBuilder();
		grylst.show(sb);

		if (com.grey.base.config.SysProps.get("grey.test.mta.printgreylist", false)) {
			System.out.println("SHOWLIST: "+msg+"\n:"+sb);
		}
	}
}
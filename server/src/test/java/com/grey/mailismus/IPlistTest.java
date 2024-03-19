/*
 * Copyright 2011-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import java.util.ArrayList;
import java.util.List;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.IP;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.DispatcherRunnable;
import com.grey.naf.reactor.TimerNAF;
import com.grey.naf.reactor.config.DispatcherConfig;
import com.grey.logging.Logger.LEVEL;

/*
 * Note that the Dispatcher is never started in this test, so don't expect to receive any events back from it
 */
public class IPlistTest
	implements TimerNAF.Handler
{
	static {
		TestSupport.initPaths(IPlistTest.class);
	}
	private static final String cfgxml =
		"<iplist>"
			+"<sourcefile>"+NAFConfig.PFX_CLASSPATH+"iplist-big.txt</sourcefile>"
			+"<mem_threshold>10</mem_threshold>"
			+"<hostnames>N</hostnames>"
		+"</iplist>";
	private static final String dname = "testmtadispatcher1";
	private static final int EXPSIZE_MEMTEST = 3;
	private static final int EXPSIZE_DBTEST = 14;

	private static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("");
	private static final DBHandle.Type setup_dbtype = TestSupport.loadDBDriver(logger);

	private final List<TimerNAF> timers = new ArrayList<>();
	private IPlist cur_iplist;
	private int expected_size;
	private volatile boolean reloaded;

	@org.junit.Test
	public void testMemorySync() throws java.io.IOException, java.net.URISyntaxException
	{
		String cfgpath = TestSupport.getResourcePath("/mtanaf.xml", getClass());
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, cfgpath, true, logger);
		XmlConfig cfg = appctx.getNafConfig().getNode("iplist");
		org.junit.Assert.assertNotNull(cfg);
		IPlist iplist = new IPlist("test_iplist_mem_sync", null, cfg, appctx, logger);
		org.junit.Assert.assertTrue(iplist.allowHostnames());
		commonChecks(iplist, EXPSIZE_MEMTEST);
		org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("105.1.2.3")));
		org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("112.1.2.3")));
		org.junit.Assert.assertTrue(iplist.toString().contains("memory="));
		iplist.reload();
		commonChecks(iplist, EXPSIZE_MEMTEST);
		org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("105.1.2.3")));
		org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("112.1.2.3")));
		iplist.close();
		iplist.close(); //test double close
	}

	@org.junit.Test
	public void testMemoryAsync() throws java.io.IOException, java.net.URISyntaxException
	{
		String cfgpath = TestSupport.getResourcePath("/mtanaf.xml", getClass());
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, cfgpath, true, logger);
		XmlConfig dcfg = appctx.getNafConfig().getDispatcherConfigNode(dname);
		DispatcherConfig def = DispatcherConfig.builder().withXmlConfig(dcfg).withAppContext(appctx).build();
		Dispatcher dsptch = Dispatcher.create(def);
		boolean ok = false;
		try {
			XmlConfig cfg = appctx.getNafConfig().getNode("iplist");
			org.junit.Assert.assertNotNull(cfg);
			IPlist iplist = new IPlist("test_iplist_mem_async", null, cfg, dsptch);
			org.junit.Assert.assertTrue(iplist.allowHostnames());
			commonChecks(iplist, EXPSIZE_MEMTEST);
			org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("105.1.2.3")));
			org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("112.1.2.3")));
			org.junit.Assert.assertTrue(iplist.toString().contains("memory="));
			DispatcherRunnable runnable = new DispatcherRunnable() {
				@Override
				public String getName() {return "IPlistTest.shutdown";}
				@Override
				public Dispatcher getDispatcher() {return dsptch;}
				@Override
				public boolean stopDispatcherRunnable() {
					for (TimerNAF t : timers) {
						t.cancel();
					}
					iplist.close();
					iplist.close();
					return true;
				}
			};
			dsptch.loadRunnable(runnable);
			reload(iplist, EXPSIZE_MEMTEST, dsptch);
			ok = true;
		} finally {
			try {
				Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(1000, true);
				org.junit.Assert.assertEquals(Dispatcher.STOPSTATUS.STOPPED, stopsts);
				org.junit.Assert.assertTrue(dsptch.completedOK());
			} catch (Throwable ex) {
				logger.log(LEVEL.ERR, ex, true, "Failed to close Dispatcher after testMemoryAsync");
				if (ok) throw ex;
			}
		}
	}

	@org.junit.Test
	public void testDBSync() throws java.io.IOException, java.net.URISyntaxException, NoSuchMethodException,
		IllegalAccessException, java.lang.reflect.InvocationTargetException
	{
		org.junit.Assume.assumeTrue(TestSupport.HAVE_DBDRIVERS);
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "/iplist");
		String cfgpath = TestSupport.getResourcePath("/mtanaf.xml", getClass());
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, cfgpath, true, logger);
		DBHandle.Type dbtype = setup_dbtype; //null means we will fail, but want to report the failure
		if (dbtype == null) dbtype = new DBHandle.Type(cfg, appctx.getNafConfig(), logger);
		IPlist iplist = new IPlist("test_iplist_db_sync", dbtype, cfg, appctx, logger);
		org.junit.Assert.assertFalse(iplist.allowHostnames());
		commonChecks(iplist, EXPSIZE_DBTEST);
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("105.1.2.3")));
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("112.1.2.3")));
		org.junit.Assert.assertTrue(iplist.toString().contains("database="));
		iplist.reload();
		commonChecks(iplist, EXPSIZE_DBTEST);
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("105.1.2.3")));
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("112.1.2.3")));
		iplist.close();
		iplist.close();
	}

	@org.junit.Test
	public void testDBAsync() throws java.io.IOException, java.net.URISyntaxException, NoSuchMethodException,
		IllegalAccessException, java.lang.reflect.InvocationTargetException
	{
		org.junit.Assume.assumeTrue(TestSupport.HAVE_DBDRIVERS);
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "/iplist");
		String cfgpath = TestSupport.getResourcePath("/mtanaf.xml", getClass());
		ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, cfgpath, true, logger);
		XmlConfig dcfg = appctx.getNafConfig().getDispatcherConfigNode(dname);
		DispatcherConfig def = DispatcherConfig.builder().withXmlConfig(dcfg).withAppContext(appctx).build();
		Dispatcher dsptch = Dispatcher.create(def);
		boolean ok = false;
		try {
			DBHandle.Type dbtype = setup_dbtype; //null means we will fail, but want to report the failure
			if (dbtype == null) dbtype = new DBHandle.Type(cfg, appctx.getNafConfig(), logger);
			IPlist iplist = new IPlist("test_iplist_db", dbtype, cfg, dsptch);
			org.junit.Assert.assertFalse(iplist.allowHostnames());
			commonChecks(iplist, EXPSIZE_DBTEST);
			org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("105.1.2.3")));
			org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("112.1.2.3")));
			org.junit.Assert.assertTrue(iplist.toString().contains("database="));
			DispatcherRunnable runnable = new DispatcherRunnable() {
				@Override
				public String getName() {return "IPlistTest.shutdown";}
				@Override
				public Dispatcher getDispatcher() {return dsptch;}
				@Override
				public boolean stopDispatcherRunnable() {
					for (TimerNAF t : timers) {
						t.cancel();
					}
					iplist.close();
					iplist.close();
					return true;
				}
			};
			dsptch.loadRunnable(runnable);
			reload(iplist, EXPSIZE_DBTEST, dsptch);
			ok = true;
		} finally {
			try {
				Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(1000, true);
				org.junit.Assert.assertEquals(Dispatcher.STOPSTATUS.STOPPED, stopsts);
				org.junit.Assert.assertTrue(dsptch.completedOK());
			} catch (Throwable ex) {
				logger.log(LEVEL.ERR, ex, true, "Failed to close Dispatcher after testDBAsync");
				if (ok) throw ex;
			}
		}
	}

	private void commonChecks(IPlist iplist, int expsize)
	{
		int netmask = iplist.getMask();
		org.junit.Assert.assertEquals(28, IP.maskToPrefix(netmask));
		org.junit.Assert.assertEquals(expsize, iplist.size());
		if (iplist.allowHostnames()) {
			org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("127.0.0.1")));
		} else {
			org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("127.0.0.1")));
		}
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("1.2.3.4")));
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("1.2.3.3")));
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("1.2.3.0"))); //due to netmask
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("1.2.3.1")));
		org.junit.Assert.assertTrue(iplist.exists(IP.convertDottedIP("192.168.100.255")));
		org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("1.2.3.64")));
		org.junit.Assert.assertFalse(iplist.exists(IP.convertDottedIP("213.101.102.103")));
	}

	private void reload(IPlist iplist, int expsize, Dispatcher dsptch) throws java.io.IOException
	{
		cur_iplist = iplist;
		expected_size = expsize;
		reloaded = false;
		timers.add(dsptch.setTimer(0, 1, this));
		dsptch.start();
		while (!reloaded) TimerNAF.sleep(50);
	}

	@Override
	public void timerIndication(TimerNAF tmr, Dispatcher dsptch)
			throws java.io.IOException
	{
		timers.remove(tmr);
		switch (tmr.getType())
		{
		case 1:
			cur_iplist.reload();
			cur_iplist.waitLoad();
			timers.add(dsptch.setTimer(100, 2, this)); //give IPlist time to receive its Producer notification
			break;
		case 2:
			reloaded = true;
			commonChecks(cur_iplist, expected_size);
			dsptch.stop();
			break;
		default:
			throw new RuntimeException("IPListTest: Missing case for timer-type="+tmr.getType());
		}
	}
}
/*
 * Copyright 2012-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.config.SysProps;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.FileOps;
import com.grey.base.ExceptionUtils;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.naf.nafman.NafManConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.errors.MailismusConfigException;

/*
 * Because JUnit will only create a single instance of a singleton across the unit tests of an entire Maven project,
 * we may need to recreate them for specific tests.
 */
public class TestSupport
{
	public static final String SYSPROP_DBJAR = "grey.test.dbjar";
	public static boolean HAVE_DBDRIVERS = true; //true means we may have the drivers, false means we definitely don't
	private static DBHandle.Type setup_dbtype;

	public static String initPaths(Class<?> clss) {
		String rootpath = SysProps.TMPDIR+"/utest/mailismus/"+clss.getPackage().getName()+"/"+clss.getSimpleName();
		SysProps.set(NAFConfig.SYSPROP_DIRPATH_ROOT, rootpath);
		SysProps.set(NAFConfig.SYSPROP_DIRPATH_CONF, null);
		SysProps.set(NAFConfig.SYSPROP_DIRPATH_VAR, null);
		SysProps.set(NAFConfig.SYSPROP_DIRPATH_LOGS, null);
		SysProps.set(NAFConfig.SYSPROP_DIRPATH_TMP, null);
		try {
			FileOps.deleteDirectory(rootpath);
		} catch (Exception ex) {
			throw new RuntimeException("TestSupport.initPaths failed to remove root="+rootpath+" - "+ex, ex);
		}
		return rootpath;
	}

	public static ApplicationContextNAF createApplicationContext(String name, String cfgpath, boolean withNafman) {
		NAFConfig nafcfg = new NAFConfig.Builder().withConfigFile(cfgpath).build();
		return createApplicationContext(name, nafcfg, withNafman);
	}

	public static ApplicationContextNAF createApplicationContext(String name, boolean withNafman) {
		NAFConfig nafcfg = new NAFConfig.Builder().withBasePort(NAFConfig.RSVPORT_ANON).build();
		return createApplicationContext(name, nafcfg, withNafman);
	}

	public static ApplicationContextNAF createApplicationContext(String name, NAFConfig nafcfg, boolean withNafman) {
		NafManConfig nafmanConfig = (withNafman ? new NafManConfig.Builder(nafcfg).build() : null);
		return ApplicationContextNAF.create(name, nafcfg, nafmanConfig);
	}

	public static void setTime(Dispatcher d, long millisecs) {
		DynLoader.setField(d, "systime_msecs", millisecs);
	}

	// NB: The concept of mapping a resource URL to a File is inherently flawed, but this utility works
	// beecause the resources we're looking up are in the same build tree.
	public static String getResourcePath(String path, Class<?> clss) throws java.io.IOException, java.net.URISyntaxException {
		java.net.URL url = DynLoader.getResource(path, clss);
		if (url == null) return null;
		return new java.io.File(url.toURI()).getCanonicalPath();
	}

	private synchronized static DBHandle.Type loadDBDriver(boolean optional, com.grey.logging.Logger logger) {
		if (!HAVE_DBDRIVERS || setup_dbtype != null) return setup_dbtype;
		String dbprop = SYSPROP_DBJAR;
		String dbjar = SysProps.get(dbprop, null);
		if (optional && dbjar == null) return null;
		Exception exlog = null;
		try {
			if (dbjar != null) DynLoader.load(dbjar);
			setup_dbtype = new DBHandle.Type(null, null, logger);
		} catch (MailismusConfigException ex) {
			if (dbjar == null && ExceptionUtils.getCause(ex, ClassNotFoundException.class) != null) {
				// forgive, with a warning
				System.out.println("**** WARNING: Ignoring database tests due to missing database drivers ****"
						+"\nTo run all tests, define path to DB-Driver JAR with "+dbprop);
				HAVE_DBDRIVERS = false;
			}
			exlog = ex;
		} catch (Exception ex) {
			exlog = ex;
		}
		if (exlog != null) {
			System.out.println("Initialisation failure on database tests for "+dbprop+"="+dbjar+" - "+exlog);
			if (HAVE_DBDRIVERS) exlog.printStackTrace(System.out);
		}
		return setup_dbtype;
	}

	public static DBHandle.Type loadDBDriver(com.grey.logging.Logger logger) {
		return loadDBDriver(false, logger);
	}


	public abstract static class TestRunnerThread extends Thread
	{
		private Throwable ex_error;
		abstract public void execute() throws Exception;

		public TestRunnerThread(String name) {
			setName(name);
		}

		@Override
		public void run() {
			try {
				execute();
			} catch (Throwable ex) {
				ex_error = ex;
			}
		}

		public void waitfor(long msecs) {
			if (msecs < 1) msecs = 1;
			try {join(msecs);} catch (InterruptedException ex) {}
			if (isAlive()) throw new IllegalStateException("Thread="+getName()+" has hung");
			if (ex_error != null) throw new RuntimeException("Thread="+getName()+" failed", ex_error);
		}
	}
}
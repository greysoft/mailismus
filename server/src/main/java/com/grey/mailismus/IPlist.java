/*
 * Copyright 2011-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.config.XmlConfig;

import java.io.IOException;

import com.grey.base.collections.HashedSetInt;
import com.grey.base.collections.IteratorInt;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.IP;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.TimerNAF;
import com.grey.naf.reactor.Producer;
import com.grey.logging.Logger.LEVEL;

public class IPlist
	implements Runnable,
		Producer.Consumer<HashedSetInt>,
		TimerNAF.Handler,
		FileOps.LineReader
{
	private static class LoadParams
	{
		LoadParams() {} //make explicit with non-private access, to eliminate synthetic accessor
		public HashedSetInt memlist;
		long systime;
		public java.sql.Connection cnx;
		public java.sql.PreparedStatement pstmt_add;
		public java.sql.PreparedStatement pstmt_update;
		public java.sql.PreparedStatement pstmt_purge;
	}

	private static final String TOKEN_TBLNAM = "_TBLNAM_";
	private static final char CMNT = '#';

	// Tried stuff like "INSERT INTO IPLIST (IP, UPDATED) select ?, ? WHERE NOT EXISTS (SELECT IP FROM IPLIST WHERE IP=?)"
	// but it didn't work.
	// In this case however, an add attempt which fails due to the uniqueness constraint on the IP column is faster than combining a Select
	// and Insert anyway.
	private static final String dflt_sqlcmd_add = "INSERT INTO "+TOKEN_TBLNAM
		+ " (IP, UPDATED)"
		+ " VALUES (?, ?)";
	private static final String dflt_sqlcmd_update = "UPDATE "+TOKEN_TBLNAM
		+ " SET UPDATED=?"
		+ " WHERE IP=?";
	private static final String dflt_sqlcmd_purge = "DELETE FROM "+TOKEN_TBLNAM
		+ " WHERE UPDATED<?";
	private static final String dflt_sqlcmd_exists = "SELECT IP FROM "+TOKEN_TBLNAM
		+ " WHERE IP=?";
	private static final String dflt_sqlcmd_size = "SELECT COUNT(*) AS SIZE FROM "+TOKEN_TBLNAM;
	private final String sqlcmd_add;
	private final String sqlcmd_update;
	private final String sqlcmd_purge;
	private final String sqlcmd_exists;
	private final String sqlcmd_size;

	private final HashedSetInt dbmemlist = new HashedSetInt(1, 1);  //null-feed marker
	private final Producer<HashedSetInt> updatesFeed;
	private final DBHandle db;
	private final java.sql.PreparedStatement pstmt_exists;
	private final java.sql.PreparedStatement pstmt_size;
	private final com.grey.logging.Logger log;

	private final String listname;
	private final java.net.URL srcpath;
	private final int memlimit;
	private final int netmask;
	private final boolean allow_hostnames;
	private final long reload_interval;
	private final int loadfactor;

	private TimerNAF tmr;
	private HashedSetInt memlist;
	private int db_size;
	private volatile Thread currentLoadThread;
	private volatile boolean is_closed;

	public int getMask() {return netmask;}
	public boolean allowHostnames() {return allow_hostnames;}

	public IPlist(String name, DBHandle.Type dbtype, XmlConfig cfg, Dispatcher dsptch)
			throws java.io.IOException
	{
		this(name, dbtype, cfg, dsptch.getApplicationContext(), dsptch, dsptch.getLogger());
	}

	public IPlist(String name, DBHandle.Type dbtype, XmlConfig cfg, ApplicationContextNAF appctx, com.grey.logging.Logger log)
			throws java.io.IOException
	{
		this(name, dbtype, cfg, appctx, null, log);
	}

	private IPlist(String name, DBHandle.Type dbtype, XmlConfig cfg, ApplicationContextNAF appctx, Dispatcher dsptch,
		com.grey.logging.Logger logger) throws java.io.IOException
	{
		listname = name;
		log = logger;

		srcpath = appctx.getConfig().getURL(cfg, "sourcefile", null, true, null, getClass());
		memlimit = cfg.getInt("mem_threshold", false, 0);
		loadfactor = cfg.getInt("hashfactor", false, 10);
		allow_hostnames = cfg.getBool("hostnames", true);
		reload_interval = cfg.getTime("interval", "6h");

		int netprefix = cfg.getInt("netprefix", false, 28);
		netmask = IP.prefixToMask(netprefix);

		String tblnam = "MMTA_"+listname.toUpperCase();
		String[] subold = new String[]{TOKEN_TBLNAM};
		String[] subnew = new String[]{tblnam};
		sqlcmd_add = setCommand(cfg, "sql_add", dflt_sqlcmd_add, subold, subnew);
		sqlcmd_update = setCommand(cfg, "sql_update", dflt_sqlcmd_update, subold, subnew);
		sqlcmd_purge = setCommand(cfg, "sql_purge", dflt_sqlcmd_purge, subold, subnew);
		sqlcmd_exists = setCommand(cfg, "sql_exists", dflt_sqlcmd_exists, subold, subnew);
		sqlcmd_size = setCommand(cfg, "sql_size", dflt_sqlcmd_size, subold, subnew);

		if (dsptch == null) {
			updatesFeed = null;
		} else {
			updatesFeed = new Producer<>("IPlist-updates", dsptch, this);

			TimerNAF.Handler onStart = new TimerNAF.Handler() {
				@Override
				public void timerIndication(TimerNAF t, Dispatcher d) throws IOException {
					updatesFeed.startDispatcherRunnable(); //need to call this within Dispatcher thread
				}
			};
			dsptch.setTimer(0, 0, onStart);
		}

		try {
			if (memlimit != 0) {
				// Install setup scripts, then prepare the SQL commands we will use in the main JDBC connection.
				// I was hoping to reconnect the main connection in read-only mode after installing the scripts, but (for both HSQLDB and H2
				// at any rate) that read-only is a database-wide setting, and a read-only connection cannot co-exist with simultaneous
				// read-write ones.
				db = new DBHandle(listname, dbtype, appctx, cfg, log);
				db.connect(false, true);
				db.executeScript("setup", getClass(), cfg, true, null, subold, subnew);
				// reconnect, as some embedded databases need to issue a shutdown after altering the database
				db.connect(false, false);
				pstmt_exists = db.prepareStatement(sqlcmd_exists);
				pstmt_size = db.prepareStatement(sqlcmd_size);
			} else {
				db = null;
				pstmt_exists = null;
				pstmt_size = null;
			}
			log.trace("Doing initial load of IPlist="+listname+" - Dispatcher="+(dsptch==null?"null":dsptch.getName()));
			acquire(load(0));
		} catch (java.sql.SQLException ex) {
			throw new java.io.IOException("Failed to load IP list="+listname, ex);
		}
	}

	// If loader thread is running, just leave it to fail when it signals 'updatesFeed'
	public void close()
	{
		if (is_closed) return;
		is_closed = true;

		if (db != null) {
			try {
				if (pstmt_exists != null) pstmt_exists.close();
				if (pstmt_size != null) pstmt_size.close();
				db.close();
			} catch (Exception ex) {
				log.log(LEVEL.TRC, ex, false, "IPlist="+listname+": Shutdown errors");
			}
		}
		if (updatesFeed != null) updatesFeed.stopDispatcherRunnable();
		if (tmr != null) tmr.cancel();
		tmr = null;
		memlist = null;
	}

	private HashedSetInt load(long systime) throws java.io.IOException, java.sql.SQLException
	{
		boolean success = false;
		LoadParams params = new LoadParams();
		params.memlist = new HashedSetInt(0, loadfactor);
		params.systime = systime;
		try {
			FileOps.readTextLines(srcpath.openStream(), this, 8192, null, 0, params);
			if (params.cnx != null) {
				// the database is the active list for this interval - purge entries that haven't been renewed by the current load
				params.pstmt_purge = params.cnx.prepareStatement(sqlcmd_purge);
				params.pstmt_purge.setLong(1, systime);
				params.pstmt_purge.executeUpdate();
			}
			success = true;
		} finally {
			if (params.cnx != null) {
				db.commit(params.cnx, success);
				if (params.pstmt_add != null) params.pstmt_add.close();
				if (params.pstmt_update != null) params.pstmt_update.close();
				if (params.pstmt_purge != null) params.pstmt_purge.close();
				db.close(params.cnx);
			}
		}
		return params.memlist;
	}

	@Override
	public boolean processLine(String line, int lno, int mode, Object cbdata) throws java.sql.SQLException
	{
		line = line.trim();
		if (line.length() == 0 || line.charAt(0) == CMNT) return false;
		LoadParams params = (LoadParams)cbdata;
		int ip;

		if (allow_hostnames) {
			try {
				ip = IP.parseIP(line);
			} catch (Exception ex) {
				log.log(LEVEL.TRC, ex, false, "IPlist="+listname+": Failed to resolve hostname on line="+lno+": "+line);
				return false;
			}
		} else {
			ip = IP.convertDottedIP(line);
			if (!IP.validDottedIP(line, ip)) return false;
		}
		ip &= netmask;

		if (memlimit != 0 && params.cnx == null && params.memlist.size() == memlimit) {
			// switch to database mode
			params.cnx = db.altConnection(false);
			params.pstmt_add = params.cnx.prepareStatement(sqlcmd_add);
			params.pstmt_update = params.cnx.prepareStatement(sqlcmd_update);
			IteratorInt iter = params.memlist.iterator();
			while (iter.hasNext()) dbAdd(params.pstmt_add, params.pstmt_update, iter.next(), params.systime);
			params.memlist = null;
		}

		if (params.cnx != null) {
			dbAdd(params.pstmt_add, params.pstmt_update, ip, params.systime);
		} else {
			params.memlist.add(ip);
		}
		return false;
	}

	public void reload() throws java.io.IOException
	{
		log.trace("Loading IPlist="+listname);
		if (updatesFeed == null) {
			try {
				HashedSetInt list = load(System.currentTimeMillis());
				acquire(list);
			} catch (java.sql.SQLException ex) {
				throw new java.io.IOException("Failed to reload IP list="+listname, ex);
			}
			return;
		}
		if (currentLoadThread != null) return;  // don't step on the toes of an existing reload
		currentLoadThread = new Thread(this, "IPlist-"+listname);
		currentLoadThread.start();
	}

	@Override
	public void run()
	{
		try {
			HashedSetInt new_memlist = load(System.currentTimeMillis());
			updatesFeed.produce(new_memlist == null ? dbmemlist : new_memlist);
		} catch (Exception ex) {
			log.info("IPlist="+listname+": Reload failed - "+com.grey.base.ExceptionUtils.summary(ex));
		}
		currentLoadThread = null;
	}

	@Override
	public void producerIndication(Producer<HashedSetInt> p)
	{
		HashedSetInt feed = null;
		HashedSetInt latest;
		while ((latest = updatesFeed.consume()) != null) {
			feed = latest;
		}
		acquire(feed == dbmemlist ? null : feed);
	}

	private void acquire(HashedSetInt new_memlist)
	{
		memlist = new_memlist;
		db_size = -1;
		log.info("Loaded IPlist="+listname+"/"+IP.maskToPrefix(netmask)+", size="+size()+"/"+memlimit+" from "+srcpath
				+" - mode="+(memlist==null ? "database" : "memory"));

		if (updatesFeed != null && reload_interval != 0) {
			// schedule next reload
			if (tmr != null) tmr.cancel();
			tmr = updatesFeed.getDispatcher().setTimer(reload_interval, 0, this);
		}
	}

	public boolean waitLoad()
	{
		Thread loader = currentLoadThread;
		if (loader == null) return false;
		try {
			loader.join();
		} catch (InterruptedException ex) {
			waitLoad();
		}
		return true;
	}

	public boolean exists(int ip)
	{
		ip &= netmask;
		if (memlist != null) return memlist.contains(ip);
		if (pstmt_exists == null) return false;
		java.sql.ResultSet rs = null;
		boolean found = false;

		try {
			pstmt_exists.clearParameters();
			pstmt_exists.setInt(1, ip);
			rs = pstmt_exists.executeQuery();
			found = rs.next();
		} catch (Exception ex) {
			log.log(LEVEL.INFO, ex, false, "IPlist="+listname+": getExists errors - "+pstmt_exists);
		}
		if (rs != null) try {rs.close();} catch (Exception ex) {log.log(LEVEL.TRC, ex, false, "IPlist="+listname+": closing Exists RS");}
		return found;
	}

	public int size()
	{
		if (memlist != null) return memlist.size();
		if (db_size != -1) return db_size;
		if (pstmt_size == null) return -1;
		java.sql.ResultSet rs = null;
		int cnt = -1;

		try {
			rs = pstmt_size.executeQuery();
			rs.next();
			cnt = rs.getInt("SIZE");
		} catch (Exception ex) {
			log.log(LEVEL.INFO, ex, false, "IPlist="+listname+": getSize errors - "+pstmt_size);
		}
		if (rs != null) try {rs.close();} catch (Exception ex) {log.log(LEVEL.TRC, ex, false, "IPlist="+listname+": closing Size RS");}
		db_size = cnt;
		return cnt;
	}

	private boolean dbAdd(java.sql.PreparedStatement pstmt_addnew, java.sql.PreparedStatement pstmt_update,
			int ip, long systime) throws java.sql.SQLException
	{
		int cnt = 0;

		if (pstmt_update != null) {
			pstmt_update.clearParameters();
			pstmt_update.setLong(1, systime);
			pstmt_update.setInt(2, ip);
			cnt = pstmt_update.executeUpdate();
		}

		if (cnt == 0) {
			pstmt_addnew.clearParameters();
			pstmt_addnew.setInt(1, ip);
			pstmt_addnew.setLong(2, systime);
			pstmt_addnew.executeUpdate();
		}
		return true;
	}

	private String setCommand(XmlConfig cfg, String cfgname, String cmd, String[] subold, String[] subnew)
	{
		cmd = cfg.getValue(cfgname, true, cmd);
		if (subold != null) {
			for (int idx = 0; idx != subold.length; idx++) {
				cmd = cmd.replace(subold[idx], subnew[idx]);
			}
		}
		return cmd;
	}

	@Override
	public void timerIndication(TimerNAF t, Dispatcher d) throws java.io.IOException
	{
		tmr = null; //Timer param is expected to be tmr, don't bother checking
		reload();
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(15);
		String txt = getClass().getName()+"="+listname+"/size="+size()+"/memlimit="+memlimit+"/";
		txt += "allowhosts="+allow_hostnames+"/reload="+TimeOps.expandMilliTime(reload_interval,sb,true)+"/";
		txt += "netmask="+IP.maskToPrefix(netmask)+"/";
		if (memlist == null) {
			txt += "database="+db;
		} else {
			IteratorInt it = memlist.iterator();
			String dlm = "memory={";
			while (it.hasNext()) {
				int ip = it.next();
				sb.setLength(0);
				txt += dlm + IP.displayDottedIP(ip, sb);
				dlm = ",";
			}
			txt += "}";
		}
		return txt +" - URL="+srcpath;
	}
}
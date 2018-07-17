/*
 * Copyright 2011-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit;

import com.grey.logging.Logger.LEVEL;
import com.grey.base.utils.TimeOps;
import com.grey.mailismus.DBHandle;
import com.grey.mailismus.errors.MailismusStorageException;

final class Greylist
	implements com.grey.naf.reactor.TimerNAF.Handler
{
	public static final long LOOKUP_ABSENT = -1;
	public static final long LOOKUP_WHITE = -2;

	private static final String TBLNAM_GREYLIST = "MMTA_GREYLIST";
	private static final String listname = "greylist";

	private static final String sqlcmd_vet = TBLNAM_GREYLIST+"_VET(?, ?, ?, ?, ?, ?)";
	private static final String sqlcmd_fetch = "SELECT *"
		+" FROM "+TBLNAM_GREYLIST
		+" WHERE IP_FROM = ? AND MAILADDR_FROM = ? AND MAILADDR_TO = ?";
	private static final String sqlcmd_create = "INSERT INTO "+TBLNAM_GREYLIST
		+" (IP_FROM, CREATED, MAILADDR_FROM, MAILADDR_TO, QTINE_TILL)"
		+" VALUES (?, ?, ?, ?, ?)";
	private static final String sqlcmd_promote = "UPDATE "+TBLNAM_GREYLIST
		+" SET QTINE_TILL = null, LAST_RECV = ?"
		+" WHERE ID = ?";
	private static final String sqlcmd_refresh = "UPDATE "+TBLNAM_GREYLIST
		+" SET LAST_RECV = ?"
		+" WHERE ID = ?";
	private static final String sqlcmd_purge = "DELETE FROM "+TBLNAM_GREYLIST
		+" WHERE (QTINE_TILL IS NOT NULL AND QTINE_TILL <= ?)"
		+" OR (LAST_RECV IS NOT NULL AND LAST_RECV <= ?)";
	private static final String sqlcmd_clear = "DELETE FROM "+TBLNAM_GREYLIST;

	public final long qtine_interval;
	public final long retry_interval;
	public final long expiry_interval;
	private final long purge_interval;
	public final long updates_freeze; //prevents excessive updates for frequent correspondents
	private final boolean primitive_ops;
	private final int netprefix;
	private final int netmask;

	private final com.grey.naf.reactor.Dispatcher dsptch;
	private final com.grey.mailismus.DBHandle db;
	private final com.grey.mailismus.IPlist whitelist;

	private final java.sql.CallableStatement pstmt_vet;
	private final java.sql.PreparedStatement pstmt_purge;
	private final java.sql.PreparedStatement pstmt_clear;
	private final java.sql.PreparedStatement pstmt_create;
	private final java.sql.PreparedStatement pstmt_promote;
	private final java.sql.PreparedStatement pstmt_refresh;
	private final java.sql.PreparedStatement pstmt_fetch;

	private com.grey.naf.reactor.TimerNAF tmr_purge;
	private volatile boolean is_closed;

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex) {}
	public int[] count() {return show(null);}

	public Greylist(com.grey.naf.reactor.Dispatcher dsptch, DBHandle.Type dbtype, com.grey.base.config.XmlConfig cfg)
		throws java.io.IOException
	{
		this.dsptch = dsptch;
		qtine_interval = cfg.getTime("quarantine_interval", "30m");
		retry_interval = cfg.getTime("retry_interval", "6h");
		expiry_interval = cfg.getTime("expiry_interval", "7d");
		purge_interval = cfg.getTime("purge_interval", "3h");
		updates_freeze = cfg.getTime("updates_freeze", "1h");

		netprefix = cfg.getInt("netprefix", false, 28);
		netmask = com.grey.base.utils.IP.prefixToMask(netprefix);

		com.grey.base.config.XmlConfig whitecfg = cfg.getSection("whitelist");
		if (whitecfg.exists()) {
			whitelist = new com.grey.mailismus.IPlist(listname+"_white", dbtype, whitecfg, dsptch);
		} else {
			whitelist = null;
		}
		Exception ex_setup = null;

		// If setup-script encounters a genuine error, that will cause our initial purge to fail, and
		// maybe even some of the prepareStatement() calls, so we assume any script errors are harmless
		// as long as these subsequent ops succeed.
		try {
			db = new com.grey.mailismus.DBHandle(listname, dbtype, dsptch.getApplicationContext(), cfg, dsptch.getLogger());
			db.connect(false, true);
			ex_setup = db.executeScript("setup", getClass(), cfg, true, null, null, null);
			db.connect(false, false);
			primitive_ops = db.dbtype.hasStoredProcs ? cfg.getBool("primitive_ops", !db.dbtype.hasStoredProcs) : true;
	
			if (primitive_ops) {
				pstmt_create = db.prepareStatement(sqlcmd_create);
				pstmt_promote = db.prepareStatement(sqlcmd_promote);
				pstmt_refresh = db.prepareStatement(sqlcmd_refresh);
				pstmt_vet = null;
			} else {
				pstmt_vet = db.prepareCall(sqlcmd_vet);
				pstmt_create = null;
				pstmt_promote = null;
				pstmt_refresh = null;
			}
			pstmt_fetch = db.prepareStatement(sqlcmd_fetch);
			pstmt_purge = db.prepareStatement(sqlcmd_purge);
			pstmt_clear = db.prepareStatement(sqlcmd_clear);
			purge();
		} catch (Exception ex) {
			if (ex_setup != null) dsptch.getLogger().info("Greylist setup failed - "+com.grey.base.ExceptionUtils.summary(ex_setup, false));
			throw new MailismusStorageException("Failed to initialise GreyList", ex);
		}

		// schedule regular purges
		tmr_purge = dsptch.setTimer(purge_interval, 0, this);

		int[] totals = count();
		dsptch.getLogger().info("Greylist/"+netprefix+" loaded - Total="+totals[0]+", Grey="+totals[1]+" - primitives="+primitive_ops);
		dsptch.getLogger().info("Greylist: quarantine="+TimeOps.expandMilliTime(qtine_interval)
				+", maxretry="+TimeOps.expandMilliTime(retry_interval)
				+", expiry="+TimeOps.expandMilliTime(expiry_interval)
				+", purge="+TimeOps.expandMilliTime(purge_interval)
				+", updates-freeze="+TimeOps.expandMilliTime(updates_freeze));
	}

	public void close()
	{
		if (is_closed) return;
		is_closed = true;

		try {
			if (pstmt_vet != null) pstmt_vet.close();
			if (pstmt_purge != null) pstmt_purge.close();
			if (pstmt_clear != null) pstmt_clear.close();
			if (pstmt_fetch != null) pstmt_fetch.close();
			if (pstmt_create != null) pstmt_create.close();
			if (pstmt_promote != null) pstmt_promote.close();
			if (pstmt_refresh != null) pstmt_refresh.close();
			db.close();
		} catch (Exception ex) {
			dsptch.getLogger().log(LEVEL.TRC, ex, false, "Greylist: Shutdown errors");
		}
		if (whitelist != null) whitelist.close();
		if (tmr_purge != null) tmr_purge.cancel();
		tmr_purge = null;
	}

	public boolean vet(int ip, com.grey.base.utils.ByteChars addrfrom, com.grey.base.utils.ByteChars addrto)
	{
		if (whitelist != null && whitelist.exists(ip)) return true;
		return vet(ip, addrfrom == null ? null : addrfrom.toArray(), addrto.toArray());
	}

	public boolean vet(int ip, byte[] addrfrom, byte[] addrto)
	{
		if (whitelist != null && whitelist.exists(ip)) return true;
		ip &= netmask;
		boolean success = false;
		boolean approved;

		try {
			if (primitive_ops) {
				approved = vet_primitives(ip, addrfrom, addrto, null);
			} else {
				approved = vet_storedproc(ip, addrfrom, addrto);
			}
			success = true;
		} catch (Exception ex) {
			throw new MailismusStorageException("Failed to vet GreyList", ex);
		} finally {
			if (!success) db.commitNOSQL(false);
		}
		return approved;
	}

	public long lookup(int ip, com.grey.base.utils.ByteChars addrfrom, com.grey.base.utils.ByteChars addrto)
	{
		if (whitelist != null && whitelist.exists(ip)) return LOOKUP_WHITE;
		return lookup(ip, addrfrom == null ? null : addrfrom.toArray(), addrto.toArray());
	}

	// Returns LOOKUP_WHITE if whitelisted, LOOKUP_ABSENT if unregistered, 0 if greylisted, else the expiry time
	public long lookup(int ip, byte[] addrfrom, byte[] addrto)
	{
		if (whitelist != null && whitelist.exists(ip)) return LOOKUP_WHITE;
		ip &= netmask;
		long[] validtill = new long[1];
		try {
			vet_primitives(ip, addrfrom, addrto, validtill);
		} catch (Exception ex) {
			throw new MailismusStorageException("Failed to lookup GreyList", ex);
		}
		return validtill[0];
	}

	public int purge()
	{
		try {
			pstmt_purge.clearParameters();
			pstmt_purge.setLong(1, dsptch.getSystemTime() - retry_interval);
			pstmt_purge.setLong(2, dsptch.getSystemTime() - expiry_interval);
			int cnt = pstmt_purge.executeUpdate();
			db.commit(true);
			if (cnt != 0) dsptch.getLogger().trace("Greylist purged recs="+cnt);
			return cnt;
		} catch (Exception ex) {
			throw new MailismusStorageException("Failed to purge GreyList", ex);
		}
	}

	public int reset()
	{
		try {
			int cnt = pstmt_clear.executeUpdate();
			db.commit(true);
			return cnt;
		} catch (Exception ex) {
			throw new MailismusStorageException("Failed to reset GreyList", ex);
		}
	}

	public int[] show(StringBuilder sb)
	{
		try {
			return wrapped_show(sb);
		} catch (Exception ex) {
			throw new MailismusStorageException("Failed to retrieve GreyList", ex);
		}
	}

	private boolean vet_primitives(int ip, byte[] addrfrom, byte[] addrto, long[] validtill) throws java.sql.SQLException
	{
		int gryid = 0;
		java.sql.ResultSet rs = null;
		long qtine = 0;
		long lastrecv = 0;
		boolean updated = false;

		pstmt_fetch.clearParameters();
		pstmt_fetch.setInt(1, ip);
		pstmt_fetch.setBytes(2, addrfrom);
		pstmt_fetch.setBytes(3, addrto);
		try {
			rs = pstmt_fetch.executeQuery();
			if (rs.next()) {
				gryid = rs.getInt("ID");
				qtine = rs.getLong("QTINE_TILL");
				lastrecv = rs.getLong("LAST_RECV");
			}
		} finally {
			if (rs != null) try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC, ex, false, "Greylist: closing Fetch RS");}
		}

		if (gryid == 0)
		{
			// this tuple is not yet registered in Greylist
			if (validtill != null)
			{
				validtill[0] = LOOKUP_ABSENT;
				return false;
			}
			pstmt_create.clearParameters();
			pstmt_create.setInt(1, ip);
			pstmt_create.setLong(2, dsptch.getSystemTime());
			pstmt_create.setBytes(3, addrfrom);
			pstmt_create.setBytes(4, addrto);
			pstmt_create.setLong(5, dsptch.getSystemTime() + qtine_interval);
			pstmt_create.executeUpdate();
			updated = true;
		}
		else
		{
			if (qtine == 0)
			{
				// this tuple is already approved
				if (validtill != null)
				{
					validtill[0] = lastrecv + expiry_interval;
					return false;
				}

				if (lastrecv + updates_freeze <= dsptch.getSystemTime())
				{
					pstmt_refresh.clearParameters();
					pstmt_refresh.setLong(1, dsptch.getSystemTime());
					pstmt_refresh.setInt(2, gryid);
					pstmt_refresh.executeUpdate();
					updated = true;
				}
			}
			else
			{
				// this tuple is greylisted
				if (validtill != null)
				{
					validtill[0] = 0;
					return false;
				}

				if (qtine > dsptch.getSystemTime())
				{
					gryid = 0;  // still quarantined/greylisted
				}
				else
				{
					// promote this tuple to approved status
					pstmt_promote.clearParameters();
					pstmt_promote.setLong(1, dsptch.getSystemTime());
					pstmt_promote.setInt(2, gryid);
					pstmt_promote.executeUpdate();
					updated = true;
				}
			}
		}
		if (updated) db.commit(true);
		return (gryid != 0);
	}

	private boolean vet_storedproc(int ip, byte[] addrfrom, byte[] addrto) throws java.sql.SQLException
	{
		int gryid = 0;
		java.sql.ResultSet rs = null;

		pstmt_vet.clearParameters();
		pstmt_vet.setInt(1, ip);
		pstmt_vet.setBytes(2, addrfrom);
		pstmt_vet.setBytes(3, addrto);
		pstmt_vet.setLong(4, dsptch.getSystemTime());
		pstmt_vet.setLong(5, qtine_interval);
		pstmt_vet.setLong(6, updates_freeze);

		try {
			rs = pstmt_vet.executeQuery();
			db.commit(true);  // can't be sure that database was updated, so have to be conservative
			rs.next();
			gryid = rs.getInt("RESULT");
		} finally {
			if (rs != null) try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC, ex, false, "Greylist: closing Proc RS");}
		}
		return (gryid != 0);
	}

	private int[] wrapped_show(StringBuilder sb) throws java.sql.SQLException
	{
		String sql = "SELECT * FROM "+TBLNAM_GREYLIST;
		java.util.Calendar dtcal = null;

		if (sb == null) {
			sql = "SELECT COUNT(*) AS TOTAL FROM "+TBLNAM_GREYLIST;
		} else {
			dtcal = com.grey.base.utils.TimeOps.getCalendar(null);
			sb.append("<rows>");
		}
		com.grey.base.utils.ByteChars bc = new com.grey.base.utils.ByteChars();
		java.sql.Statement stmt = db.createStatement();
		java.sql.ResultSet rs = null;
		int total = 0;
		int grycnt = 0;

		try {
			rs = stmt.executeQuery(sql);
			while (rs.next()) {
				if (sb == null) {
					// we'll only get back one row, and then repeat to count the greylisted/quarantined entries
					total = rs.getInt("TOTAL");
					sql += " WHERE QTINE_TILL IS NOT NULL";
					rs.close();
					rs = stmt.executeQuery(sql);
					rs.next();
					grycnt = rs.getInt("TOTAL");
					break;
				}
				total++;
				int id = rs.getInt("ID");
				dtcal.setTimeInMillis(rs.getLong("CREATED"));
				long qtine_till = rs.getLong("QTINE_TILL");
				int ip = rs.getInt("IP_FROM");
				byte[] arrfrom = rs.getBytes("MAILADDR_FROM");
				byte[] arrto = rs.getBytes("MAILADDR_TO");
				String status = (qtine_till == 0 ? "OK" : "GREY");

				sb.append("<row ID=\"").append(id).append("\" Status=\"").append(status).append("\" Created=\"");
				com.grey.base.utils.TimeOps.makeTimeLogger(dtcal, sb, true, false);
				sb.append("\" SourceNet=\"");
				com.grey.base.utils.IP.displayDottedIP(ip, sb);
				sb.append("\" Sender=\"").append(arrfrom == null ? "" : bc.populate(arrfrom));
				sb.append("\" Recip=\"").append(bc.populate(arrto));
				sb.append("\" QuarantineTill=\"");
				if (qtine_till != 0) {
					dtcal.setTimeInMillis(qtine_till);
					grycnt++;
					com.grey.base.utils.TimeOps.makeTimeLogger(dtcal, sb, true, false);
				}
				sb.append("\" LastRecv=\"");
				if (qtine_till == 0) {
					dtcal.setTimeInMillis(rs.getLong("LAST_RECV"));
					com.grey.base.utils.TimeOps.makeTimeLogger(dtcal, sb, true, false);
				}
				sb.append("\"/>");
			}
			if (sb != null) {
				sb.append("</rows>");
				sb.append("<summary total=\"").append(total).append("\" grey=\"").append(grycnt);
				sb.append("\" srcprefix=\"").append(netprefix).append("\"/>");
			}
		} finally {
			if (rs != null) {
				try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC2, ex, false, "GreyList.show: rs.close");}
			}
			try {stmt.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC2, ex, false, "GreyList.show: stmt.close");}
		}
		return new int[]{total, grycnt};
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF t, com.grey.naf.reactor.Dispatcher d)
	{
		if (t != tmr_purge) throw new MailismusStorageException("Unexpected Greylist timer="+t.getID()+"/"+t.getType());
		tmr_purge = null;
		purge();
		tmr_purge = dsptch.setTimer(purge_interval, 0, this);
	}
}
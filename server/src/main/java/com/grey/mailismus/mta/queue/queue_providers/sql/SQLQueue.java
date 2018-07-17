/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.sql;

import com.grey.logging.Logger.LEVEL;
import com.grey.base.utils.TimeOps;
import com.grey.mailismus.mta.queue.QException;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.errors.MailismusConfigException;

/*
 * In order to be picked up by the Class.forName() call, the JDBC driver (eg. postgresql-8.4-701.jdbc4.jar or sqljdbc4.jar) merely needs to be on
 * our class-path. The hitch with that is that if the application is an executable JAR (ie. run with 'java -jar', as Mailismus is), then the
 * CLASSPATH environmental or command-line setting is ignored, and the class-path reduces to this JAR and its Manifest dependencies.
 * In that situation, the only way to pick up the JDBC JAR is to put it in the Extensions directory.
 *
 * A note on the procname()/tryProcname() pattern:
 * We tolerate failure on the initial database access resulting from an external call, and retry exactly once before declaring official failure.
 * This is not meant to be an error-recovery scheme (ie. an attempt to recover from ongoing problems), as that could lead to the suggestion of
 * why not retry more than once, plus ever more elaborate tactics.
 * It is simply a recognition that this object holds long-lived database handles, which may occasionally disappear due to resets at the database
 * server, or similiar. We therefore handle the possibility of this happening in between calls to this object. If we fail to repeat the command,
 * then there is an ongoing current problem, and we don't try to handle that - we just throw back to caller.
 *
 * Note that pstmt.setString(n, String) does not require the incoming string to be surrounded in quotes, and also handles embedded quotes ok
 * pstmt.setNull(n, java.sql.Types.VARCHAR) successfully conveys a NULL for a varchar field
 */
public final class SQLQueue
	extends com.grey.mailismus.mta.queue.Manager
	implements com.grey.mailismus.mta.queue.Spooler.SPID_Counter
{
	private static final String TBLNAM_SMTPQ = "MMTA_SMTPQUEUE";

	// This is associated with the Postgresql max_expr_depth config item, whose default value is 10,000. That is the
	// critical limit which constrains long SQL commands, long before any theoretical limit on their max SQL length.
	// Setting keys=10,000 as well doesn't work, but 9,000 does work, so it can probably go even close to that limit
	// (which can also be increased by the Postgresql administrator anyway.
	// However, since it doesn't constrain our cache size, we'll default sqlmax_subqrykeys to a much lower value, to
	// be conservative.
	private final int sqlmax_subqrykeys;

	private static final String dflt_sqlcmd_add_recip = "INSERT INTO "+TBLNAM_SMTPQ
		+ " (SPID, MAILADDR_FROM, RECVTIME, IPRECV, NEXTSEND, DOMAIN_TO, MAILBOX_TO)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?)";
	private static final String dflt_sqlcmd_get_readyrecips = "SELECT QID, SPID, MAILADDR_FROM, DOMAIN_TO, MAILBOX_TO, RETRYCOUNT, RECVTIME, IPRECV"
		+ " FROM "+TBLNAM_SMTPQ
		+ " WHERE NEXTSEND IS NOT NULL AND NEXTSEND <= ?";
	private static final String dflt_sqlcmd_get_bounces = "SELECT QID, SPID, MAILADDR_FROM, DOMAIN_TO, MAILBOX_TO, RETRYCOUNT, STATUS, RECVTIME, IPRECV"
		+ " FROM "+TBLNAM_SMTPQ
		+ " WHERE NEXTSEND IS NULL";
	private static final String dflt_sqlcmd_get_allspids = "SELECT SPID FROM "+TBLNAM_SMTPQ;

	// command fragments
	private static final String dflt_sqlcmd_del_recips = "DELETE FROM "+TBLNAM_SMTPQ
		+" WHERE QID in ("; //append QIDs and closing bracket to this
	private static final String dflt_sqlcmd_update_recips = "UPDATE "+TBLNAM_SMTPQ
		+" SET STATUS=#1#, NEXTSEND = #2# + #3#, RETRYCOUNT=RETRYCOUNT+1"
		+" WHERE QID in ("; //append QIDs and closing bracket to this
	private static final String dflt_sqlcmd_set_errorbounces = "UPDATE "+TBLNAM_SMTPQ
		+" SET NEXTSEND=NULL, STATUS=#1#"
		+" WHERE QID in ("; //append QIDs and closing bracket to this
	private static final String dflt_sqlcmd_set_expirybounces = "UPDATE "+TBLNAM_SMTPQ
		+" SET NEXTSEND=NULL"
		+" WHERE QID in ("; //append QIDs and closing bracket to this
	private static final String dflt_sqlcmd_get_spids = "SELECT SPID FROM "+TBLNAM_SMTPQ
		+" WHERE SPID in ("; //append SPIDs and closing bracket to this

	private final String sqlcmd_add_recip;
	private final String sqlcmd_get_readyrecips;
	private final String sqlcmd_get_bounces;
	private final String sqlcmd_del_recips;
	private final String sqlcmd_update_recips;
	private final String sqlcmd_set_errorbounces;
	private final String sqlcmd_set_expirybounces;
	private final String sqlcmd_get_allspids;
	private final String sqlcmd_get_spids;

	@Override
	public boolean supportsShow() {return true;}

	// these all exist purely to provide a test hook for failing SQL commands (on the first call only)
	static final String SYSPROP_FORCEFAILS = "grey.test.mtaq.sqlq.forcefails";
	private static final boolean FORCE_FAIL = com.grey.base.config.SysProps.get(SYSPROP_FORCEFAILS, false);
	private static final int FAIL_STOREMSG = 0;
	private static final int FAIL_UPDATERECIPS = 1;
	private static final int FAIL_GETRECIPS = 2;
	private static final int FAIL_GETBOUNCES = 3;
	private static final int FAIL_SHOW = 4;
	private final boolean failed_methods[] = new boolean[5];

	private final com.grey.mailismus.DBHandle db;
	private java.sql.PreparedStatement pstmt_add_recip;
	private java.sql.PreparedStatement pstmt_get_readyrecips;
	private java.sql.PreparedStatement pstmt_get_bounces;
	private java.sql.Statement stmt_misc;

	// Temp work areas, pre-allocated for efficiency
	// modqids tracks the MessageRecips updates for deferred messages - it is indexed by retrycnt, and each element is hash-keyed on smtp_status
	private final com.grey.base.collections.HashedMapIntKey<java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>>[] modqids;
	private final StringBuilder sqlcmd_misc = new StringBuilder(1024);
	private final com.grey.base.utils.IntValue recipcounter = new com.grey.base.utils.IntValue(0);
	private int[] tmp_spids = new int[0]; //will grow as necessary
	private final StringBuilder tmpsb = new StringBuilder();

	public SQLQueue(com.grey.naf.reactor.Dispatcher dsptch, com.grey.base.config.XmlConfig cfg, AppConfig appcfg, String name)
			throws java.io.IOException
	{
		super(dsptch, cfg, name);
		if (getSpooler().isHardLinked()) {
			//incompatible, as this manager's QIDs are not numbered from 1 per recip
			throw new MailismusConfigException("The SQL-QueueManager does not support hard-linked spool files");
		}

		@SuppressWarnings("unchecked")
		com.grey.base.collections.HashedMapIntKey<java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>>[] unchecked
			= new com.grey.base.collections.HashedMapIntKey[10];
		modqids = unchecked;
		for (int idx = 0; idx != modqids.length; idx++) {
			modqids[idx] = new com.grey.base.collections.HashedMapIntKey<java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>>(0, 10f);
		}

		sqlmax_subqrykeys = cfg.getInt("sql_maxsubquerykeys", false, 1000);
		sqlcmd_add_recip = cfg.getValue("sql_addrecip", true, dflt_sqlcmd_add_recip);
		sqlcmd_update_recips = cfg.getValue("sql_updaterecips", true, dflt_sqlcmd_update_recips);
		sqlcmd_del_recips = cfg.getValue("sql_delrecips", true, dflt_sqlcmd_del_recips);
		sqlcmd_get_readyrecips = cfg.getValue("sql_getready", true, dflt_sqlcmd_get_readyrecips);
		sqlcmd_get_bounces = cfg.getValue("sql_getbounces", true, dflt_sqlcmd_get_bounces);
		sqlcmd_set_errorbounces = cfg.getValue("sql_setbounces_err", true, dflt_sqlcmd_set_errorbounces);
		sqlcmd_set_expirybounces = cfg.getValue("sql_setbounces_tmt", true, dflt_sqlcmd_set_expirybounces);
		sqlcmd_get_allspids = cfg.getValue("sql_getallspids", true, dflt_sqlcmd_get_allspids);
		sqlcmd_get_spids = cfg.getValue("sql_getspids", true, dflt_sqlcmd_get_spids);

		String dbname = cfg.getValue("sql_dbname", true, "smtpq");
		db = new com.grey.mailismus.DBHandle(dbname, appcfg.getDatabaseType(), dsptch.getApplicationContext(), cfg, dsptch.getLogger());
		try {
			initDatabase(cfg);
		} catch (Exception ex) {
			throw new QException(loglbl+"Failed to initialise database", ex);
		}
		dsptch.getLogger().trace(loglbl+" sql_maxsubquerykeys=" + sqlmax_subqrykeys);
		if (FORCE_FAIL) dsptch.getLogger().info(loglbl+"Forced-Failure test mode is enabled");
	}

	@Override
	protected void shutdown()
	{
		closeDatabase();
	}

	@Override
	public void loadSPIDs(com.grey.base.collections.HashedMapIntInt refcnt, int max) throws java.io.IOException
	{
		try {
			tryLoadSPIDs(refcnt, max);
		} catch (Throwable ex) {
			dsptch.getLogger().trace(loglbl+"initial loadSPIDs failed - "+com.grey.base.ExceptionUtils.summary(ex));
			try {db.commit(false);} catch (Exception ex2) {
				dsptch.getLogger().log(LEVEL.TRC, ex2, false, loglbl+"failed to rollback initial loadSPIDs");
			}

			// reset database connection and try again - treat as genuine error if we fail again
			boolean rollback = false;
			try {
				initDatabase(null);
				rollback = true;
				tryLoadSPIDs(refcnt, max);
			} catch (Throwable ex2) {
				dsptch.getLogger().log(LEVEL.INFO, ex2, true, loglbl+"loadSPIDs failed");
				if (rollback) db.commitNOSQL(false);
			}
		}
	}

	@Override
	protected boolean storeMessage(com.grey.mailismus.mta.queue.SubmitHandle sph) throws java.io.IOException
	{
		boolean success = true;
		if (read_only) return true;

		try {
			tryStoreMessage(sph);
		} catch (Throwable ex) {
			dsptch.getLogger().trace(loglbl+"initial add-message failed - counter="+recipcounter.val+" - "+com.grey.base.ExceptionUtils.summary(ex));
			try {db.commit(false);} catch (Exception ex2) {
				dsptch.getLogger().log(LEVEL.TRC, ex2, false, loglbl+"failed to rollback initial add-message");
			}

			// reset database connection and try again - treat as genuine error if we fail again
			tmpsb.setLength(0);
			tmpsb.append("/"+sph.recips.size()+" - spid="+externalSPID(sph.spid));
			boolean rollback = false;
			try {
				initDatabase(null);
				rollback = true;
				tryStoreMessage(sph);
			} catch (Throwable ex2) {
				dsptch.getLogger().log(LEVEL.INFO, ex2, true, loglbl+"add-message failed - counter="+recipcounter.val+tmpsb
						+", recips="+sph.recips.size()+": "+sph.recips);
				success = false;
				if (rollback) db.commitNOSQL(false);
			}
		}
		return success;
	}

	@Override
	public void updateMessages(com.grey.mailismus.mta.queue.Cache cache, boolean is_bounces_batch)
	{
		if (read_only) return;
		try {
			tryUpdateMessages(cache, is_bounces_batch);
		} catch (Throwable ex) {
			dsptch.getLogger().trace(loglbl+"initial updateMessages failed - "+com.grey.base.ExceptionUtils.summary(ex));
			try {db.commit(false);} catch (Exception ex2) {
				dsptch.getLogger().log(LEVEL.TRC, ex2, false, loglbl+"failed to rollback initial updateMessages");
			}

			// reset database connection and try again - treat as genuine error if we fail again
			boolean rollback = false;
			try {
				initDatabase(null);
				rollback = true;
				tryUpdateMessages(cache, is_bounces_batch);
			} catch (Throwable ex2) {
				dsptch.getLogger().log(LEVEL.INFO, ex2, true, loglbl+"updateMessages failed");
				if (rollback) {
					try {
						db.commitNOSQL(false);
					} catch (Throwable ex3) {
						dsptch.getLogger().log(LEVEL.INFO, ex3, true, loglbl+"updateMessages rollback failed");
					}
				}
				throw new QException("Queue-Update failed", ex2);
			}
		}
	}

	@Override
	protected void determineOrphans(com.grey.base.collections.HashedSetInt candidates) throws java.io.IOException
	{
		try {
			tryDetermineOrphans(candidates);
		} catch (Throwable ex) {
			dsptch.getLogger().trace(loglbl+"initial determineOrphans failed - "+com.grey.base.ExceptionUtils.summary(ex));
			try {db.commit(false);} catch (Exception ex2) {
				dsptch.getLogger().log(LEVEL.TRC, ex2, false, loglbl+"failed to rollback initial determineOrphans");
			}

			// reset database connection and try again - treat as genuine error if we fail again
			boolean rollback = false;
			try {
				initDatabase(null);
				rollback = true;
				tryDetermineOrphans(candidates);
			} catch (Throwable ex2) {
				dsptch.getLogger().log(LEVEL.INFO, ex2, true, loglbl+"determineOrphans failed");
				if (rollback) db.commitNOSQL(false);
			}
		}
	}

	@Override
	protected void loadMessages(com.grey.mailismus.mta.queue.Cache cache, boolean get_bounces, boolean get_deferred)
	{
		getRecips(cache, get_bounces, get_deferred);
	}

	@Override
	public int qsize(CharSequence sender, CharSequence recip, int flags)
	{
		return show(sender, recip, 0, flags, null);
	}

	@Override
	public int show(CharSequence sender, CharSequence recip, int maxmessages, int flags, StringBuilder outbuf)
	{
		int prevmark = (outbuf == null ? -1 : outbuf.length());
		int total;
		try {
			total = tryShow(sender, recip, maxmessages, flags, outbuf);
			if (FORCE_FAIL) forced_failure(FAIL_SHOW, "Show-Queue");
		} catch (Throwable ex) {
			// reset database connection and try again - treat as genuine error if we fail again
			dsptch.getLogger().info(loglbl+"initial show-queue failed - "+com.grey.base.ExceptionUtils.summary(ex));
			if (outbuf != null) outbuf.setLength(prevmark);

			try {
				initDatabase(null);
				total = tryShow(sender, recip, maxmessages, flags, outbuf);
			} catch (Exception ex2) {
				throw new QException(loglbl+"Failed to do show-queue", ex2);
			}
		}
		return total;
	}

	private void tryStoreMessage(com.grey.mailismus.mta.queue.SubmitHandle sph) throws java.sql.SQLException
	{
		pstmt_add_recip.clearParameters();
		pstmt_add_recip.setInt(1, sph.spid);
		pstmt_add_recip.setLong(3, dsptch.getSystemTime());
		pstmt_add_recip.setInt(4, sph.iprecv);
		pstmt_add_recip.setLong(5, dsptch.getSystemTime());
		com.grey.base.utils.ByteChars origsender_bc = (sph.sender == null || sph.sender.length() == 0 ? null : sph.sender);
		String origsender = null;

		for (int idx = 0; idx != sph.recips.size(); idx++) {
			com.grey.base.utils.ByteChars rewrite_sender = (sph.sender_rewrites==null ? null : sph.sender_rewrites.get(idx));
			String sender;
			if (rewrite_sender != null) {
				sender = rewrite_sender.toString();
			} else {
				//defer creation of String till we're sure we need it
				if (origsender == null && origsender_bc != null) origsender = origsender_bc.toString();
				sender = origsender;
			}
			addRecipient(sph.recips.get(idx), sender);
		}
		if (FORCE_FAIL) forced_failure(FAIL_STOREMSG, "Store-Message");
		db.commit(true);
	}

	private void addRecipient(com.grey.base.utils.EmailAddress recip, String sender) throws java.sql.SQLException
	{
		recip.decompose();
		String mbx = recip.mailbox.toString(); //empty mailbox part becomes empty string
		String dom = (recip.domain.length() == 0 ? null : recip.domain.toString()); //missing or empty domain part becomes null string

		pstmt_add_recip.setString(2, sender);
		pstmt_add_recip.setString(6, dom);
		pstmt_add_recip.setString(7, mbx);
		pstmt_add_recip.executeUpdate();
	}

	private void getRecips(com.grey.mailismus.mta.queue.Cache cache, boolean get_bounces, boolean get_deferred) {
		String tag = (get_bounces ? "Bounces" : "Ready-Recips");
		int prevmark = cache.size();
		try {
			loadRecips(cache, get_bounces, get_deferred);
			if (FORCE_FAIL) forced_failure(get_bounces ? FAIL_GETBOUNCES : FAIL_GETRECIPS, "Get-"+tag);
			db.commit(true);
		} catch (Throwable ex) {
			// reset database connection and try again - treat as genuine error if we fail again
			dsptch.getLogger().info(loglbl+"initial get-"+tag+" failed - "+com.grey.base.ExceptionUtils.summary(ex));
			try {db.commit(false);} catch (Exception ex2) {
				dsptch.getLogger().log(LEVEL.TRC, ex2, false, loglbl+"failed to rollback initial get-"+tag);
			}
			cache.truncate(prevmark);
			boolean rollback = false;
			try {
				initDatabase(null);
				rollback = true;
				loadRecips(cache, get_bounces, get_deferred);
				db.commit(true);
			} catch (Exception ex2) {
				if (rollback) {
					try {
						db.commitNOSQL(false);
					} catch (Throwable ex3) {
						dsptch.getLogger().log(LEVEL.INFO, ex3, true, loglbl+"getRecips rollback failed");
					}
				}
				throw new QException(loglbl+"Failed to get "+tag, ex2);
			}
		}
	}

	// The 'bounces' param tells us whether we're being called in reporting or delivery mode.
	private void loadRecips(com.grey.mailismus.mta.queue.Cache cache, boolean get_bounces, boolean get_deferred)
		throws java.sql.SQLException
	{
		java.sql.PreparedStatement stmt;
		if (get_bounces) {
			stmt = pstmt_get_bounces;
		} else {
			stmt = pstmt_get_readyrecips;
			stmt.clearParameters();
			stmt.setLong(1, get_deferred ? Long.MAX_VALUE : dsptch.getSystemTime());
		}
		long bounce_cutoff = dsptch.getSystemTime() - maxretrytime;
		long bounce_cutoff_ndr = dsptch.getSystemTime() - maxretrytime_ndr;
		stmt.setMaxRows(cache.capacity() - cache.size());
		java.sql.ResultSet rs = stmt.executeQuery();
		int expirecnt = 0;

		try {
			while (rs.next()) {
				boolean expired = false;
				int qid = rs.getInt("QID");
				short status = (get_bounces ? (short)rs.getInt("STATUS") : 0);
				com.grey.base.utils.ByteChars sndr = allocCacheField(rs.getString("MAILADDR_FROM"));
				com.grey.base.utils.ByteChars dom_to = allocCacheField(rs.getString("DOMAIN_TO"));
				com.grey.base.utils.ByteChars mbox_to = allocCacheField(rs.getString("MAILBOX_TO"));
				long recvtime = rs.getLong("RECVTIME");
				if (!get_bounces) {
					// we are being called in delivery mode, but we mark bounced messages for the reporting task
					if (sndr == null) {
						if (recvtime < bounce_cutoff_ndr) expired = true;
					} else {
						if (recvtime < bounce_cutoff) expired = true;
					}
				}
				if (expired) {
					if (tmp_spids.length <= expirecnt) tmp_spids = arrgrow(tmp_spids);
					tmp_spids[expirecnt++] = qid;
				} else {
					cache.addEntry(qid, rs.getInt("SPID"), recvtime, rs.getInt("IPRECV"), sndr, dom_to, mbox_to,
							(short)rs.getInt("RETRYCOUNT"), status);
				}
			}
		} finally {
			try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC2, ex, false, loglbl+"load-rs.close");}
		}

		// now mark any expired messages as bounces
		if (expirecnt != 0) {
			int subkeynum = 0;
			sqlcmd_misc.setLength(0);
			for (int idx = 0; idx != expirecnt; idx++) {
				subkeynum = buildSubquery(sqlcmd_set_expirybounces, subkeynum, null, tmp_spids[idx], sqlcmd_misc, stmt_misc);
			}
			if (subkeynum != 0) executeWithSubQuery(stmt_misc, sqlcmd_misc, false);
		}
	}

	private void tryUpdateMessages(com.grey.mailismus.mta.queue.Cache cache, boolean is_bounces_batch)
		throws java.sql.SQLException
	{
		for (int idx = 0; idx != modqids.length; idx++) {
			java.util.Iterator<java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>> it = modqids[idx].recycledValuesIterator();
			while (it.hasNext()) it.next().clear();
		}
		final int cachesize = cache.size();
		int subkeynum = 0;
		sqlcmd_misc.setLength(0);

		for (int idx = 0; idx != cachesize; idx++) {
			com.grey.mailismus.mta.queue.MessageRecip recip = cache.get(idx);
			if (recip.qstatus != com.grey.mailismus.mta.queue.MessageRecip.STATUS_DONE) {
				continue;
			}

			if (is_bounces_batch || recip.smtp_status == com.grey.mailismus.mta.Protocol.REPLYCODE_OK) {
				/*
				 * Delete queue entries for delivered recips as we go along.
				 * Note if a non-leading sqlcmd_del_recips update throws (or if we encounter any errors between executing the first del_recips
				 * command and deleting the last orphaned spool item), we will have lost track of any spool items which were orphaned (ie. are
				 * not referred to by any more queued recipients) by deleting those recipients.
				 * I considered taking advantage of an empty queue to clean this up by simply wiping out all spooled content that was leftover at
				 * that point, but since bouncing messages will hang around for days before expiring, a busy server's queue will never be empty.
				 * It's not just a case of waiting for no incoming messages (which will happen regularly enough).
				 * So a non-time critical thread needs to trawl the spool area at regular (but infrequent) intervals, and delete any items
				 * it discovers to be orphaned.
				 */
				subkeynum = buildSubquery(sqlcmd_del_recips, subkeynum, null, recip.qid, sqlcmd_misc, stmt_misc);
			} else {
				int idx_modqids = (recip.retrycnt < modqids.length ? recip.retrycnt : modqids.length - 1);
				java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip> lst = modqids[idx_modqids].get(recip.smtp_status);
				if (lst == null) {
					lst = new java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>();
					modqids[idx_modqids].put(recip.smtp_status, lst);
				}
				lst.add(recip);
			}
		}

		//finish off the sqlcmd_del_recips loop, to delete the last of the delivered recips
		if (subkeynum != 0) executeWithSubQuery(stmt_misc, sqlcmd_misc, false);

		/*
		 * Update the recipients who were marked as unsuccessful, so that they will be retried later.
		 * The replace() calls generate some runtime String allocations, but the JDBC forces us to churn out lots of those anyway, so
		 * temporarily suspend the usual fretting about this.
		 */
		final String sqlcmd_update2 = sqlcmd_update_recips.replace("#2#", Long.toString(dsptch.getSystemTime()));
		for (int retrycnt = 0; retrycnt != modqids.length; retrycnt++) {
			java.util.Iterator<java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>> it_status = modqids[retrycnt].recycledValuesIterator();
			String sqlcmd_update3 = null;

			while (it_status.hasNext()) {
				java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip> lst = it_status.next();
				int cnt = lst.size();
				if (cnt == 0) continue;
				int status = lst.get(0).smtp_status; //every element of 'lst' has same status - this hashed node is keyed on smtp_status
				String sqlcmd;

				if (status >= com.grey.mailismus.mta.Protocol.PERMERR_BASE) {
					sqlcmd = sqlcmd_set_errorbounces;
				} else {
					if (sqlcmd_update3 == null) sqlcmd_update3 = sqlcmd_update2.replace("#3#", Long.toString(getRetryDelay(retrycnt)));
					sqlcmd = sqlcmd_update3;
				}
				sqlcmd = sqlcmd.replace("#1#", Integer.toString(status));
				subkeynum = 0;
				sqlcmd_misc.setLength(0);
				for (int idx = 0; idx != cnt; idx++) {
					subkeynum = buildSubquery(sqlcmd, subkeynum, null, lst.get(idx).qid, sqlcmd_misc, stmt_misc);
				}
				if (subkeynum != 0) executeWithSubQuery(stmt_misc, sqlcmd_misc, false);
			}
		}
		if (FORCE_FAIL) forced_failure(FAIL_UPDATERECIPS, "Update-Recips");
		db.commit(true);
	}

	private void tryLoadSPIDs(com.grey.base.collections.HashedMapIntInt refcnt, int max) throws java.sql.SQLException
	{
		java.sql.ResultSet rs = stmt_misc.executeQuery(sqlcmd_get_allspids);
		try {
			while (rs.next()) {
				int spid = rs.getInt("SPID");
				if (!com.grey.mailismus.mta.queue.Spooler.isMultiSPID(spid)) continue;
				int cnt = refcnt.get(spid);
				refcnt.put(spid, cnt+1);
				if (max != 0 && refcnt.size() >= max) break;
			}
		} finally {
			try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC2, ex, false, loglbl+"load-spids.close");}
		}
	}

	private void tryDetermineOrphans(com.grey.base.collections.HashedSetInt candidates) throws java.sql.SQLException
	{
		//can't modify 'candidates' while we're iterating on it, so copy to (reusable) temp array first
		int spidcnt = 0;
		if (tmp_spids.length < candidates.size()) tmp_spids = new int[candidates.size()];
		com.grey.base.collections.IteratorInt iter = candidates.recycledIterator();
		while (iter.hasNext()) tmp_spids[spidcnt++] = iter.next();

		int subkeynum = 0;
		sqlcmd_misc.setLength(0);
		for (int idx = 0; idx != spidcnt; idx++) {
			subkeynum = buildSubquery(sqlcmd_get_spids, subkeynum, null, tmp_spids[idx], sqlcmd_misc, null);

			if (subkeynum == 0 || idx == spidcnt - 1) {
				//use the returned SPIDs to prune our saved set of SPIDs
				java.sql.ResultSet rs = executeWithSubQuery(stmt_misc, sqlcmd_misc, true);
				try {
					while (rs.next()) {
						//this SPID is still in use, so is no longer a candidate for deletion
						candidates.remove(rs.getInt("SPID"));
					}
				} finally {
					try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC2, ex, false, loglbl+"orphans-rs.close");}
				}
				sqlcmd_misc.setLength(0);
			}
		}
	}

	// This method does not constitute part of the MTA's fundamental runtime repertoire and is only provided for reporting purposes, so
	// maximum efficiency is not required.
	private int tryShow(CharSequence sender, CharSequence recip, int maxrows, int flags, StringBuilder outbuf)
		throws java.sql.SQLException
	{
		StringBuilder sql = new StringBuilder(256);
		java.util.ArrayList<String> conds = new java.util.ArrayList<String>();
		java.util.Calendar dtcal = null;

		if (outbuf == null) {
			sql.append("SELECT COUNT(*) AS TOTAL");
			maxrows = 0;
		} else {
			sql.append("SELECT QID, SPID, MAILADDR_FROM, DOMAIN_TO, MAILBOX_TO, STATUS, RETRYCOUNT, RECVTIME, NEXTSEND");
			dtcal = TimeOps.getCalendar(null);
		}
		sql.append(" FROM ").append(TBLNAM_SMTPQ);
		String conj = " WHERE ";
		String wild = "%";

		if ((flags & SHOWFLAG_NEW) != 0) conds.add("NEXTSEND IS NOT NULL AND STATUS IS NULL"); //Status=Null will do, but NextSend is indexed
		if ((flags & SHOWFLAG_BOUNCES) != 0) conds.add("NEXTSEND IS NULL");
		if ((flags & SHOWFLAG_TEMPERR) != 0) conds.add("NEXTSEND IS NOT NULL AND STATUS IS NOT NULL");

		for (int idx = 0; idx != conds.size(); idx++) {
			sql.append(conj).append(conds.get(idx));
			conj = " AND ";
		}

		if (sender != null) {
			String str = sender.toString();
			sql.append(conj).append("MAILADDR_FROM LIKE ");
			sql.append(com.grey.mailismus.DBHandle.quoteString(wild+str+wild));
			conj = " AND ";
		}

		if (recip != null) {
			String str = recip.toString();
			sql.append(conj).append("MAILBOX_TO").append(db.dbtype.sqlconcat).append("'");
			sql.append(com.grey.base.utils.EmailAddress.DLM_DOM).append("'").append(db.dbtype.sqlconcat);
			sql.append("DOMAIN_TO LIKE ").append(com.grey.mailismus.DBHandle.quoteString(wild+str+wild));
		}
		if (outbuf != null) outbuf.append("<qrows>");
		java.sql.Statement stmt = db.createStatement();
		if (maxrows != 0) stmt.setMaxRows(maxrows);
		java.sql.ResultSet rs = null;
		int total = 0;

		try {
			rs = stmt.executeQuery(sql.toString());
			while (rs.next()) {
				if (outbuf == null) {
					// we'll only get back one row
					total = rs.getInt("TOTAL");
					break;
				}
				total++;
				int status = rs.getInt("STATUS");
				String sndr = rs.getString("MAILADDR_FROM");
				String dom_to = rs.getString("DOMAIN_TO");
				dtcal.setTimeInMillis(rs.getLong("RECVTIME"));
				long nextsend = 0;

				outbuf.append("<qrow MessageID=\"").append(externalSPID(rs.getInt("SPID")));
				outbuf.append("\" QID=\"").append(rs.getInt("QID"));
				outbuf.append("\" RecvTime=\"");
				TimeOps.makeTimeLogger(dtcal, outbuf, true, false);
				outbuf.append("\" Sender=\"").append(sndr == null ? "" : sndr);
				outbuf.append("\" Recip=\"").append(rs.getString("MAILBOX_TO"));
				if (dom_to != null) outbuf.append(com.grey.base.utils.EmailAddress.DLM_DOM).append(dom_to);

				outbuf.append("\" Status=\"");
				if (status == 0) {
					outbuf.append("NEW");
				} else {
					String txt = "ERR";
					nextsend = rs.getLong("NEXTSEND");
					if (nextsend == 0) {
						//this entry will become a bounce report, once Reports task picks it up
						txt = "BOUNCE";
					}
					outbuf.append(txt).append(" - ").append(rs.getInt("RETRYCOUNT")).append('/').append(status);
				}

				outbuf.append("\" NextSend=\"");
				if (nextsend != 0) {
					dtcal.setTimeInMillis(nextsend);
					TimeOps.makeTimeLogger(dtcal, outbuf, false, false);
				}
				outbuf.append("\"/>");
			}
		} finally {
			if (rs != null) {
				try {rs.close();} catch (Exception ex) {dsptch.getLogger().log(LEVEL.TRC2, ex, false, loglbl+"show-rs.close");}
			}
			closeStatement(stmt);
		}
		if (outbuf != null) {
			outbuf.append("</qrows>");
			outbuf.append("<summary total=\"").append(total).append("\"/>");
		}
		return total;
	}

	// The SQL setup script can yield spurious errors, so the proof of their seriousness lies in whether the
	// subsequent database ops work. If they do, the errors are assumed benign and ignored.
	private void initDatabase(com.grey.base.config.XmlConfig cfg)
		throws java.io.IOException, java.sql.SQLException
	{
		boolean verbose = false;
		if (cfg != null) {
			// this is the initial call from the constructor
			verbose = true;
		} else {
			closeDatabase();
		}
		db.connect(false, verbose);
		Exception ex_setup = null;
		boolean success = false;

		try {
			if (verbose) {
				// this is the first connect, so install the required database objects, if they don't already exist.
				ex_setup = db.executeScript("setup", getClass(), cfg, true, null, null, null);
			}
			pstmt_add_recip = db.prepareStatement(sqlcmd_add_recip);
			pstmt_get_readyrecips = db.prepareStatement(sqlcmd_get_readyrecips);
			pstmt_get_bounces = db.prepareStatement(sqlcmd_get_bounces);
			stmt_misc = db.createStatement();
			success = true;
		} finally {
			if (!success && ex_setup != null) dsptch.getLogger().info(loglbl+"Init-Database failed - "+com.grey.base.ExceptionUtils.summary(ex_setup, false));
		}
	}

	private void closeDatabase()
	{
		if (db == null) return;
		if (pstmt_add_recip != null) closeStatement(pstmt_add_recip); pstmt_add_recip = null;
		if (pstmt_get_readyrecips != null) closeStatement(pstmt_get_readyrecips); pstmt_get_readyrecips = null;
		if (pstmt_get_bounces != null) closeStatement(pstmt_get_bounces); pstmt_get_bounces = null;
		if (stmt_misc != null) closeStatement(stmt_misc); stmt_misc = null;
		db.close();
	}

	private void closeStatement(java.sql.Statement stmt)
	{
		if (stmt == null) return;
		try {
			stmt.close();
		} catch (Exception ex) {
			dsptch.getLogger().log(LEVEL.INFO, ex, false, loglbl+"failed to close statement - "+stmt);
		}
	}

	private int buildSubquery(String maincmd, int keynum, String strval, int intval,
			StringBuilder strbuf, java.sql.Statement stmt) throws java.sql.SQLException
	{
		if (keynum == 0) strbuf.append(maincmd);
		keynum++;

		if (strval == null) {
			strbuf.append(intval).append(',');
		} else {
			strbuf.append(com.grey.mailismus.DBHandle.quoteString(strval)).append(",");
		}
		if (keynum == sqlmax_subqrykeys) {
			keynum = 0;
			if (stmt != null) executeWithSubQuery(stmt, strbuf, false);
		}
		return keynum;
	}

	private java.sql.ResultSet executeWithSubQuery(java.sql.Statement stmt, StringBuilder strbuf, boolean isqry)
		throws java.sql.SQLException
	{
		strbuf.setLength(strbuf.length() - 1); //erase final comma
		strbuf.append(')'); //terminate list
		String sql = strbuf.toString();
		strbuf.setLength(0);
		if (isqry) return stmt.executeQuery(sql);
		stmt.executeUpdate(sql);
		return null;
	}

	private void forced_failure(int id, String msg) throws java.sql.SQLException
	{
		boolean failed = failed_methods[id];
		if (failed) return;
		failed_methods[id] = true;
		throw new QException(loglbl+msg+": Failing first transaction as directed");
	}

	private static int[] arrgrow(int[] arr)
	{
		int[] arr2 = new int[arr.length + 256];
		System.arraycopy(arr, 0, arr2, 0, arr.length);
		return arr2;
	}
}

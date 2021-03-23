/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.base.collections.IteratorInt;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;
import com.grey.logging.Logger.LEVEL;

abstract public class QueueManager
{
	// Flags for show() method.
	public static final int SHOWFLAG_NEW = 1 << 0;
	public static final int SHOWFLAG_BOUNCES = 1 << 1;
	public static final int SHOWFLAG_TEMPERR = 1 << 2;
	public static final int SHOWFLAG_REVSORT = 1 << 3;

	public final com.grey.naf.reactor.Dispatcher dsptch;
	protected final long maxretrytime;
	protected final long maxretrytime_ndr;
	protected final long prune_grace;

	protected final boolean read_only;
	protected final String loglbl;
	private final long[] retrydelays;
	private final int maxcachesize;
	private final Spooler spool;

	// spids_done contains SPIDs that are definitely ready for deletion (ie. orphaned), while spids_preserved are definitely not.
	// spids_candidates records the delivery count of SPIDs we're not sure about.
	private final com.grey.base.collections.HashedSetInt spids_done = new com.grey.base.collections.HashedSetInt(0, 10f);
	private final com.grey.base.collections.HashedSetInt spids_preserved = new com.grey.base.collections.HashedSetInt(0, 10f);
	private final com.grey.base.collections.HashedMapIntInt spids_candidates = new com.grey.base.collections.HashedMapIntInt(0, 10f);

	// Temp work areas, preallocated for efficiency
	protected final com.grey.base.collections.ObjectWell<ByteChars> bcwell;
	private final com.grey.base.collections.HashedSetInt tmpspidset = new com.grey.base.collections.HashedSetInt(0, 10f);
	private final java.util.ArrayList<ByteChars> cacherefs = new java.util.ArrayList<ByteChars>();
	private final StringBuilder tmpsb = new StringBuilder();

	/*
	 * Specialised methods that the concrete queue-manager classes are required to implement.
	 * Note that the thread which fetches ready recips in getMessages() also has to be the one that bounces them. Else there
	 * would be a race hazard where the thread that processes bounces (assuming it's different - and if it's not, it doesn't
	 * matter where we call updateMessages!) might declare messages as bounced while the thread that called getMessages() was
	 * still working through a cache that contained them.
	 */
	// This method attempts to be atomic and has the following return values:
	// - Returns True: Means we succeeded
	// - Returns False: Means we failed, but managed to roll back
	// - Throws: Failed in unknown state (and we've failed to guarantee being atomic)
	protected abstract boolean storeMessage(SubmitHandle sph)
			throws java.io.IOException;
	protected abstract void loadMessages(Cache cache, boolean get_bounces, boolean get_deferred)
			throws java.io.IOException;
	// same return values as storeMessage()
	protected abstract void updateMessages(Cache cache, boolean is_bounces_batch)
			throws java.io.IOException;
	// If a SPID has no references from the queue, then the spooled message-file is an orphan, and due for deletion.
	// This method is called after updateMessages() with a set of orphan candidates (the Manager base class is able to rule out
	// some SPIDs by itself) and the subclass is required to identify non-orphans and remove them from the set. On return, the
	// set contains definite orphans, and this base class will handle their spool-files cleanup.
	protected abstract void determineOrphans(com.grey.base.collections.HashedSetInt orphan_candidates)
			throws java.io.IOException;
	/*
	 * These methods are also eligible for override by the specialised queue-manager subclasses, but default implementations
	 * are provided.
	 */
	public int qsize(CharSequence sender, CharSequence recip, int flags) //same flags as show()
			throws java.io.IOException {return -1;}
	public int show(CharSequence sender, CharSequence recip, int maxmessages, int flags, StringBuilder outbuf)
			throws java.io.IOException {
		throw new QException(getClass().getName()+" does not support the Show-Queue method");
	}
	public boolean supportsShow() {return false;} //show() is an optional method
	public boolean verifyAddress(ByteChars full_email_address) {return true;}
	protected void shutdown() {}
	protected void doHousekeeping() {}

	public final CharSequence externalSPID(int spidval) {return spool.externalSPID(spidval);}
	public final java.nio.file.Path getMessage(int spid, int qid) {return spool.getMessage(spid, qid);}
	public final java.nio.file.Path getDiagnosticFile(int spid, int qid) {return spool.getDiagnosticFile(spid, qid);}
	public final int qsize(int flags) throws java.io.IOException {return qsize(null, null, flags);}
	public final void getMessages(Cache cache) throws java.io.IOException {getMessages(cache, false);}
	protected final Spooler getSpooler() {return spool;}

	protected QueueManager(com.grey.naf.reactor.Dispatcher d, Spooler spooler, com.grey.base.config.XmlConfig cfg, String name)
		throws java.io.IOException
	{
		if (spooler == null) {
			com.grey.base.config.XmlConfig spcfg = cfg.getSection("spool");
			spooler = new Spooler(d.getApplicationContext(), spcfg, d.getLogger(), name);
		}
		dsptch = d;
		spool = spooler;
		loglbl = "QMGR-"+name+": ";
		bcwell = new com.grey.base.collections.ObjectWell<ByteChars>(ByteChars.class, "QMGR-"+name);

		read_only = cfg.getBool("read_only", false);
		maxcachesize = (int)cfg.getSize("maxmemoryqueue", 0);
		maxretrytime = cfg.getTime("retry_maxtime", com.grey.base.utils.TimeOps.parseMilliTime("72h"));
		maxretrytime_ndr = Math.min(maxretrytime, cfg.getTime("retry_maxtime_reports", com.grey.base.utils.TimeOps.parseMilliTime("24h")));
		prune_grace = cfg.getTime("prune_graceperiod", com.grey.base.utils.TimeOps.parseMilliTime("7d"));

		String[] delaytimes = cfg.getTuple("retry_delays", "|", false, null);
		if (delaytimes == null) {
			retrydelays = new long[]{com.grey.base.utils.TimeOps.parseMilliTime("15m"),
				com.grey.base.utils.TimeOps.parseMilliTime("30m"),
				com.grey.base.utils.TimeOps.parseMilliTime("2h"),
				com.grey.base.utils.TimeOps.parseMilliTime("4h")};
		} else {
			retrydelays = new long[delaytimes.length];
			for (int idx = 0; idx != delaytimes.length; idx++) {
				retrydelays[idx] = com.grey.base.utils.TimeOps.parseMilliTime(delaytimes[idx]);
			}
		}
		String txt_retries = "";
		for (int idx = 0; idx != retrydelays.length; idx++) txt_retries += TimeOps.expandMilliTime(retrydelays[idx])+" + ";

		dsptch.getLogger().info(loglbl+"retry_maxtime="+com.grey.base.utils.TimeOps.expandMilliTime(maxretrytime)
				+" (reports="+com.grey.base.utils.TimeOps.expandMilliTime(maxretrytime_ndr)+")"+" - retries = "+txt_retries+"...");
		if (read_only) dsptch.getLogger().info(loglbl+"read-only mode");
	}

	protected QueueManager(com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg, String name)
			throws java.io.IOException
	{
		this(d, null, cfg, name);
	}

	public void start() throws java.io.IOException
	{
		if (this instanceof Spooler.SPID_Counter) spool.init((Spooler.SPID_Counter)this);
	}

	public final boolean stop()
	{
		shutdown();
		return true;
	}

	public final Cache initCache(int size)
	{
		if (maxcachesize != 0 && size > maxcachesize) size = maxcachesize;
		return new Cache(size);
	}

	public final void getMessages(Cache msgcache, boolean get_deferred) throws java.io.IOException
	{
		restoreCacheFields();
		loadMessages(msgcache, false, get_deferred);
	}

	public final void getBounces(Cache msgcache) throws java.io.IOException
	{
		restoreCacheFields();
		loadMessages(msgcache, true, false);
	}

	public final int messagesProcessed(Cache cache) throws java.io.IOException
	{
		return messagesProcessed(cache, false);
	}

	public final int bouncesProcessed(Cache cache) throws java.io.IOException
	{
		return messagesProcessed(cache, true);
	}

	// It's important to realise that this method is twinned with either getMessages() or getBounces(). The Cache that is
	// populated in those methods is returned to us in this method, in the same thread.
	// In any one instance of this class, this method is only twinned with one or the other of those Get methods, so there's
	// no inter-thread contention.
	private int messagesProcessed(Cache cache, boolean is_bounces_batch) throws java.io.IOException
	{
		spids_preserved.clear();
		spids_done.clear();
		spids_candidates.clear();
		int cachesize = cache.size();
		int delivcnt = 0;
		int failcnt = 0;

		updateMessages(cache, is_bounces_batch);

		for (int idx = 0; idx != cachesize; idx++) {
			MessageRecip recip = cache.get(idx);
			if (recip.qstatus != MessageRecip.STATUS_DONE) {
				if (!spool.isHardLinked()) spids_preserved.add(recip.spid);
			} else if (is_bounces_batch || recip.smtp_status == com.grey.mailismus.mta.Protocol.REPLYCODE_OK) {
				//this message has completed its lifecycle, successfully or otherwise, and must now be deleted from queue
				if (spool.isHardLinked()) {
					//one spool file per recipient, so it's easy to identify which ones to delete
					spool.delete(recip.spid, recip.qid);
				} else {
					if (Spooler.isMultiSPID(recip.spid)) {
						int cnt = spids_candidates.get(recip.spid);
						spids_candidates.put(recip.spid, cnt+1);
					} else {
						spids_done.add(recip.spid);
					}
				}
				delivcnt++;
			} else {
				/*
				 * This message was not successfully delivered in this batch run, so it's one of 3 cases:
				 * - Perm error, so shunt to bounced status
				 * - Temp error, so will be retried
				 * - Temp error that has now expired (msg in queue too long), so treat as perm error
				 * Either way we have to preserve it for now, as even bounced messages need to be retained till Reports Task
				 * has issued the NDR.
				 */
				if (!spool.isHardLinked()) spids_preserved.add(recip.spid);
				failcnt++;
			}
		}
		int undetermined = 0; //no. of SPIDs whose orphan status cannot be resolved without determineOrphans()
		int spidcache = 0;

		if (!spool.isHardLinked()) {
			// update the global refs (while pruning the orphan candidates) and then act on the preserved messages
			if (spids_candidates.size() != 0) {
				spidcache = spool.updateReferenceCounts(spids_candidates, spids_done);
			}
			if (spids_preserved.size() != 0) {
				IteratorInt it = spids_preserved.recycledIterator();
				while (it.hasNext()) spids_candidates.remove(it.next()); //spare determineOrphans() the bother
			}
			undetermined = spids_candidates.size();

			// if we have orphan candidates, ask the subclass to prune the set and then add the result to spids_done
			if (undetermined != 0) {
				tmpspidset.clear();
				IteratorInt it = spids_candidates.recycledKeysIterator();
				while (it.hasNext()) tmpspidset.add(it.next());
				determineOrphans(tmpspidset);
				it = tmpspidset.recycledIterator();
				while (it.hasNext()) spids_done.add(it.next());
			}
			// now delete the orphaned SPIDs
			spool.deleteOrphans(spids_done);
		}

		LEVEL loglvl = LEVEL.TRC;
		if (dsptch.getLogger().isActive(loglvl)) {
			tmpsb.setLength(0);
			tmpsb.append(loglbl).append("delivered=").append(delivcnt).append('/').append(cachesize);
			if (failcnt != 0) tmpsb.append(", rejected=").append(failcnt);
			if (undetermined != 0 || spids_done.size() != 0) {
				tmpsb.append(" - pruned spools=").append(spids_done.size());
				if (undetermined != 0) tmpsb.append(", undetermined=").append(undetermined);
			}
			if (spidcache != 0) tmpsb.append(" - spidcache=").append(spidcache);
			dsptch.getLogger().log(loglvl, tmpsb);
		}
		cache.clear();
		restoreCacheFields();
		return (delivcnt+failcnt);
	}

	public final java.io.OutputStream createDiagnosticFile(int spid, int qid) throws java.io.IOException
	{
		java.io.OutputStream fstrm = null;
		java.nio.file.Path fh = getDiagnosticFile(spid, qid);
		try {
			fstrm = java.nio.file.Files.newOutputStream(fh, FileOps.OPENOPTS_CREATE);
		} catch (Exception ex) {
			//assume initial failure was due to missing directory - another failure is genuine
			FileOps.ensureDirExists(FileOps.parentDirectory(fh));
			fstrm = java.nio.file.Files.newOutputStream(fh, FileOps.OPENOPTS_CREATE);
		}
		return fstrm;
	}

	public final java.nio.file.Path exportMessage(int spid, int qid, CharSequence dstpath) throws java.io.IOException
	{
		return spool.export(spid, qid, dstpath);
	}

	public final int housekeep() throws java.io.IOException
	{
		doHousekeeping();
		long max_age = maxretrytime + prune_grace;
		long cutoff = dsptch.getSystemTime() - max_age;
		return spool.housekeep(cutoff);
	}

	public int submit(ByteChars sender, java.util.ArrayList<com.grey.base.utils.EmailAddress> recips,
			java.util.ArrayList<ByteChars> sender_rewrites, int iprecv, ByteChars msgbody) throws java.io.IOException
	{
		QException ex_error = null;
		SubmitHandle sph = startSubmit(sender, recips, sender_rewrites, iprecv);
		int spid = sph.spid;
		boolean rollback = false;
		try {
			sph.write(msgbody);
		} catch (Throwable ex) {
			rollback = true;
			ex_error = (ex instanceof QException ? (QException)ex : new QException("Submit-write failed on spid="+Integer.toHexString(sph.spid), ex));
		}
		if (!endSubmit(sph, rollback)) {
			if (ex_error == null) ex_error = new QException("Submit failed on spid="+Integer.toHexString(sph.spid));
		}
		if (ex_error != null) throw ex_error;
		return spid;
	}

	public final SubmitHandle startSubmit(ByteChars sender, java.util.ArrayList<com.grey.base.utils.EmailAddress> recips,
			java.util.ArrayList<ByteChars> sender_rewrites, int iprecv) throws java.io.IOException
	{
		for (int idx = 0; idx != recips.size(); idx++) {
			com.grey.base.utils.EmailAddress recip = recips.get(idx);
			if (!verifyAddress(recip.full)) {
				throw new QException("Bad chars in recipient address - "+recip.full);
			}
		}
		return spool.create(sender, recips, sender_rewrites, iprecv, false);
	}

	// Returns True if we successfully carried out the initial request to commit or rollback the current submission transaction
	// If the request was to commit, then a return of False does not guarantee successfull rollback, merely a best effort.
	// The spool file-stream may or may not have been closed beforehand.
	public final boolean endSubmit(SubmitHandle sph, boolean rollback)
	{
		if (read_only) rollback = true;
		boolean success = true;
		try {
			if (!rollback) {
				success = sph.close(dsptch.getLogger()); //spool needs to be visible before control-queue commit
				if (success) success = storeMessage(sph);
			}
		} catch (Throwable ex) {
			success = false;
			dsptch.getLogger().log(LEVEL.INFO, ex, true, loglbl+"Submit-ctl failed on SPID="+Integer.toHexString(sph.spid));
		}

		if (rollback || !success) {
			int spid = sph.spid;
			boolean undone = spool.cancel(sph);
			if (!undone) {
				success = false;
				dsptch.getLogger().trace(loglbl+"submission/commit="+!rollback+" failed to rollback SPID="+Integer.toHexString(spid));
			}
		} else {
			spool.releaseHandle(sph);
		}
		return success;
	}

	public final int submitCopy(int src_spid, int src_qid, ByteChars sender,
			java.util.ArrayList<com.grey.base.utils.EmailAddress> recips)
	{
		java.nio.file.Path fhsrc = getMessage(src_spid, src_qid);
		SubmitHandle sph = null;
		boolean rollback = false;
		int spid = 0;

		try {
			sph = spool.create(sender, recips, null, 0, true);
			FileOps.copyFile(fhsrc, sph.getMessage()); //don't use Files.copy() as will lose hard links
		} catch (Throwable ex) {
			dsptch.getLogger().log(LEVEL.TRC, ex, !(ex instanceof java.io.IOException), loglbl+"failed to submit copy of spool="+fhsrc+" - created="+(sph!=null));
			rollback = true;
		}

		if (sph != null) {
			if (!rollback) spid = sph.spid;
			if (!endSubmit(sph, rollback)) spid = 0;
		}
		return spid;
	}

	// In the default case (batch size=2,500) we will allocate 7,500 ByteChars per batch (3 fields per MessageRecip entry).
	// This could potentially be reduced by interning the allocated fields, but over time the ObjectWell size would still
	// tend towards the ceiling of 7,500 (as it will never release the extra storage required by the least intern-friendly
	// batches) so probably not much point.
	protected final ByteChars allocCacheField(CharSequence inpval)
	{
		if (inpval == null) return null;
		ByteChars outval = bcwell.extract().populate(inpval);
		cacherefs.add(outval);
		return outval;
	}

	private void restoreCacheFields()
	{
		for (int idx = 0; idx != cacherefs.size(); idx++) {
			bcwell.store(cacherefs.get(idx));
		}
		cacherefs.clear();
	}

	protected final long getRetryDelay(int retrynum)
	{
		if (retrynum >= retrydelays.length) return retrydelays[retrydelays.length-1];
		return retrydelays[retrynum];
	}
}
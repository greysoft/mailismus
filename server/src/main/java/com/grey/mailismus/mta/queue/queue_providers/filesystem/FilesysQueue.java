/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem;

import com.grey.base.config.SysProps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteChars;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.mta.queue.MessageRecip;
import com.grey.mailismus.mta.queue.QException;
import com.grey.naf.NAFConfig;
import com.grey.logging.Logger.LEVEL;

public final class FilesysQueue
	extends com.grey.mailismus.mta.queue.Manager
	implements com.grey.mailismus.mta.queue.Spooler.SPID_Counter
{
	private static final String QDIR_INCOMING = "incoming"; //incoming messages are queued here
	private static final String QDIR_DEFERRED = "deferred"; //failing messages are queued here to be retried
	private static final String QDIR_BOUNCES = "bounces"; //failed/bounced messages are queued here to be reported
	private static final String QDIR_COMPOSE = "new"; //new messages are composed here before being queued to Incoming
	private static final byte DLM_FLDS = '|';
	private static final byte DLM_ROWS = '\n';

	private final java.io.File queueRoot;
	private final java.io.File deferredRoot;
	private final java.io.File incomingDir;
	private final java.io.File bounceDir;
	private final java.io.File composeNewDir;
	private final java.io.File composeBounceDir;
	private final java.nio.file.Path queueRoot_path;
	private final java.nio.file.Path deferredRoot_path;
	private final java.nio.file.Path incomingDir_path;
	private final java.nio.file.Path bounceDir_path;
	private final long maxDeferredIgnore;
	private final long retryGranularity;
	private final int maxFilesList;

	private final com.grey.base.collections.HashedMap<String,java.io.File> deferdirs //keyed on simple filename
			= new com.grey.base.collections.HashedMap<String,java.io.File>(0);
	private final com.grey.base.collections.HashedMap<MessageRecip, java.nio.file.Path> mmqcache
			= new com.grey.base.collections.HashedMap<MessageRecip, java.nio.file.Path>(0,4);

	// temp work areas, pre-allocated for efficiency
	private final java.util.ArrayList<String> load_dirnames = new java.util.ArrayList<String>();
	private final java.util.ArrayList<String> load_filenames = new java.util.ArrayList<String>();
	private final ByteChars ctlfile_databuf = new ByteChars();
	private final ByteChars tmpbc = new ByteChars();
	private final ByteChars tmplightbc = new ByteChars(-1); //lightweight object without own storage
	private final StringBuilder tmpsb = new StringBuilder();
	private final FilenameParser mmq_nameparser;

	private long lastload_deferred;

	public FilesysQueue(com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig qcfg, AppConfig appcfg, String name)
			throws java.io.IOException
	{
		super(d, qcfg, name);
		NAFConfig nafcfg = dsptch.getApplicationContext().getConfig();
		String queuepath = nafcfg.getPath(qcfg, "rootpath", null, false, nafcfg.path_var+"/queue", null);
		maxDeferredIgnore = qcfg.getTime("deferred_maxignore", "5m");
		maxFilesList = qcfg.getInt("maxfileslist", false, 0);
		retryGranularity = Math.max(TimeOps.MSECS_PER_SECOND, qcfg.getTime("retry_granularity", "1m")); //minimum is 1 sec

		queueRoot = new java.io.File(queuepath);
		incomingDir = new java.io.File(queueRoot, QDIR_INCOMING);
		deferredRoot = new java.io.File(queueRoot, QDIR_DEFERRED);
		bounceDir = new java.io.File(queueRoot, QDIR_BOUNCES);
		composeNewDir = new java.io.File(incomingDir, QDIR_COMPOSE);
		composeBounceDir = new java.io.File(bounceDir, QDIR_COMPOSE);

		queueRoot_path = queueRoot.toPath();
		incomingDir_path = incomingDir.toPath();
		deferredRoot_path = deferredRoot.toPath();
		bounceDir_path = bounceDir.toPath();
		mmq_nameparser = new FilenameParser(this);

		FileOps.ensureDirExists(composeNewDir);
		FileOps.ensureDirExists(composeBounceDir);
		FileOps.ensureDirExists(deferredRoot);

		dsptch.getLogger().info(loglbl+"Path=" + queuepath);
		dsptch.getLogger().info(loglbl+"retry_granularity="+TimeOps.expandMilliTime(retryGranularity)
				+"; deferred_maxignore="+TimeOps.expandMilliTime(maxDeferredIgnore)
				+"; maxmmqlist="+maxFilesList);
	}

	@Override
	public void loadSPIDs(com.grey.base.collections.HashedMapIntInt refcnt, int max) throws java.io.IOException
	{
		loadSPIDs(refcnt, max, queueRoot_path);
	}

	private boolean loadSPIDs(com.grey.base.collections.HashedMapIntInt refcnt, int max, java.nio.file.Path dh) throws java.io.IOException
	{
		try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dh)) {
			for (java.nio.file.Path fpath : ds) {
				if (max != 0 && refcnt.size() >= max) return true;
				if (java.nio.file.Files.isDirectory(fpath, FileOps.LINKOPTS_NONE)) {
					if (loadSPIDs(refcnt, max, fpath)) return true;
				} else {
					String filename = FileOps.getFilename(fpath);
					if (!mmq_nameparser.parse(filename)) continue; //not a valid MMQ file, ignore
					int spid = mmq_nameparser.parsed_spid;
					if (!com.grey.mailismus.mta.queue.Spooler.isMultiSPID(spid)) continue;
					int cnt = refcnt.get(spid);
					refcnt.put(spid, cnt+1);
				}
			}
		} catch (java.nio.file.NoSuchFileException ex) { //ok
		} catch (java.io.IOException ex) {
			if (java.nio.file.Files.exists(dh, FileOps.LINKOPTS_NONE)) throw ex;
		}
		return false;
	}

	// This method creates the control files that represent a queued message.
	// See FilenameParser for the filename format.
	// To guard against the race hazard whereby the Delivery thread might see partially written control files, we create them
	// in a temporary compose area first, and then move them into the Incoming directory afterwards. The move operation is
	// atomic, so this ensures that the Queue's Fetch methods don't see partial files.
	// Note that queue-manager objects are single threaded, so although storeMessage() and loadMessages() are both called in
	// different threads and might therefore appear to clash on ctlfile_databuf, only one of those methods would ever get callled
	// in a particular instance of this class.
	@Override
	protected boolean storeMessage(com.grey.mailismus.mta.queue.SubmitHandle sph) throws java.io.IOException
	{
		java.util.ArrayList<java.io.File> ctlfiles = new java.util.ArrayList<java.io.File>();
		boolean success = true;

		// Loop through all the recips to create their control files.
		// Create them in temp Compose area initially, to avoid the race hazards described in the header comment above.
		for (int idx = 0; idx != sph.recips.size(); idx++) {
			com.grey.base.utils.EmailAddress recip = sph.recips.get(idx);
			recip.decompose();
			int qid = idx + 1; //QID is only unique relative to SPID
			ByteChars sender_rewrite = (sph.sender_rewrites == null ? null : sph.sender_rewrites.get(idx));
			ByteChars sender = (sender_rewrite == null ? sph.sender : sender_rewrite);
			String filename = mmq_nameparser.buildNew(sph.spid, qid);
			java.io.File fh = new java.io.File(composeNewDir, filename);
			setMessage(ctlfile_databuf.clear(), sender, dsptch.getSystemTime(), sph.iprecv, recip.mailbox, recip.domain, 0, 0);
			Throwable ex = writeMessage(fh, ctlfile_databuf, null);
			if (ex != null) {
				dsptch.getLogger().log(LEVEL.INFO, ex, true, loglbl+"Store-New failed on recip="+idx+"/"+sph.recips.size()+" - "+filename);
				success = false;
				break;
			}
			ctlfiles.add(fh);
		}
		int rename_cnt = 0;

		// Now atomically move each control file into the Incoming dir, where they can be picked up
		if (success) {
			for (int idx = 0; idx != ctlfiles.size(); idx++) {
				java.io.File fh_tmp = ctlfiles.get(idx);
				java.io.File fh_new = new java.io.File(incomingDir, fh_tmp.getName());
				if (!fh_tmp.renameTo(fh_new)) {
					// assume failure is due to missing directory - a 2nd failure is genuine
					FileOps.ensureDirExists(fh_new.getParentFile());
					if (!fh_tmp.renameTo(fh_new)) {
						dsptch.getLogger().info(loglbl+"Rename-New failed on domain="+idx+"/"+ctlfiles.size()+" - "+fh_new.getAbsolutePath());
						success = false;
						break;
					}
				}
				ctlfiles.set(idx, fh_new);
				rename_cnt++;
			}
		}
		com.grey.mailismus.mta.queue.QException ex = null;

		// Rollback on error. It is critical that we remove any control files from the Incoming area, as this method guarantees to queue a
		// message in full, or not at all.
		// It is highly desirable to remove any leftover files in the temp Compose area as well, but failure to remove those does not result
		// in incorrect system behaviour (merely a buildup of shrapnel, which can be manually purged whenever), so we swallow the error.
		if (!success) {
			for (int idx = 0; idx != ctlfiles.size(); idx++) {
				java.io.File fh = ctlfiles.get(idx);
				if (!fh.delete()) {
					// delete failed - carry on looping to clean up as much as possible
					String errmsg = loglbl+"Rollback failed on domain="+idx+"/"+rename_cnt+"/"+ctlfiles.size()+" for "+fh.getAbsolutePath();
					dsptch.getLogger().info(errmsg);
					if (idx < rename_cnt) {
						//this file is in the Incoming area, so throw once we've finished cleaning up
						if (ex != null) errmsg += "; "+com.grey.base.ExceptionUtils.summary(ex);
						ex = new com.grey.mailismus.mta.queue.QException(errmsg);
					}
				}
			}
		}
		if (ex != null) throw ex;
		return success;
	}

	@Override
	public void updateMessages(com.grey.mailismus.mta.queue.Cache msgcache, boolean is_bounces_batch)
	{
		int cachesize = msgcache.size();
		for (int idx = 0; idx != cachesize; idx++) {
			final MessageRecip recip = msgcache.get(idx);
			if (recip.qstatus != MessageRecip.STATUS_DONE) {
				mmqcache.remove(recip); //leave the control file as is
				continue;
			}

			if (is_bounces_batch || recip.smtp_status == com.grey.mailismus.mta.Protocol.REPLYCODE_OK) {
				continue;
			}

			// Schedule the next retry - how soon depends on number of failures so far.
			long delay = getRetryDelay(recip.retrycnt);
			long nextsend = dsptch.getSystemTime() + delay;
			long roundup = retryGranularity - (nextsend % retryGranularity);
			nextsend += roundup; //round up next-send time to next standard interval
			long maxtime = (recip.sender == null ? maxretrytime_ndr : maxretrytime);
			boolean isBounce = (nextsend - recip.recvtime >= maxtime || recip.smtp_status >= com.grey.mailismus.mta.Protocol.PERMERR_BASE);
			recip.retrycnt++;

			// Construct filename of MMQ file to write to - see FilenameParser for format.
			java.io.File dirh_rename = null;
			java.io.File dirh;
			String filename;
			if (isBounce) {
				// Write to bounces dir. As is the case for new incoming messages, we rename the finished control file into
				// its official directory after writing it, to ensure that the getBounces() method (which is called in a
				// different thread) does not see partial files.
				dirh = composeBounceDir;
				dirh_rename = bounceDir;
				filename = mmq_nameparser.buildBounce(recip.spid, recip.qid);
			} else {
				// Identify which deferred directory to write to.
				// No need to do rename-after-create for deferred MMQ files, because the thread which calls this method to
				// create them is also the one which loads them, so it will never see partial files.
				tmpsb.setLength(0);
				tmpsb.append(FilenameParser.PFX_DEFERDIR).append(nextsend/1000); //min resolution is 1 sec, so avoid extra zeroes
				dirh = getRetryDir(tmpsb.toString());
				filename = mmq_nameparser.buildRetry(recip.spid, recip.qid);
			}
			// and write the new control file to disk
			java.io.File fh = new java.io.File(dirh, filename);
			setMessage(ctlfile_databuf.clear(), recip.sender, recip.recvtime, recip.ip_recv, recip.mailbox_to, recip.domain_to,
					recip.retrycnt, recip.smtp_status);
			Throwable ex = writeMessage(fh, ctlfile_databuf, dirh_rename);
			if (ex != null) {
				/*
				 * We've failed to save the current message's new MMQ file to disk, and the MMQ file it was loaded from will
				 * be deleted below. We have to prevent that, so that the previous MMQ can be reloaded and tried again in a
				 * future batch.
				 */
				dsptch.getLogger().log(LEVEL.WARN, ex, true, loglbl+"Store-Deferred failed on msg="+idx+"/"+cachesize+" - "+fh.getAbsolutePath());
				recip.qstatus = MessageRecip.STATUS_READY; //signals base class to preserve the SPID
				mmqcache.remove(recip);
			}
		}

		// Now that all messages have been requeued or delivered, we delete the original control files from which this batch of messages was loaded
		java.util.Iterator<MessageRecip> it_mmq = mmqcache.keysIterator();
		while (it_mmq.hasNext()) {
			MessageRecip mr = it_mmq.next();
			java.nio.file.Path fh = mmqcache.get(mr);
			Exception ex = FileOps.deleteFile(fh);
			if (ex != null) {
				//it has been processed, but will now get processed again, so remove from this batch's stats
				dsptch.getLogger().info(loglbl+"Failed to delete MMQ="+fh+" - "+ex);
			}
		}
		mmqcache.clear();
	}

	@Override
	protected void determineOrphans(com.grey.base.collections.HashedSetInt orphan_candidates)
	{
		pruneActiveSPIDs(orphan_candidates, queueRoot_path);
	}

	private boolean pruneActiveSPIDs(com.grey.base.collections.HashedSetInt spids, java.nio.file.Path dirh)
	{
		try {
			try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dirh)) {
				for (java.nio.file.Path fpath : ds) {
					if (spids.isEmpty()) return true;
					if (java.nio.file.Files.isDirectory(fpath, FileOps.LINKOPTS_NONE)) {
						if (pruneActiveSPIDs(spids, fpath)) return true;
						continue;
					}
					String filename = FileOps.getFilename(fpath);
					if (!mmq_nameparser.parse(filename)) continue; //not a valid MMQ file, ignore
					spids.remove(mmq_nameparser.parsed_spid); //a related MMQ file exists, so this spool is not an orphan
				}
			}
		} catch (java.nio.file.NoSuchFileException ex) { //ok
		} catch (java.io.IOException ex) {
			if (java.nio.file.Files.exists(dirh, FileOps.LINKOPTS_NONE)) {
				dsptch.getLogger().log(LEVEL.ERR, ex, false, "Failed to open queue-directory to scan for orphaned SPIDs - "+dirh);
			}
		}
		return false;
	}

	@Override
	protected void loadMessages(com.grey.mailismus.mta.queue.Cache msgcache, boolean get_bounces, boolean get_deferred)
		throws java.io.IOException
	{
		mmqcache.clear();
		if (get_bounces) {
			loadMessages(msgcache, bounceDir_path, FilenameParser.PFX_BOUNCEFILE);
		} else {
			if (dsptch.getSystemTime() >= lastload_deferred + maxDeferredIgnore) {
				loadDeferred(msgcache, get_deferred);
				if (msgcache.size() != msgcache.capacity()) loadMessages(msgcache, incomingDir_path, FilenameParser.PFX_MSGFILE);
				lastload_deferred = dsptch.getSystemTime();
			} else {
				loadMessages(msgcache, incomingDir_path, FilenameParser.PFX_MSGFILE);
				if (msgcache.size() != msgcache.capacity()) loadDeferred(msgcache, get_deferred);
			}
		}
	}

	private void loadDeferred(com.grey.mailismus.mta.queue.Cache msgcache, boolean fetchAll) throws java.io.IOException
	{
		java.util.ArrayList<String> dirlist = load_dirnames;
		dirlist.clear();
		FileOps.directoryListSimple(deferredRoot_path, 0, dirlist);
		if (dirlist.size() == 0) return;
		java.util.Collections.sort(dirlist);

		for (int idx = 0; idx != dirlist.size(); idx++) {
			if (msgcache.size() == msgcache.capacity()) break;
			String dirname = dirlist.get(idx);
			if (dirname.charAt(0) != FilenameParser.PFX_DEFERDIR) continue; //safety check
			if (!fetchAll) {
				tmpbc.populate(dirname, FilenameParser.FILENAME_PFXLEN, dirname.length() - FilenameParser.FILENAME_PFXLEN);
				long nextsend = tmpbc.parseDecimal() * 1000; //parse scheduled retry time out of directory name
				if (nextsend > dsptch.getSystemTime()) break; //not yet due
			}
			java.io.File dirh = getRetryDir(dirname);

			if (!loadMessages(msgcache, dirh.toPath(), FilenameParser.PFX_MSGFILE)) {
				try {
					boolean opsts = dirh.delete();
					deferdirs.remove(dirname);
					LEVEL loglvl = LEVEL.TRC2;
					if (dsptch.getLogger().isActive(loglvl)) dsptch.getLogger().log(loglvl, loglbl+"Cleared stale DeferDir with status="+opsts+" - "+dirname);
				} catch (Exception ex) {
					dsptch.getLogger().log(LEVEL.TRC, ex, false, loglbl+"Failed to delete stale DeferDir="+dirh.getParent()+java.io.File.separatorChar+dirh.getName());
				}
			}
		}
	}

	private boolean loadMessages(com.grey.mailismus.mta.queue.Cache msgcache, java.nio.file.Path dirh, char filepfx)
		throws java.io.IOException
	{
		java.util.ArrayList<String> dirlist = load_filenames;
		dirlist.clear();
		FileOps.directoryListSimple(dirh, maxFilesList, dirlist);
		int numfiles = dirlist.size();
		if (numfiles == 0) return false;
		java.util.Collections.sort(dirlist);

		for (int idx = 0; idx != numfiles; idx++) {
			// load messages (plural because each recip is a distinct entry) from current control file
			if (msgcache.size() == msgcache.capacity()) break;
			final String filename = dirlist.get(idx);
			if (filename.charAt(0) != filepfx || !mmq_nameparser.parse(filename)) continue;
			final int spid = mmq_nameparser.parsed_spid;
			final int qid = mmq_nameparser.parsed_qid;
			final int prevcachesize = msgcache.size();
			try {
				// Read entire file into in-memory buffer
				// The general-purpose FileOps.read() methods are too slow for our simple needs.
				java.nio.file.Path fh = dirh.resolve(filename);
				java.io.InputStream strm = java.nio.file.Files.newInputStream(fh, FileOps.OPENOPTS_NONE);
				try {
					FileOps.read(strm, -1, ctlfile_databuf.clear());
				} finally {
					strm.close();
				}

				int lmt = ctlfile_databuf.indexOf(DLM_FLDS);
				tmplightbc.set(ctlfile_databuf, 0, lmt);
				ByteChars addr_from = (tmplightbc.size() == 0 ? null : allocCacheField(tmplightbc));
				int off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				long recvtime = ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_ROWS);
				int iprecv = (int)ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				tmplightbc.set(ctlfile_databuf, off, lmt - off);
				ByteChars mbx_to = allocCacheField(tmplightbc);
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				tmplightbc.set(ctlfile_databuf, off, lmt - off);
				ByteChars domain_to = (tmplightbc.size() == 0 ? null : allocCacheField(tmplightbc));
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				short retrycnt = (short)ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				int status = 0;
				lmt = ctlfile_databuf.indexOf(off, DLM_ROWS);
				if (filepfx == FilenameParser.PFX_BOUNCEFILE) status = (int)ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				MessageRecip mr = msgcache.addEntry(qid, spid, recvtime, iprecv, addr_from, domain_to, mbx_to, retrycnt, status);
				mmqcache.put(mr, fh);
			} catch (Throwable ex) {
				// Discard all recips from this control file and continue. That means this control file will remain here until our next load
				// and we may well fail to parse it every single time, so log at level=warning to alert admins that they may want to remove
				// this MMQ file.
				// The whole purpose of our verifyAddress() method is to help ensure this situation never arises.
				dsptch.getLogger().log(LEVEL.WARN, ex, false, loglbl+"Failed to load MMQ queuefile="+filename+" from list="+numfiles);
				msgcache.truncate(prevcachesize);
			}
		}
		return true;
	}

	@Override
	public int qsize(CharSequence sender, CharSequence recip, int flags) throws java.io.IOException
	{
		if (sender != null && sender.length() == 0) sender = null;
		if (recip != null && recip.length() == 0) recip = null;
		if (sender != null || recip != null) return -1; //filtering on sender or recip not supported
		java.util.ArrayList<java.nio.file.Path> lst = new java.util.ArrayList<java.nio.file.Path>();
		if (flags == 0 || (flags & SHOWFLAG_NEW) != 0) lst.add(incomingDir_path);
		if (flags == 0 || (flags & SHOWFLAG_TEMPERR) != 0) lst.add(deferredRoot_path);
		if (flags == 0 || (flags & SHOWFLAG_BOUNCES) != 0) lst.add(bounceDir_path);
		int total = 0;
		for (int idx = 0; idx != lst.size(); idx++) {
			total += countMessages(lst.get(idx));
		}
		return total;
	}

	private int countMessages(java.nio.file.Path dh) throws java.io.IOException
	{
		int total = 0;
		try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dh)) {
			for (java.nio.file.Path fpath : ds) {
				if (java.nio.file.Files.isDirectory(fpath, FileOps.LINKOPTS_NONE)) {
					total += countMessages(fpath);
				} else {
					String filename = FileOps.getFilename(fpath);
					if (mmq_nameparser.parse(filename)) total++;
				}
			}
		} catch (java.nio.file.NoSuchFileException ex) { //ok
		} catch (java.io.IOException ex) {
			if (java.nio.file.Files.exists(dh, FileOps.LINKOPTS_NONE)) throw ex;
		}
		return total;
	}

	private Throwable writeMessage(java.io.File fh, ByteChars data, java.io.File newdir)
	{
		Throwable err = null;
		Throwable err2 = null;
		java.io.FileOutputStream fstrm = null;

		try {
			try {
				fstrm = new java.io.FileOutputStream(fh, false);
			} catch (Exception ex) {
				// assume failure is due to missing directory - a 2nd failure is genuine
				FileOps.ensureDirExists(fh.getParentFile());
				fstrm = new java.io.FileOutputStream(fh, true);
			}
			fstrm.write(data.buffer(), data.offset(), data.length());
		} catch (Throwable ex) {
			err = new QException("Write failed on "+fh.getAbsolutePath(), ex);
		} finally {
			try {
				if (fstrm != null) fstrm.close();
			} catch (Throwable ex) {
				String txt= "Close failed on "+fh.getAbsolutePath();
				if (err != null) txt += " - "+com.grey.base.ExceptionUtils.summary(ex);
				err2 = new QException(txt, ex);
			}
		}
		if (err == null) err = err2;

		if (err == null && newdir != null) {
			java.io.File newpath = new java.io.File(newdir, fh.getName());
			if (!fh.renameTo(newpath)) {
				// assume failure is due to missing directory - a 2nd failure is genuine
				try {
					FileOps.ensureDirExists(newpath.getParentFile());
					if (!fh.renameTo(newpath)) {
						err = new com.grey.mailismus.mta.queue.QException("Rename failed on "+fh.getAbsolutePath()+" => "+newpath);
					}
				} catch (Exception ex) {
					err = ex;
				}
			}
		}
		return err;
	}

	private void setMessage(ByteChars data, ByteChars sender, long recvtime, int iprecv,
			ByteChars mbx, ByteChars domain_to, int retrycnt, int status)
	{
		data.append(sender).append(DLM_FLDS).append(recvtime, tmpsb).append(DLM_FLDS).append(iprecv, tmpsb).append(DLM_ROWS);
		data.append(mbx).append(DLM_FLDS).append(domain_to).append(DLM_FLDS);
		data.append(retrycnt, tmpsb).append(DLM_FLDS).append(status, tmpsb).append(DLM_ROWS);
	}

	private java.io.File getRetryDir(String dirname)
	{
		java.io.File dirh = deferdirs.get(dirname);
		if (dirh == null) {
			// we're either finding existing directories on startup, or about to create a new one
			dirh = new java.io.File(deferredRoot, dirname);
			deferdirs.put(dirname, dirh);
		}
		return dirh;
	}

	// Filter out illegal chars that would break our parsing of MMQ files.
	// Ignore all other considerations about what constitutes a legal email address, as that's dealt with elsewhere.
	@Override
	public boolean verifyAddress(ByteChars addr)
	{
		byte[] buf = addr.buffer();
		int off = addr.offset();
		int lmt = off + addr.size();

		while (off != lmt) {
			byte b = buf[off++];
			if (b == DLM_FLDS || b == DLM_ROWS || b == FilenameParser.DLM_FILENAME) return false;
			if (b == ':' && SysProps.isWindows) return false;
		}
		return true;
	}
}

/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem_cluster;

import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.queue.MessageRecip;
import com.grey.logging.Logger.LEVEL;

public final class ClusteredQueue
	extends com.grey.mailismus.mta.queue.QueueManager
	implements com.grey.mailismus.mta.queue.Spooler.SPID_Counter
{
	private static final String QDIR_INCOMING = "incoming"; //incoming messages are queued here
	private static final String QDIR_DEFERRED = "deferred"; //failing messages are queued here to be retried
	private static final String QDIR_BOUNCES = "bounces"; //failed/bounced messages are queued here to be reported
	private static final byte DLM_FLDS = '|';
	private static final byte DLM_ROWS = '\n';

	private final java.nio.file.Path queueRoot;
	private final java.nio.file.Path deferredRoot;
	private final java.nio.file.Path incomingDir;
	private final java.nio.file.Path bounceDir;
	private final java.nio.file.Path[] orphan_scandirs;
	private final int maxClusterSize;
	private final long maxDeferredIgnore;
	private final long retryGranularity;
	private final int maxFilesList;

	private final ClusterController cctl;
	private final java.util.ArrayList<MessageCluster> batch_clusters = new java.util.ArrayList<MessageCluster>();
	private final java.util.ArrayList<MessageCluster> rollback_clusters = new java.util.ArrayList<MessageCluster>();
	private final java.util.HashMap<java.nio.file.Path, ByteChars> defer_databufs
			= new java.util.HashMap<java.nio.file.Path, ByteChars>();
	private final com.grey.base.collections.HashedMap<String,java.nio.file.Path> defer_dirs
			= new com.grey.base.collections.HashedMap<String,java.nio.file.Path>(0); //keyed on simple filename

	private MessageCluster currentIncomingCluster;
	private MessageCluster partial_cluster;
	private int partial_offset;
	private long lastload_deferred;

	// temp work areas, pre-allocated for efficiency
	private final java.util.ArrayList<String> load_dirnames = new java.util.ArrayList<String>();
	private final java.util.ArrayList<String> load_filenames = new java.util.ArrayList<String>();
	private final ByteChars ctlfile_databuf = new ByteChars();
	private final StringBuilder tmpsb = new StringBuilder();
	private final ByteChars tmplightbc = new ByteChars(-1); //lightweight object without own storage
	//these three are for ShowQ
	private final ByteChars tmplightbc1 = new ByteChars(-1);
	private final ByteChars tmplightbc2 = new ByteChars(-1);
	private final ByteChars tmplightbc3 = new ByteChars(-1);

	@Override
	public boolean supportsShow() {return true;}

	public ClusteredQueue(Dispatcher d, XmlConfig qcfg, AppConfig appcfg, String name) throws java.io.IOException
	{
		super(d, qcfg, name);
		NAFConfig nafcfg = dsptch.getApplicationContext().getConfig();
		String queuepath = nafcfg.getPath(qcfg, "rootpath", null, false, nafcfg.getPathVar()+"/queue", null);
		maxClusterSize = (int)qcfg.getSize("maxclustersize", "256K");
		maxDeferredIgnore = qcfg.getTime("deferred_maxignore", "5m");
		maxFilesList = qcfg.getInt("maxfileslist", false, 1000); //filenames limit should be under 30K
		retryGranularity = Math.max(TimeOps.MSECS_PER_SECOND, qcfg.getTime("retry_granularity", "1m")); //minimum is 1 sec

		queueRoot = java.nio.file.Paths.get(queuepath).toAbsolutePath();
		incomingDir = queueRoot.resolve(QDIR_INCOMING);
		deferredRoot = queueRoot.resolve(QDIR_DEFERRED);
		bounceDir = queueRoot.resolve(QDIR_BOUNCES);

		orphan_scandirs = new java.nio.file.Path[]{deferredRoot, incomingDir, bounceDir};

		cctl = ClusterController.getController(d.getApplicationContext(), queueRoot, d, d.getLogger());
		FileOps.ensureDirExists(incomingDir);

		dsptch.getLogger().info(loglbl+"root-path="+queuepath);
		dsptch.getLogger().info(loglbl+"maxcluster="+ByteOps.expandByteSize(maxClusterSize)
				+"; retry_granularity="+TimeOps.expandMilliTime(retryGranularity)
				+"; deferred_maxignore="+TimeOps.expandMilliTime(maxDeferredIgnore)
				+"; maxmqclist="+maxFilesList);
	}

	@Override
	protected void shutdown()
	{
		if (currentIncomingCluster != null) currentIncomingCluster.close();
		currentIncomingCluster = null;
	}

	// stream the directory contents rather than listing them first, in case they're huge
	@Override
	public void loadSPIDs(com.grey.base.collections.HashedMapIntInt refcnt, int max) throws java.io.IOException
	{
		loadSPIDs(refcnt, max, queueRoot);
	}

	// This only gets called if there were misses on the in-memory cache which is biased towards more recent messages. So
	// by scanning deferredRoot (which probably contains the oldest messages) first, we maximise the chances of resolving
	// the candidates without having to traverse the entire directory tree. Similiarly, bounce messages normally exist only
	// fleetingly, so an overloaded incoming area will probably contain older files than the bounces directory.
	@Override
	protected void determineOrphans(com.grey.base.collections.HashedSetInt orphan_candidates) throws java.io.IOException
	{
		for (java.nio.file.Path dh : orphan_scandirs) {
			if (determineOrphans(orphan_candidates, dh)) break;
		}
	}

	// Note that queue-manager objects are single threaded, so although storeMessage() and loadMessages() are both called in
	// different threads and might therefore appear to clash on ctlfile_databuf, only one of those methods would ever get callled
	// in a particular instance of this class.
	@Override
	protected boolean storeMessage(com.grey.mailismus.mta.queue.SubmitHandle sph) throws java.io.IOException
	{
		ctlfile_databuf.clear();
		for (int idx = 0; idx != sph.recips.size(); idx++) {
			int qid = idx + 1; //QID is only unique relative to SPID
			com.grey.base.utils.EmailAddress recip = sph.recips.get(idx);
			recip.decompose();
			ByteChars sender_rewrite = (sph.sender_rewrites == null ? null : sph.sender_rewrites.get(idx));
			ByteChars sender = (sender_rewrite == null ? sph.sender : sender_rewrite);
			appendMessage(ctlfile_databuf, sph.spid, qid, sender, recip.domain, recip.mailbox, dsptch.getSystemTime(), sph.iprecv, 0, 0);
		}
		if (currentIncomingCluster == null) currentIncomingCluster = cctl.createCluster(incomingDir, maxClusterSize);
		currentIncomingCluster = cctl.writeCluster(currentIncomingCluster, ctlfile_databuf);
		return true;
	}

	@Override
	protected void updateMessages(com.grey.mailismus.mta.queue.Cache msgcache, boolean is_bounces_batch)
		throws java.io.IOException
	{
		rollback_clusters.clear();
		defer_databufs.clear();
		defer_dirs.clear();
		final int cachesize = msgcache.size();
		ByteChars bounces_databuf = null;
		ByteChars unprocessed_databuf = null;
		long minrecvtime = dsptch.getSystemTime(); //only applies to unprocessed entries

		for (int idx = 0; idx != cachesize; idx++) {
			final MessageRecip recip = msgcache.get(idx);
			if (recip.qstatus != MessageRecip.STATUS_DONE) {
				// roll these over into a new cluster file, as the old one has to be deleted
				if (unprocessed_databuf == null) unprocessed_databuf = bcwell.extract().clear();
				appendMessage(unprocessed_databuf, recip.spid, recip.qid, recip.sender, recip.domain_to, recip.mailbox_to,
						recip.recvtime, recip.ip_recv, recip.retrycnt, recip.smtp_status);
				if (recip.recvtime < minrecvtime) minrecvtime = recip.recvtime;
				continue;
			}

			if (is_bounces_batch || recip.smtp_status == Protocol.REPLYCODE_OK) {
				continue;
			}

			// Failed message, so schedule the next retry - how soon depends on number of failures so far.
			long delay = getRetryDelay(recip.retrycnt);
			long nextsend = dsptch.getSystemTime() + delay;
			long roundup = retryGranularity - (nextsend % retryGranularity);
			nextsend += roundup; //round up next-send time to next standard interval
			long maxtime = (recip.sender == null ? maxretrytime_ndr : maxretrytime);
			boolean isBounce = (nextsend - recip.recvtime >= maxtime || recip.smtp_status >= Protocol.PERMERR_BASE);
			recip.retrycnt++;

			//add current recipient to appropriate cluster (in fact, we just update its buffered data for now)
			ByteChars cluster_databuf;
			if (isBounce) {
				if (bounces_databuf == null) bounces_databuf = bcwell.extract().clear();
				cluster_databuf = bounces_databuf;
			} else {
				//Identify which deferred directory to write to. The directory name encodes the next-send timestamp, which we
				//store in the minimum resolution of seconds, to avoid the spurious extra 3 zeroes of a millisecond timestamp.
				tmpsb.setLength(0);
				tmpsb.append(MessageCluster.PFX_DEFERDIR).append(nextsend/1000);
				String dirname = tmpsb.toString();
				java.nio.file.Path dirpath = defer_dirs.get(dirname);
				if (dirpath == null) {
					dirpath = deferredRoot.resolve(dirname);
					defer_dirs.put(dirname, dirpath);
				}
				cluster_databuf = defer_databufs.get(dirpath);
				if (cluster_databuf == null) {
					cluster_databuf = bcwell.extract().clear();
					defer_databufs.put(dirpath, cluster_databuf);
				}
			}
			appendMessage(cluster_databuf, recip.spid, recip.qid, recip.sender, recip.domain_to, recip.mailbox_to,
					recip.recvtime, recip.ip_recv, recip.retrycnt, recip.smtp_status);
		}

		// now create the pending clusters for deferred/bounced/unprocessed recipients
		java.nio.file.Path parentdir = null;
		try {
			for (java.nio.file.Path dirpath : defer_databufs.keySet()) {
				parentdir = dirpath;
				ByteChars databuf = defer_databufs.get(dirpath);
				try {
					//don't cache, as it could be ages before we load it and don't want to keep its object ref in memory
					MessageCluster cluster = cctl.createCluster(dirpath, databuf, 0, true);
					rollback_clusters.add(cluster);
				} finally {
					bcwell.store(databuf);
				}
			}
			if (bounces_databuf != null) {
				parentdir = bounceDir;
				try {
					MessageCluster cluster = cctl.createCluster(bounceDir, bounces_databuf, 0, false);
					rollback_clusters.add(cluster);
				} finally {
					bcwell.store(bounces_databuf);
				}
			}
			if (unprocessed_databuf != null) {
				//These recips have already been eligible for loading once, so name new cluster to sort before any later
				//incoming messages.
				parentdir = (is_bounces_batch ? bounceDir : incomingDir);
				try {
					MessageCluster cluster = cctl.createCluster(parentdir, unprocessed_databuf, minrecvtime, false);
					rollback_clusters.add(cluster);
				} finally {
					bcwell.store(unprocessed_databuf);
				}
			}
		} catch (Exception ex) {
			//one of the cluster writes must have failed
			dsptch.getLogger().log(LEVEL.WARN, ex, true, loglbl+"Failed to create cluster under "+parentdir);
			for (int idx = 0; idx != rollback_clusters.size(); idx++) {
				cctl.deleteCluster(rollback_clusters.get(idx));
			}
			throw ex;
		}

		// Now that all messages have been successfully delivered or requeued, we delete the original clusters
		// from which this batch of messages was loaded
		for (int idx = 0; idx != batch_clusters.size(); idx++) {
			cctl.deleteCluster(batch_clusters.get(idx));
		}
		if (partial_cluster != null) cctl.setPartialOffset(partial_cluster, partial_offset);

		batch_clusters.clear();
		rollback_clusters.clear();
		defer_databufs.clear();
	}

	@Override
	protected void loadMessages(com.grey.mailismus.mta.queue.Cache msgcache, boolean get_bounces, boolean get_deferred)
		throws java.io.IOException
	{
		batch_clusters.clear();
		partial_cluster = null;
		partial_offset = 0;

		if (get_bounces) {
			loadClusters(msgcache, bounceDir, true);
		} else {
			if (dsptch.getSystemTime() >= lastload_deferred + maxDeferredIgnore) {
				loadDeferred(msgcache, get_deferred);
				if (msgcache.size() != msgcache.capacity()) loadClusters(msgcache, incomingDir, false);
				lastload_deferred = dsptch.getSystemTime();
			} else {
				loadClusters(msgcache, incomingDir, false);
				if (msgcache.size() != msgcache.capacity()) loadDeferred(msgcache, get_deferred);
			}
		}
	}

	private void loadDeferred(com.grey.mailismus.mta.queue.Cache msgcache, boolean fetchAll) throws java.io.IOException
	{
		java.util.ArrayList<String> dirlist = load_dirnames;
		dirlist.clear();
		FileOps.directoryListSimple(deferredRoot, 0, dirlist);
		if (dirlist.size() == 0) return;
		java.util.Collections.sort(dirlist);

		for (int idx = 0; idx != dirlist.size(); idx++) {
			if (msgcache.size() == msgcache.capacity()) break;
			String dirname = dirlist.get(idx);
			if (dirname.charAt(0) != MessageCluster.PFX_DEFERDIR) continue;
			if (!fetchAll) {
				ByteChars bc = bcwell.extract();
				bc.populate(dirname, 1, dirname.length() - 1);
				long nextsend = bc.parseDecimal() * 1000; //parse scheduled retry time out of directory name
				bcwell.store(bc);
				if (nextsend > dsptch.getSystemTime()) break; //not yet due
			}
			java.nio.file.Path dirpath = deferredRoot.resolve(dirname);
			boolean non_empty = true;

			try {
				non_empty = loadClusters(msgcache, dirpath, false);
			} catch (java.io.IOException ex) {
				dsptch.getLogger().log(LEVEL.WARN, ex, false, loglbl+"Failed to load DeferDir="+dirpath);
			}

			if (!non_empty) {
				try {
					java.nio.file.Files.delete(dirpath);
					LEVEL loglvl = LEVEL.TRC2;
					if (dsptch.getLogger().isActive(loglvl)) dsptch.getLogger().log(loglvl, loglbl+"Cleared stale DeferDir="+dirname);
				} catch (Exception ex) {
					dsptch.getLogger().log(LEVEL.TRC, ex, false, loglbl+"Failed to delete stale DeferDir="+dirpath);
				}
			}
		}
	}

	// It's not absolutely imperative that we process the clusters fairly (oldest first) but we might as well and it also
	// gives us the benefit of probably reading files that are contiguously stored on disk, if they were created in rapid
	// succession (eg. on a busy system). If the directory is huge however, we forego the benefits of sorting its full
	// contents and make do with an arbitrary subset.
	private boolean loadClusters(com.grey.mailismus.mta.queue.Cache msgcache, java.nio.file.Path dirh, boolean as_bounces)
		throws java.io.IOException
	{
		java.util.ArrayList<String> dirlist = load_filenames;
		dirlist.clear();
		FileOps.directoryListSimple(dirh, maxFilesList, dirlist);
		if (dirlist.size() == 0) return false;
		java.util.Collections.sort(dirlist);
		boolean has_files = false;

		for (int idx = 0; idx != dirlist.size(); idx++) {
			if (msgcache.size() == msgcache.capacity()) break;
			String filename = dirlist.get(idx);
			if (filename.charAt(0) != MessageCluster.PFX_CLUSTER || !filename.endsWith(MessageCluster.SUFFIX)) continue;
			if (loadCluster(dirh, filename, msgcache, as_bounces)) has_files = true;
		}
		return has_files;
	}

	private boolean loadCluster(java.nio.file.Path dirh, String filename, int maxcapacity,
		com.grey.mailismus.mta.queue.Cache msgcache, boolean as_bounces,
		com.grey.base.utils.IntValue total, StringBuilder displaybuf, CharSequence filter_sender, CharSequence filter_recip, java.util.Calendar dtcal,
		com.grey.base.collections.HashedSetInt orphan_candidates,
		com.grey.base.collections.HashedMapIntInt spids_refcnt) throws java.io.IOException
	{
		int prevcachesize = (msgcache == null ? 0 : msgcache.size());
		java.nio.file.Path fpath = dirh.resolve(filename);
		MessageCluster cluster = cctl.getCluster(fpath);
		java.io.InputStream strm = cluster.load();
		if (strm == null) return false;

		try {
			// Read entire file into in-memory buffer
			try {
				FileOps.read(strm, -1, ctlfile_databuf.clear());
			} finally {
				strm.close();
			}
			strm = null;
			long nextsend = 0; //will remain 0 for a Bounces cluster

			if (total != null) {
				if (displaybuf != null) {
					String dirname = FileOps.getFilename(FileOps.parentDirectory(fpath));
					if (dirname.equals(QDIR_INCOMING)) {
						nextsend = -1;
					} else if (!dirname.equals(QDIR_BOUNCES)) {
						ByteChars bc = bcwell.extract();
						bc.populate(dirname, 1, dirname.length() - 1);
						nextsend = bc.parseDecimal(); //parse time from deferred-dir name
						bcwell.store(bc);
					}
				} else {
					if (filter_sender == null && filter_recip == null) {
						int cnt = ctlfile_databuf.count(DLM_ROWS);
						total.val += cnt;
						return true;
					}
				}
			}
			int off = cctl.getPartialOffset(cluster);

			// loop through the messages - one per line
			while (off < ctlfile_databuf.size()) {
				if (maxcapacity != 0 && msgcache != null && msgcache.size() == maxcapacity) {
					partial_cluster = cluster;
					partial_offset = off;
					return true;
				}
				int lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				int spid = (int)ctlfile_databuf.parseHexadecimal(off, lmt - off);
				off = lmt + 1;

				if (orphan_candidates != null) {
					if (com.grey.mailismus.mta.queue.Spooler.isMultiSPID(spid)) {
						orphan_candidates.remove(spid);
						if (orphan_candidates.isEmpty()) break;
					}
					off = ctlfile_databuf.indexOf(off, DLM_ROWS) + 1;
					continue;
				}
				if (spids_refcnt != null) {
					if (com.grey.mailismus.mta.queue.Spooler.isMultiSPID(spid)) {
						int cnt = spids_refcnt.get(spid);
						spids_refcnt.put(spid, cnt+1);
						if (maxcapacity != 0 && spids_refcnt.size() >= maxcapacity) break;
					}
					off = ctlfile_databuf.indexOf(off, DLM_ROWS) + 1;
					continue;
				}

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				int qid = (int)ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				tmplightbc.set(ctlfile_databuf, off, lmt - off);
				ByteChars addr_from = (tmplightbc.size() == 0 ? null :
						(total == null ? allocCacheField(tmplightbc) : tmplightbc1.set(tmplightbc)));
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				tmplightbc.set(ctlfile_databuf, off, lmt - off);
				ByteChars mbx_to = (total == null ? allocCacheField(tmplightbc) : tmplightbc2.set(tmplightbc));
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				tmplightbc.set(ctlfile_databuf, off, lmt - off);
				ByteChars domain_to = (tmplightbc.size() == 0 ? null :
						(total == null ? allocCacheField(tmplightbc) : tmplightbc3.set(tmplightbc)));
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				long recvtime = ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				int iprecv = (int)ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				lmt = ctlfile_databuf.indexOf(off, DLM_FLDS);
				int retrycnt = (int)ctlfile_databuf.parseDecimal(off, lmt - off);
				off = lmt + 1;

				int status = 0;
				lmt = ctlfile_databuf.indexOf(off, DLM_ROWS);
				if (lmt == -1) lmt = ctlfile_databuf.size();
				if (as_bounces || total != null) {
					//be prepared for some corruption in line endings, in case people edit it to leave white space or CRLF
					int len = lmt - off;
					while (len != 0 && ctlfile_databuf.byteAt(off+len-1) <= ' ') len--;
					status = (int)ctlfile_databuf.parseDecimal(off, len);
				}
				off = lmt + 1;

				if (total != null) {
					if (filter_sender != null) {
						if (addr_from.indexOf(filter_sender) == -1) continue;
					}
					if (filter_recip != null) {
						ByteChars bc = bcwell.extract();
						bc.populate(mbx_to);
						if (domain_to != null) bc.append(EmailAddress.DLM_DOM).append(domain_to);
						int pos = bc.indexOf(filter_recip);
						bcwell.store(bc);
						if (pos == -1) continue;
					}
					if (displaybuf != null) {
						dtcal.setTimeInMillis(recvtime);
						displaybuf.append("<qrow MessageID=\"").append(externalSPID(spid));
						displaybuf.append("\" QID=\"").append(qid);
						displaybuf.append("\" RecvTime=\"");
						TimeOps.makeTimeLogger(dtcal, displaybuf, true, false);
						displaybuf.append("\" Sender=\"").append(addr_from == null ? "" : addr_from);
						displaybuf.append("\" Recip=\"").append(mbx_to);
						if (domain_to != null) displaybuf.append(EmailAddress.DLM_DOM).append(domain_to);

						displaybuf.append("\" Status=\"");
						if (nextsend == -1) {
							displaybuf.append("NEW");
						} else {
							String txt = "ERR";
							if (nextsend == 0) txt = "BOUNCE"; //will become bounce report, once Reports task picks it up
							displaybuf.append(txt).append(" - ").append(retrycnt).append('/').append(status);
						}

						displaybuf.append("\" NextSend=\"");
						if (nextsend > 0) {
							dtcal.setTimeInMillis(nextsend);
							TimeOps.makeTimeLogger(dtcal, displaybuf, false, false);
						}
						displaybuf.append("\"/>");
					}
					total.val++;
					if (maxcapacity != 0 && total.val >= maxcapacity) break;
					continue;
				}
				msgcache.addEntry(qid, spid, recvtime, iprecv, addr_from, domain_to, mbx_to, retrycnt, status);
			}
			if (msgcache != null) batch_clusters.add(cluster);
		} catch (Throwable ex) {
			// Discard all recips from this control file and continue. That means this file will remain here until our next load
			// and we may well fail to parse it every single time, so log at level=warning to alert admins that they may want to
			// remove this file.
			// The whole purpose of our verifyAddress() method is to help ensure this situation never arises.
			dsptch.getLogger().log(LEVEL.WARN, ex, true, loglbl+"Failed to load Incoming cluster="+cluster);
			if (msgcache != null) msgcache.truncate(prevcachesize);
		}
		return true;
	}

	private boolean loadCluster(java.nio.file.Path dirh, String filename, com.grey.mailismus.mta.queue.Cache msgcache, boolean as_bounces)
		throws java.io.IOException
	{
		return loadCluster(dirh, filename, msgcache.capacity(), msgcache, as_bounces, null, null, null, null, null, null, null);
	}

	private boolean loadCluster(java.nio.file.Path fpath, com.grey.base.utils.IntValue total, int max, StringBuilder outbuf,
			CharSequence sender, CharSequence recip, java.util.Calendar dtcal)
		throws java.io.IOException
	{
		return loadCluster(FileOps.parentDirectory(fpath), FileOps.getFilename(fpath), max, null, false,
								total, outbuf, sender, recip, dtcal, null, null);
	}

	private boolean loadCluster(java.nio.file.Path fpath, com.grey.base.collections.HashedMapIntInt spids_refcnt, int max)
		throws java.io.IOException
	{
		return loadCluster(FileOps.parentDirectory(fpath), FileOps.getFilename(fpath), max, null, false,
								null, null, null, null, null, null, spids_refcnt);
	}

	private boolean loadCluster(java.nio.file.Path fpath, com.grey.base.collections.HashedSetInt orphan_candidates)
		throws java.io.IOException
	{
		return loadCluster(FileOps.parentDirectory(fpath), FileOps.getFilename(fpath), 0, null, false,
								null, null, null, null, null, orphan_candidates, null);
	}

	@Override
	public int qsize(CharSequence sender, CharSequence recip, int flags) throws java.io.IOException
	{
		return show(sender, recip, 0, flags, null);
	}

	@Override
	public int show(CharSequence sender, CharSequence recip, int maxmessages, int flags, StringBuilder outbuf)
		throws java.io.IOException
	{
		if (sender != null && sender.length() == 0) sender = null;
		if (recip != null && recip.length() == 0) recip = null;
		java.util.ArrayList<java.nio.file.Path> lst = listClusters(flags);
		com.grey.base.utils.IntValue total = new com.grey.base.utils.IntValue();
		java.util.Calendar dtcal = null;

		if (outbuf != null) {
			dtcal = TimeOps.getCalendar(null);
			outbuf.append("<qrows>");
		}
		for (int idx = 0; idx != lst.size(); idx++) {
			if (maxmessages != 0 && total.val >= maxmessages) break;
			loadCluster(lst.get(idx), total, maxmessages, outbuf, sender, recip, dtcal);
		}
		if (outbuf != null) {
			outbuf.append("</qrows>");
			outbuf.append("<summary total=\"").append(total.val).append("\"/>");
		}
		return total.val;
	}

	private boolean loadSPIDs(com.grey.base.collections.HashedMapIntInt refcnt, int max, java.nio.file.Path dh) throws java.io.IOException
	{
		try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dh)) {
			for (java.nio.file.Path fpath : ds) {
				if (max != 0 && refcnt.size() >= max) return true;
				if (java.nio.file.Files.isDirectory(fpath, FileOps.LINKOPTS_NONE)) {
					if (loadSPIDs(refcnt, max, fpath)) return true;
				} else {
					loadCluster(fpath, refcnt, max);
				}
			}
		} catch (java.nio.file.NoSuchFileException ex) { //ok
		} catch (java.io.IOException ex) {
			if (java.nio.file.Files.exists(dh, FileOps.LINKOPTS_NONE)) throw ex;
		}
		return false;
	}

	private boolean determineOrphans(com.grey.base.collections.HashedSetInt orphan_candidates, java.nio.file.Path dh) throws java.io.IOException
	{
		try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dh)) {
			for (java.nio.file.Path fpath : ds) {
				if (orphan_candidates.isEmpty()) return true;
				if (java.nio.file.Files.isDirectory(fpath, FileOps.LINKOPTS_NONE)) {
					if (determineOrphans(orphan_candidates, fpath)) return true;
				} else {
					loadCluster(fpath, orphan_candidates);
				}
			}
		} catch (java.nio.file.NoSuchFileException ex) { //ok
		} catch (java.io.IOException ex) {
			if (java.nio.file.Files.exists(dh, FileOps.LINKOPTS_NONE)) throw ex;
		}
		return false;
	}

	// We want to sort with oldest messages first, so that means putting the retries at the start, as by definition
	// they're older than the new ones.
	// Bounces could be any age (though certainly older than new messages too), but they only exist fleetingly before the
	// Forwarder removes them and replaces them with new NDR messages, so shove them to the end as a special case.
	// Note that REVSORT only has a rough effect because messages are still sorted by ascending time within each cluster
	// file, but it allows us to peek at the young end of a very large queue without having to scan it all.
	private java.util.ArrayList<java.nio.file.Path> listClusters(int flags) throws java.io.IOException
	{
		java.util.ArrayList<java.nio.file.Path> lst = new java.util.ArrayList<java.nio.file.Path>();

		if (flags == 0 || (flags & SHOWFLAG_TEMPERR) != 0) {
			java.util.ArrayList<java.nio.file.Path> partlst = FileOps.directoryList(deferredRoot, true);
			if (partlst != null) {
				FileOps.sortByFilename(partlst);
				lst.addAll(partlst);
			}
		}
		if (flags == 0 || (flags & SHOWFLAG_NEW) != 0) {
			java.util.ArrayList<java.nio.file.Path> partlst = FileOps.directoryList(incomingDir, true);
			if (partlst != null) {
				FileOps.sortByFilename(partlst);
				lst.addAll(partlst);
			}
		}
		if ((flags & SHOWFLAG_REVSORT) != 0) java.util.Collections.reverse(lst);

		if (flags == 0 || (flags & SHOWFLAG_BOUNCES) != 0) {
			java.util.ArrayList<java.nio.file.Path> partlst = FileOps.directoryList(bounceDir, true);
			if (partlst != null) lst.addAll(partlst);
		}

		for (int idx = lst.size() - 1; idx >= 0; idx--) {
			if (!lst.get(idx).toString().endsWith(MessageCluster.SUFFIX)) lst.remove(idx);
		}
		return lst;
	}

	private void appendMessage(ByteChars databuf, int spid, int qid, ByteChars sender, ByteChars domain_to, ByteChars mbx_to,
			long recvtime, int iprecv, int retrycnt, int status)
	{
		databuf.append(externalSPID(spid)).append(DLM_FLDS).append(qid & ByteOps.INTMASK, tmpsb);
		databuf.append(DLM_FLDS).append(sender).append(DLM_FLDS).append(mbx_to).append(DLM_FLDS).append(domain_to);
		databuf.append(DLM_FLDS).append(recvtime, tmpsb).append(DLM_FLDS).append(iprecv, tmpsb);
		databuf.append(DLM_FLDS).append(retrycnt, tmpsb).append(DLM_FLDS).append(status, tmpsb);
		databuf.append(DLM_ROWS);
	}

	// Filter out illegal chars that would break our parsing of control files.
	// Ignore all other considerations about what constitutes a legal email address, as that's dealt with elsewhere.
	@Override
	public boolean verifyAddress(ByteChars addr)
	{
		byte[] buf = addr.buffer();
		int off = addr.offset();
		int lmt = off + addr.size();

		while (off != lmt) {
			byte b = buf[off++];
			if (b == DLM_FLDS || b == DLM_ROWS) return false;
			if (b == ':' && SysProps.isWindows) return false;
		}
		return true;
	}
}

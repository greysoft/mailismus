/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.IntValue;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.FileOps;
import com.grey.base.collections.HashedMap;
import com.grey.base.collections.HashedMapIntInt;
import com.grey.base.collections.HashedSetInt;
import com.grey.base.collections.IteratorInt;
import com.grey.base.collections.ObjectWell;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.mailismus.errors.MailismusConfigException;

public final class Spooler
{
	public interface SPID_Counter
	{
		void loadSPIDs(HashedMapIntInt refcnt, int max) throws java.io.IOException;
	}

	private static final String EXT_SPOOL = ".msg";
	private static final String EXT_NDRDIAG = ".ndr";
	private static final String DIR_NDRDIAG = "ndrdiag";
	private static final char DLM_SPID = '-';

	private final java.nio.file.Path dhroot;
	private final java.nio.file.Path[] fhsilo; //silo directories - indexed by mapSilo(spid)
	private final java.nio.file.Path dh_ndrdiag;
	private final int spidmask;
	private final int bufsiz_submit;
	private final ObjectWell<SubmitHandle> poolSubmitHandles;
	private final com.grey.logging.Logger logger;
	private final String loglbl;
	private final boolean isHardLinked;

	private final HashedMapIntInt spids_refcnt;
	private final SPID_Allocator spidgen;
	private final int max_spidrefs;

	//temp work areas, pre-allocated for efficiency
	private final StringBuilder tmpsb = new StringBuilder();
	// these are needed by externalSPID()
	private static final char[] hexdigits = "0123456789ABCDEF".toCharArray();
	private static final int EXTSPIDSIZE = IntValue.SIZE_INT; //external SPIDs are padded to this length
	private final StringBuilder spidbuilder = new StringBuilder();

	public static boolean isMultiSPID(int spid) {return ((spid & 0x1) == 1);}
	public boolean isHardLinked() {return isHardLinked;}
	private int mapSilo(int spid) {return spid & spidmask;}
	boolean cancel(SubmitHandle sph) {return cancel(sph, sph.recips.size());}
	void releaseHandle(SubmitHandle sph) {poolSubmitHandles.store(sph.release());}

	Spooler(ApplicationContextNAF appctx, XmlConfig cfg, com.grey.logging.Logger l, String name) throws java.io.IOException
	{
		logger = l;
		loglbl = "Spooler-"+name+": ";
		poolSubmitHandles = new ObjectWell<SubmitHandle>(SubmitHandle.class, "Spooler-"+name);
		spidbuilder.setLength(EXTSPIDSIZE);

		NAFConfig nafcfg = appctx.getConfig();
		String rootpath = nafcfg.getPath(cfg, "rootpath", null, false, nafcfg.getPathVar()+"/spool", null);
		int loadfactor = cfg.getInt("silofactor", false, 5);
		bufsiz_submit = (int)cfg.getSize("bufsize", "16K");
		isHardLinked = cfg.getBool("hardlinks", false);
		max_spidrefs = (isHardLinked ? -1 : cfg.getInt("maxspidcache", false, 250_000));

		// create top-level directory of spool area
		spidmask = (int)Math.pow(2, loadfactor) - 1;
		dhroot = java.nio.file.Paths.get(rootpath).toAbsolutePath();
		dh_ndrdiag = dhroot.resolve(DIR_NDRDIAG);
		fhsilo = createSiloDefs();
		FileOps.ensureDirExists(dhroot);

		if (isHardLinked) {
			verifySupportsHardLinks(dhroot);
		}

		if (max_spidrefs == -1) {
			spids_refcnt = null;
		} else {
			ConcurrentHashMap<java.nio.file.Path,HashedMapIntInt> refcounts = appctx.getNamedItem(getClass().getName()+"-refcnt", () -> new ConcurrentHashMap<>());
			HashedMapIntInt map = refcounts.get(dhroot);
			if (map == null) {
				map = new HashedMapIntInt(0, 10f);
				HashedMapIntInt map2 = refcounts.putIfAbsent(dhroot, map);
				if (map2 != null) map = map2;
			}
			spids_refcnt = map;
		}

		// Don't bother with ConcurrentHashMap, as we want to lock the SPID_Allocator constructor as well, to make sure only
		// one thread undertakes this expensive task.
		HashedMap<java.nio.file.Path,SPID_Allocator> SPID_allocators = appctx.getNamedItem(getClass().getName()+"-spidalloc", () -> new HashedMap<>());
		synchronized (SPID_allocators) {
			SPID_Allocator allocator = SPID_allocators.get(dhroot);
			if (allocator == null) {
				spidgen = new SPID_Allocator(dhroot);
				SPID_allocators.put(dhroot, spidgen);
			} else {
				spidgen = allocator;
			}
		}

		if (logger != null) {
			logger.info(loglbl+"Root="+dhroot);
			logger.trace(loglbl+"bufsiz="+bufsiz_submit+", hardlinks="+isHardLinked+", maxspidcache="+max_spidrefs
					+", silofactor="+loadfactor+"/"+fhsilo.length);
		}
	}

	void init(SPID_Counter loader) throws java.io.IOException
	{
		if (spids_refcnt == null) return;
		synchronized (spids_refcnt) {
			//non-empty map doesn't mean we're the first to do this load, but it should mean it's cheap to repeat it
			if (!spids_refcnt.isEmpty()) return;
			loader.loadSPIDs(spids_refcnt, max_spidrefs);
			if (logger != null && spids_refcnt.size() != 0) {
				logger.info(loglbl+"Loaded existing SPID refs="+spids_refcnt.size());
			}
		}
	}

	SubmitHandle create(ByteChars sender,
			java.util.ArrayList<EmailAddress> recips,
			java.util.ArrayList<ByteChars> sender_rewrites,
			int iprecv, boolean no_open) throws java.io.IOException
	{
		SubmitHandle sph = poolSubmitHandles.extract();
		sph.spid = spidgen.getSPID(recips.size());
		int filecnt = 0;

		try {
			java.nio.file.Path fh1 = getMessage(sph.spid, 1);
			try {
				sph.create(fh1, bufsiz_submit, no_open);
			} catch (java.io.IOException ex) {
				//assume failure is due to missing silo directory - a 2nd failure is genuine
				FileOps.ensureDirExists(FileOps.parentDirectory(fh1));
				sph.create(fh1, bufsiz_submit, no_open);
			}
			filecnt++;

			if (isHardLinked) {
				for (int qid = 2; qid <= recips.size(); qid++) {
					java.nio.file.Path fh = getMessage(sph.spid, qid);
					java.nio.file.Files.createLink(fh, fh1);
					filecnt++;
				}
			}
		} catch (Throwable ex) {
			cancel(sph, filecnt);
			throw ex;
		}

		if (spids_refcnt != null && recips.size() != 1) {
			initReferenceCount(sph.spid, recips.size());
		}
		sph.sender = sender;
		sph.recips = recips;
		sph.sender_rewrites = sender_rewrites;
		sph.iprecv = iprecv;
		return sph;
	}

	private boolean cancel(SubmitHandle sph, int recipcnt)
	{
		if (spids_refcnt != null) {
			synchronized (spids_refcnt) {
				spids_refcnt.remove(sph.spid);
			}
		}
		boolean success = sph.close(logger);

		if (recipcnt != 0) {
			java.nio.file.Path fh1 = sph.getMessage();
			if (fh1 == null) fh1 = getMessage(sph.spid, 1);
			if (!delete(fh1)) success = false;
		}

		if (isHardLinked) {
			for (int qid = 2; qid <= recipcnt; qid++) {
				if (!delete(sph.spid, qid)) success = false;
			}
		}
		releaseHandle(sph);
		return success;
	}

	// The qid param is only relevant if hard links are in use and technically 0 means any spool file will do while qid=1 means
	// the first recipient, but in practice zero is treated in the same way.
	java.nio.file.Path getMessage(int spid, int qid)
	{
		tmpsb.setLength(0);
		tmpsb.append(externalSPID(spid));
		if (isHardLinked && isMultiSPID(spid)) {
			if (qid == 0) qid = 1;
			tmpsb.append(DLM_SPID).append(qid);
		}
		tmpsb.append(EXT_SPOOL);
		return fhsilo[mapSilo(spid)].resolve(tmpsb.toString());
	}

	java.nio.file.Path getDiagnosticFile(int spid, int qid)
	{
		tmpsb.setLength(0);
		tmpsb.append(externalSPID(spid)).append(DLM_SPID).append(qid).append(EXT_NDRDIAG);
		return dh_ndrdiag.resolve(tmpsb.toString());
	}

	java.nio.file.Path export(int spid, int qid, CharSequence dstpath) throws java.io.IOException
	{
		// set everything up ...
		tmpsb.setLength(0);
		tmpsb.append(dstpath).append('/').append(externalSPID(spid)).append(EXT_SPOOL);
		java.nio.file.Path fhdst = java.nio.file.Paths.get(tmpsb.toString());
		java.nio.file.Path fhsrc = getMessage(spid, qid);

		// ... and here come the moving parts
		try {
			FileOps.copyFile(fhsrc, fhdst);
		} catch (Exception ex) {
			// assume failure is due to missing directory - a 2nd failure is genuine
			FileOps.ensureDirExists(FileOps.parentDirectory(fhdst));
			FileOps.copyFile(fhsrc, fhdst);
		}
		return fhdst;
	}

	// this only gets called when hard links aren't in use
	void deleteOrphans(HashedSetInt spids)
	{
		if (spids.size() == 0) return;
		IteratorInt iter = spids.recycledIterator();
		while (iter.hasNext()) delete(iter.next(), 0);
	}

	boolean delete(int spid, int qid)
	{
		java.nio.file.Path fh = getMessage(spid, qid);
		return delete(fh);
	}

	private boolean delete(java.nio.file.Path fh)
	{
		Exception ex = FileOps.deleteFile(fh);
		if (ex != null && logger != null) {
			logger.info(loglbl+"Failed to delete spool-file="+fh+" - "+ex);
			return false;
		}
		return true;
	}

	// This method converts a SPID to the hex representation used externally.
	// Note that the returned buffer is updated on every invocation so callers need to latch the returned instance,
	// and make their own MT-safe arrangements (but anyway, Mailismus is based on single-threaded Naflets, each having
	// its own instance of this class).
	// They must also treat the returned StringBuilder as read-only - in fact they only see it as a generic CharSequence
	// anyway.
	CharSequence externalSPID(int spidval)
	{
		for (int idx = EXTSPIDSIZE - 1; idx != -1; idx--) {
			spidbuilder.setCharAt(idx, hexdigits[(byte)(spidval & 0xF)]);
			spidval >>>= 4;
		}
		return spidbuilder;
	}

	private void initReferenceCount(int spid, int cnt)
	{
		synchronized (spids_refcnt) {
			if (max_spidrefs != 0 && spids_refcnt.size() == max_spidrefs) {
				//we could just discard the new entry, but we want to be biased towards new messages, so discard random existing one
				int delspid = spids_refcnt.recycledKeysIterator().next();
				spids_refcnt.remove(delspid);
			}
			spids_refcnt.put(spid, cnt);
		}
	}

	// On entry, deliv_refcnt contains the delivery reference count for a set of SPIDs, and on return, only orphan candidates
	// remain. SPIDs which are definitely known to be or not be an orphan will have been stripped, and the former will also
	// have been added to the 'completed' set.
	int updateReferenceCounts(HashedMapIntInt deliv_refcnt, HashedSetInt completed)
	{
		if (spids_refcnt == null) return 0;
		synchronized (spids_refcnt) {
			IteratorInt it = deliv_refcnt.recycledKeysIterator();
			while (it.hasNext()) {
				int spid = it.next();
				int totalcnt = spids_refcnt.get(spid);
				if (totalcnt == 0) {
					//unknown SPID ref, so leave as possible orphan candidate
				} else {
					int batchcnt = deliv_refcnt.get(spid);
					totalcnt -= batchcnt;
					if (totalcnt == 0) {
						//this is now known to be an orphan, so cross off the list of mere candidates
						spids_refcnt.remove(spid);
						completed.add(spid);
					} else {
						if (totalcnt < 0) {
							//something's gone wrong, so remove from global refs but leave as orphan candidate
							spids_refcnt.remove(spid);
							if (logger != null) logger.error(loglbl+"SPID="+externalSPID(spid)+" has excess delivcnt="+batchcnt+" vs refcnt="+(totalcnt+batchcnt));
							continue;
						}
						//not an orphan candidate, as known refs remain
						spids_refcnt.put(spid, totalcnt);
					}
					it.remove();
				}
			}
			return spids_refcnt.size();
		}
	}

	int housekeep(long oldest_timestamp) throws java.io.IOException
	{
		String[] filetypes = new String[]{EXT_SPOOL, EXT_NDRDIAG};
		FileOps.Filter_EndsWith filter = new FileOps.Filter_EndsWith(filetypes, false, false);
		int cnt = FileOps.deleteOlderThan(dhroot.toFile(), oldest_timestamp, filter, true);
		if (cnt != 0 && logger != null) logger.trace(loglbl+"Housekeep deletes="+cnt);
		return cnt;
	}

	private java.nio.file.Path[] createSiloDefs()
	{
		java.nio.file.Path[] silo = new java.nio.file.Path[spidmask + 1];
		int namelen = Integer.toString(silo.length).length();
		for (int idx = 0; idx != silo.length; idx++) {
			String name = "s"+String.format("%0"+namelen+"d", Integer.valueOf(idx+1));
			silo[idx] = dhroot.resolve(name);
		}
		return silo;
	}

	private static void verifySupportsHardLinks(java.nio.file.Path dh) throws java.io.IOException
	{
		java.nio.file.Path path1 = dh.resolve("dummylinksrc");
		java.nio.file.Path path2 = dh.resolve("dummylink");
		Exception ex_del = FileOps.deleteFile(path1);
		if (ex_del == null) ex_del = FileOps.deleteFile(path2);
		if (ex_del != null) throw new java.io.IOException("Unable to delete files under Spool root="+dh, ex_del);
		FileOps.writeTextFile(path1.toFile(), "blah blah", false);
		try {
			java.nio.file.Path pthlink2 = java.nio.file.Files.createLink(path2, path1);
			if (!pthlink2.equals(path2)) throw new MailismusConfigException("Path mismatch - "+pthlink2+" vs "+path2);
			if (java.nio.file.Files.size(path2) != java.nio.file.Files.size(path1)) throw new MailismusConfigException("Size mismatch - "+pthlink2+" vs "+path2);
		} finally {
			java.nio.file.Files.delete(path1);
			java.nio.file.Files.delete(path2);
		}
	}


	private static final class SPID_Allocator
	{
		//SPID to assign to next multi-recip message
		private AtomicInteger nextspid_multi = new AtomicInteger();
		//SPID to assign to next single-recip message
		private AtomicInteger nextspid_solo = new AtomicInteger();

		public SPID_Allocator(java.nio.file.Path dhroot) throws java.io.IOException {
			long nextspid = System.currentTimeMillis();
			java.util.ArrayList<java.nio.file.Path> spooldirs = FileOps.directoryList(dhroot, false);
			for (int idx = 0; idx != spooldirs.size(); idx++) {
				java.nio.file.Path dhsilo = spooldirs.get(idx);
				try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dhsilo)) {
					for (java.nio.file.Path fpath : ds) {
						String filename = FileOps.getFilename(fpath);
						int pos = filename.indexOf(DLM_SPID);
						if (pos == -1) pos = filename.indexOf(EXT_SPOOL);
						long spid = StringOps.parseNumber(filename, 0, pos, 16);
						if (spid >= nextspid) nextspid = spid + 1;
					}
				}
			}
			if (nextspid == 0) nextspid++;
			int intspid = (int)nextspid;
			if (isMultiSPID(intspid)) intspid++;
			nextspid_solo.set(intspid);
			nextspid_multi.set(intspid+1);
		}

		public int getSPID(int recipcnt) {
			AtomicInteger nextspid = (recipcnt == 1 ? nextspid_solo : nextspid_multi);
			int spid = nextspid.addAndGet(2);
			if (spid == 0) spid = nextspid.addAndGet(2);
			return spid;
		}
	}
}
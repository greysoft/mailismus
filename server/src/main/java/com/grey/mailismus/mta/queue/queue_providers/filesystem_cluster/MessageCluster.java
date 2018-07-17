/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem_cluster;

import java.nio.file.StandardOpenOption;

import com.grey.base.config.SysProps;
import com.grey.base.utils.FileOps;

class MessageCluster
{
	public static final String SUFFIX = ".mqc";
	public static final char PFX_CLUSTER = 'M';
	public static final char PFX_DEFERDIR = 'T';
	public static final String PFX_OFFSET = "-p";

	// Have measured DSYNC as more than 60 times slower (on Windows 10) so disable by default.
	// Basically we don't ever want to use this, because it probably means a sync of the entire filesystem, which is
	// something that would really be handled at the sysadmin policy level.
	private static final boolean ENABLE_SYNC = SysProps.get("grey.mtaq.sync_mqc", false);

	public final String filename;
	public final java.nio.file.Path pthnam;
	public final int capacity; //max size in bytes - zero means no limit. Limit can be exceeded by a large initial write
	private final boolean rdonly;
	private final com.grey.logging.Logger logger;

	private java.io.OutputStream ostrm;
	private boolean sealed;
	private int size;

	// this is the constructor for cluster creators
	public MessageCluster(java.nio.file.Path parent, String filename, int cap, com.grey.logging.Logger l)
	{
		this.filename = filename;
		pthnam = parent.resolve(filename);
		logger = l;
		capacity = cap;
		rdonly = false;
	}

	// this is the constructor for cluster readers
	public MessageCluster(java.nio.file.Path pthnam, com.grey.logging.Logger l)
	{
		this.pthnam = pthnam;
		filename = FileOps.getFilename(pthnam);
		logger = l;
		capacity = 0;
		rdonly = true;
	}

	public synchronized void close()
	{
		try {
			if (ostrm != null) ostrm.close();
		} catch (Exception ex) {
			logger.trace("Failed to close cluster="+this+" - "+ex);
		}
		sealed = true;
		ostrm = null;
	}

	public synchronized java.io.InputStream load() throws java.io.IOException
	{
		if (ostrm != null) close();
		try {
			return java.nio.file.Files.newInputStream(pthnam, FileOps.OPENOPTS_NONE);
		} catch (java.io.IOException ex) {
			if (!java.nio.file.Files.exists(pthnam, FileOps.LINKOPTS_NONE)) return null;
			throw ex;
		}
	}

	public synchronized boolean write(com.grey.base.utils.ByteChars data, boolean with_sync) throws java.io.IOException
	{
		if (rdonly) throw new UnsupportedOperationException("Illegal write on read-only cluster - "+this);
		if (sealed) return false;
		int newsize = size + data.size();
		if (capacity != 0 && size != 0 && newsize > capacity) {
			close();
			return false;
		}

		// We don't expect any contention on file creation, but enforce it antway with CREATE_NEW
		if (ostrm == null) {
			StandardOpenOption[] opts = FileOps.OPENOPTS_CREATNEW;
			if (ENABLE_SYNC && with_sync) opts = FileOps.OPENOPTS_CREATNEW_DSYNC;
			try {
				//default options are CREATE,TRUNCATE_EXISTING
				ostrm = java.nio.file.Files.newOutputStream(pthnam, opts);
			} catch (java.io.IOException ex) {
				//assume failure is due to missing directory - a 2nd failure is genuine
				FileOps.ensureDirExists(FileOps.parentDirectory(pthnam));
				ostrm = java.nio.file.Files.newOutputStream(pthnam, opts);
			}
		}
		ostrm.write(data.buffer(), data.offset(), data.size());
		size = newsize;
		return true;
	}

	@Override
	public synchronized String toString() {
		String status = (sealed ? "sealed" : (ostrm == null ? "closed" : "open"));
		return "MessageCluster="+System.identityHashCode(this)+"/"+status+"/size="+size+" - "+pthnam;
	}
}

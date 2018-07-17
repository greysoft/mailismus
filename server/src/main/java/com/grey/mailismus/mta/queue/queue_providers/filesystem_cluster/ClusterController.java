/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem_cluster;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.grey.base.utils.IntValue;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.FileOps;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.reactor.TimerNAF;

class ClusterController
{
	private final ConcurrentHashMap<java.nio.file.Path, MessageCluster> clusters = new ConcurrentHashMap<>();
	private final AtomicInteger create_cnt = new AtomicInteger();
	private final TimerNAF.TimeProvider timesrc;
	private final com.grey.logging.Logger logger;

	public static ClusterController getController(ApplicationContextNAF appctx, java.nio.file.Path qroot, TimerNAF.TimeProvider t,
			com.grey.logging.Logger l)
	{
		ConcurrentHashMap<java.nio.file.Path, ClusterController> controllers = appctx.getNamedItem(ClusterController.class.getName()+"-cctl", (c) -> new ConcurrentHashMap<>());
		ClusterController ctl = controllers.get(qroot);
		if (ctl == null) {
			ctl = new ClusterController(t, l);
			ClusterController ctl2 = controllers.putIfAbsent(qroot, ctl);
			if (ctl2 != null) ctl = ctl2;
		}
		return ctl;
	}

	private ClusterController(TimerNAF.TimeProvider t, com.grey.logging.Logger l) {
		timesrc = t;
		logger = l;
	}

	public MessageCluster createCluster(java.nio.file.Path parent, int cap)
	{
		return createCluster(parent, cap, 0, false);
	}

	//we create a unique filename, so no need for putIfAbsent()
	private MessageCluster createCluster(java.nio.file.Path parent, int cap, long timestamp, boolean nocache)
	{
		String filename = constructFilename(timestamp);
		MessageCluster cluster = new MessageCluster(parent, filename, cap, logger);
		if (!nocache) clusters.put(cluster.pthnam, cluster);
		return cluster;
	}

	public MessageCluster createCluster(java.nio.file.Path parent, ByteChars data, long timestamp, boolean nocache)
		throws java.io.IOException
	{
		boolean ok = false;
		MessageCluster cluster = createCluster(parent, 0, timestamp, nocache);
		try {
			//this write is guaranteed not to return a new cluster (initial write, no size limit)
			writeCluster(cluster, data, false);
			ok = true;
		} finally {
			cluster.close();
			if (!ok) clusters.remove(cluster.pthnam);
		}
		return cluster;
	}

	public void deleteCluster(MessageCluster cluster)
	{
		cluster.close();
		clusters.remove(cluster.pthnam);
		Exception ex = FileOps.deleteFile(cluster.pthnam);
		if (ex != null) logger.warn("Failed to delete cluster="+cluster+" - "+ex);
	}

	// This is called by readers. It's not currently possible for 2 readers to clash on this, but the putIfAbsent()
	// avoids FindBugs warnings.
	public MessageCluster getCluster(java.nio.file.Path pthnam)
	{
		MessageCluster cluster = clusters.get(pthnam);
		if (cluster == null) {
			cluster = new MessageCluster(pthnam, logger);
			MessageCluster c2 = clusters.putIfAbsent(cluster.pthnam, cluster);
			if (c2 != null) cluster = c2;
		}
		return cluster;
	}

	public MessageCluster writeCluster(MessageCluster cluster, ByteChars data) throws java.io.IOException
	{
		return writeCluster(cluster, data, true);
	}

	private MessageCluster writeCluster(MessageCluster cluster, ByteChars data, boolean with_sync) throws java.io.IOException
	{
		if (!cluster.write(data, with_sync)) {
			cluster = createCluster(FileOps.parentDirectory(cluster.pthnam), cluster.capacity);
			if (!cluster.write(data, with_sync)) {
				//Should never happen on a new cluster - must be some sort of of config issue.
				//The cluster file isn't created till we write to it, so there's no way another thread could have sealed it.
				throw new java.io.IOException("Failed to write data="+data.size()+" to new cluster="+cluster);
			}
		}
		return cluster;
	}

	// This is called by a reader which has just loaded a cluster that now exists in the clusters map, and partially processed
	// it. No other reader could have done the same in the meantime, so no need for putIfAbsent() when updating filename.
	public MessageCluster setPartialOffset(MessageCluster cluster, int offset) throws java.io.IOException
	{
		if (offset == getPartialOffset(cluster)) return cluster;
		int pos = cluster.filename.lastIndexOf(MessageCluster.PFX_OFFSET);
		if (pos == -1) pos = cluster.filename.length() - MessageCluster.SUFFIX.length();
		StringBuilder sb = new StringBuilder();
		sb.setLength(0);
		sb.append(cluster.filename);
		sb.setLength(pos);
		sb.append(MessageCluster.PFX_OFFSET).append(offset).append(MessageCluster.SUFFIX);
		String new_filename = sb.toString();
		java.nio.file.Path dh = FileOps.parentDirectory(cluster.pthnam);
		MessageCluster new_cluster = new MessageCluster(dh, new_filename, cluster.capacity, logger);
		clusters.put(new_cluster.pthnam, new_cluster);
		java.nio.file.Files.move(cluster.pthnam, new_cluster.pthnam, FileOps.COPYOPTS_ATOMIC);
		clusters.remove(cluster.pthnam);
		return new_cluster;
	}

	public int getPartialOffset(MessageCluster cluster)
	{
		int pos1 = cluster.filename.lastIndexOf(MessageCluster.PFX_OFFSET);
		if (pos1 == -1) return 0;
		pos1 += MessageCluster.PFX_OFFSET.length();
		int pos2 = cluster.filename.indexOf('.', pos1);
		return (int)StringOps.parseNumber(cluster.filename, pos1, pos2 - pos1, 10);
	}

	// The creation-counter is merely to avoid naming collisions and its impact on sort order is not important. We wrap
	// it on Short.MAX_VALUE, so we're assuming no more than 65,535 clusters created per millisecond!
	private String constructFilename(long timestamp)
	{
		if (timestamp == 0) timestamp = timesrc.getSystemTime();
		int cnt = create_cnt.incrementAndGet() & ByteOps.SHORTMASK;
		StringBuilder sb = new StringBuilder();
		sb.append(MessageCluster.PFX_CLUSTER);
		IntValue.encodeHexLeading(timestamp, true, sb);
		sb.append('-').append(cnt);
		sb.append(MessageCluster.SUFFIX);
		return sb.toString();
	}
}

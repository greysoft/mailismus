/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem_cluster;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.IntValue;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.FileOps;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.reactor.TimerNAF;
import com.grey.mailismus.TestSupport;

public class ClusterControllerTest
	implements TimerNAF.TimeProvider
{
	protected static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("cctltest");
	private final String rootpath = TestSupport.initPaths(getClass());
	private final ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, true);
	private final ClusterController cctl;
	private final ConcurrentHashMap<?,?> cmap;
	private final Clock clock = Clock.systemUTC();

	@Override
	public long getRealTime() {return clock.millis();}
	@Override
	public long getSystemTime() {return getRealTime();}

	public ClusterControllerTest() {
		cctl = ClusterController.getController(appctx, java.nio.file.Paths.get(rootpath), this, logger);
		cmap = (ConcurrentHashMap<?,?>)DynLoader.getField(cctl, "clusters");
	}

	@org.junit.Test
	public void testSimpleLifeCycle() throws java.io.IOException
	{
		int cap = 20;
		java.nio.file.Path croot = java.nio.file.Paths.get(rootpath, "clusterdir");
		ByteChars databuf = new ByteChars(cap*2);

		MessageCluster c1 = cctl.createCluster(croot, cap);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertFalse(java.nio.file.Files.exists(c1.pthnam));
		org.junit.Assert.assertEquals(croot, c1.pthnam.getParent());
		org.junit.Assert.assertNotNull(c1.filename);
		org.junit.Assert.assertEquals(MessageCluster.PFX_CLUSTER, c1.filename.charAt(0));
		org.junit.Assert.assertTrue(c1.filename.endsWith(MessageCluster.SUFFIX));
		org.junit.Assert.assertEquals(cap, c1.capacity);
		org.junit.Assert.assertNull(DynLoader.getField(c1, "ostrm"));
		databuf.setSize(databuf.buffer().length);
		MessageCluster c = cctl.writeCluster(c1, databuf);
		org.junit.Assert.assertSame(c1, c); //initial write is allowed to exceed the capacity
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c1.pthnam));
		org.junit.Assert.assertEquals(cap*2, java.nio.file.Files.size(c1.pthnam));
		org.junit.Assert.assertEquals(cap*2, databuf.size());
		org.junit.Assert.assertNotNull(DynLoader.getField(c1, "ostrm"));
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c1));

		databuf.setSize(1);
		MessageCluster c2 = cctl.writeCluster(c1, databuf);
		org.junit.Assert.assertNotSame(c1, c2); //an already excessive capacity has now trigerred a switch
		org.junit.Assert.assertEquals(2, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c1.pthnam));
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c2.pthnam));
		org.junit.Assert.assertEquals(c1.pthnam.getParent(), c2.pthnam.getParent());
		org.junit.Assert.assertNotEquals(c1.pthnam, c2.pthnam);
		org.junit.Assert.assertNotEquals(c1.filename, c2.filename);
		org.junit.Assert.assertEquals(MessageCluster.PFX_CLUSTER, c2.filename.charAt(0));
		org.junit.Assert.assertTrue(c2.filename.endsWith(MessageCluster.SUFFIX));
		org.junit.Assert.assertEquals(c1.capacity, c2.capacity);
		org.junit.Assert.assertNull(DynLoader.getField(c1, "ostrm"));
		org.junit.Assert.assertNotNull(DynLoader.getField(c2, "ostrm"));
		databuf.setSize(cap - databuf.size());
		c = cctl.writeCluster(c2, databuf);
		org.junit.Assert.assertSame(c2, c); //this cluster is now exactly at capacity
		org.junit.Assert.assertEquals(2, cmap.size());
		org.junit.Assert.assertEquals(cap, java.nio.file.Files.size(c2.pthnam));
		org.junit.Assert.assertNotNull(DynLoader.getField(c2, "ostrm"));
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c2));

		databuf.setSize(1);
		MessageCluster c3 = cctl.writeCluster(c2, databuf);
		org.junit.Assert.assertNotSame(c2, c3); //capacity has been exceeded
		org.junit.Assert.assertEquals(3, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c2.pthnam));
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c3.pthnam));
		org.junit.Assert.assertNull(DynLoader.getField(c2, "ostrm"));
		org.junit.Assert.assertNotNull(DynLoader.getField(c3, "ostrm"));
		c3.close();
		org.junit.Assert.assertNull(DynLoader.getField(c3, "ostrm"));
		c3.close(); //repeated call has no ill effect
		org.junit.Assert.assertNull(DynLoader.getField(c3, "ostrm"));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c3.pthnam));
		org.junit.Assert.assertEquals(1, databuf.size());
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c3));

		MessageCluster[] clusters = new MessageCluster[]{c1, c2, c3};
		int cnt = clusters.length;
		for (int idx = 0; idx != clusters.length; idx++) {
			c = cctl.getCluster(clusters[idx].pthnam);
			org.junit.Assert.assertSame(clusters[idx], c);
			java.io.InputStream strm = c.load();
			org.junit.Assert.assertEquals(java.nio.file.Files.size(c.pthnam), strm.available());
			strm.close();
			org.junit.Assert.assertEquals(cnt, cmap.size());
			cctl.deleteCluster(c);
			org.junit.Assert.assertEquals(--cnt, cmap.size());
			org.junit.Assert.assertFalse(java.nio.file.Files.exists(c.pthnam));
			org.junit.Assert.assertNull(DynLoader.getField(c, "ostrm"));
			org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c));
			//double deletion is safe
			cctl.deleteCluster(c1);
			org.junit.Assert.assertEquals(cnt, cmap.size());
		}
		org.junit.Assert.assertEquals(0, cmap.size());
	}

	@org.junit.Test
	public void testSimultaneousAccess() throws java.io.IOException
	{
		java.nio.file.Path croot = java.nio.file.Paths.get(rootpath, "clusterdir");
		ByteChars databuf = new ByteChars(100);

		MessageCluster c1 = cctl.createCluster(croot, 0);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertFalse(java.nio.file.Files.exists(c1.pthnam));
		databuf.setSize(2);
		MessageCluster c = cctl.writeCluster(c1, databuf);
		org.junit.Assert.assertSame(c1, c);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c1.pthnam));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c1.pthnam));
		org.junit.Assert.assertEquals(2, databuf.size());
		org.junit.Assert.assertNotNull(DynLoader.getField(c1, "ostrm"));

		// reader loads (and hence seals) a cluster that's currently open for writing
		c = cctl.getCluster(c1.pthnam);
		org.junit.Assert.assertSame(c1, c);
		org.junit.Assert.assertNotNull(DynLoader.getField(c1, "ostrm"));
		java.io.InputStream strm = c.load();
		org.junit.Assert.assertNull(DynLoader.getField(c1, "ostrm"));
		org.junit.Assert.assertEquals(java.nio.file.Files.size(c.pthnam), strm.available());
		org.junit.Assert.assertEquals(1, cmap.size());
		// and writer tries to write again to the now-sealed cluster
		databuf.setSize(3);
		MessageCluster c2 = cctl.writeCluster(c1, databuf);
		org.junit.Assert.assertNotSame(c1, c2); //because the load() on c1 sealed it
		org.junit.Assert.assertEquals(2, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c2.pthnam));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c2.pthnam));
		org.junit.Assert.assertNotNull(DynLoader.getField(c2, "ostrm"));
		// delete the sealed cluster
		strm.close();
		org.junit.Assert.assertEquals(2, cmap.size());
		cctl.deleteCluster(c);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertFalse(java.nio.file.Files.exists(c1.pthnam));
		org.junit.Assert.assertNull(DynLoader.getField(c1, "ostrm"));

		// seal and delete the cluster that resulted from writing to the first sealed cluster
		c = cctl.getCluster(c2.pthnam);
		org.junit.Assert.assertSame(c2, c);
		org.junit.Assert.assertNotNull(DynLoader.getField(c2, "ostrm"));
		strm = c.load();
		org.junit.Assert.assertNull(DynLoader.getField(c2, "ostrm"));
		org.junit.Assert.assertEquals(java.nio.file.Files.size(c.pthnam), strm.available());
		strm.close();
		org.junit.Assert.assertEquals(1, cmap.size());
		cctl.deleteCluster(c);
		org.junit.Assert.assertEquals(0, cmap.size());
		org.junit.Assert.assertFalse(java.nio.file.Files.exists(c2.pthnam));
		org.junit.Assert.assertNull(DynLoader.getField(c2, "ostrm"));

		//whether a cluster has been deleted or merely sealed, writes continue to work and trigger a new cluster
		databuf.setSize(4);
		MessageCluster c3 = cctl.writeCluster(c2, databuf);
		org.junit.Assert.assertNotSame(c2, c3);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c3.pthnam));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c3.pthnam));
		org.junit.Assert.assertNotNull(DynLoader.getField(c3, "ostrm"));
		c3.close();
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c3.pthnam));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c3.pthnam));
		org.junit.Assert.assertNull(DynLoader.getField(c3, "ostrm"));

		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c1));
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c2));
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c3));
	}

	@org.junit.Test
	public void testUnmappedGet() throws java.io.IOException
	{
		java.nio.file.Path goodpath = java.nio.file.Paths.get(rootpath, "cluster1");
		java.nio.file.Files.createDirectories(FileOps.parentDirectory(goodpath));
		java.nio.file.Files.createFile(goodpath);
		MessageCluster c = cctl.getCluster(goodpath);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertSame(c, cmap.get(c.pthnam));
		org.junit.Assert.assertEquals(goodpath, c.pthnam);
		org.junit.Assert.assertEquals(goodpath.getParent(), c.pthnam.getParent());
		org.junit.Assert.assertNotNull(c.filename);
		org.junit.Assert.assertEquals(0, c.capacity);
		org.junit.Assert.assertNull(DynLoader.getField(c, "ostrm"));
		java.io.InputStream strm = c.load();
		org.junit.Assert.assertEquals(0, strm.available());
		org.junit.Assert.assertNull(DynLoader.getField(c, "ostrm"));
		strm.close();
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c.pthnam));

		java.nio.file.Path badpath = java.nio.file.Paths.get(rootpath, "nosuchcluster");
		c = cctl.getCluster(badpath);
		org.junit.Assert.assertEquals(2, cmap.size());
		org.junit.Assert.assertSame(c, cmap.get(c.pthnam));
		org.junit.Assert.assertEquals(badpath, c.pthnam);
		org.junit.Assert.assertEquals(badpath.getParent(), c.pthnam.getParent());
		org.junit.Assert.assertNotNull(c.filename);
		org.junit.Assert.assertEquals(0, c.capacity);
		org.junit.Assert.assertNull(DynLoader.getField(c, "ostrm"));
		strm = c.load();
		org.junit.Assert.assertNull(strm);
	}

	@org.junit.Test
	public void testSingleWrite() throws java.io.IOException
	{
		java.nio.file.Path croot = java.nio.file.Paths.get(rootpath, "clusterdir");
		ByteChars databuf = new ByteChars("abc");

		MessageCluster c = cctl.createCluster(croot, databuf, 0, false);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c.pthnam));
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c));
		org.junit.Assert.assertEquals(1, StringOps.count(c.filename, "-"));
		org.junit.Assert.assertTrue(c.filename.endsWith(MessageCluster.SUFFIX));
		org.junit.Assert.assertFalse(c.filename.contains(MessageCluster.PFX_CLUSTER+"0-"));
		org.junit.Assert.assertNull(DynLoader.getField(c, "ostrm"));

		long timestamp = 10;
		String tstr = IntValue.encodeHexLeading(timestamp, true, null).toString();
		c = cctl.createCluster(croot, databuf, timestamp, false);
		org.junit.Assert.assertEquals(2, cmap.size());
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c.pthnam));
		org.junit.Assert.assertEquals(0, cctl.getPartialOffset(c));
		org.junit.Assert.assertEquals(1, StringOps.count(c.filename, "-"));
		org.junit.Assert.assertTrue(c.filename.endsWith(MessageCluster.SUFFIX));
		org.junit.Assert.assertFalse(c.filename.contains(MessageCluster.PFX_CLUSTER+"0-"));
		org.junit.Assert.assertTrue(c.filename.startsWith(MessageCluster.PFX_CLUSTER+tstr+"-"));
		org.junit.Assert.assertNull(DynLoader.getField(c, "ostrm"));
	}

	@org.junit.Test
	public void testPartialCluster() throws java.io.IOException
	{
		java.nio.file.Path croot = java.nio.file.Paths.get(rootpath, "clusterdir");
		ByteChars databuf = new ByteChars("abc");

		MessageCluster c1 = cctl.createCluster(croot, 0);
		org.junit.Assert.assertEquals(1, cmap.size());
		int offset = cctl.getPartialOffset(c1);
		org.junit.Assert.assertEquals(0, offset);
		org.junit.Assert.assertEquals(1, StringOps.count(c1.filename, "-"));
		MessageCluster c = cctl.writeCluster(c1, databuf);
		org.junit.Assert.assertSame(c1, c);
		org.junit.Assert.assertEquals(1, cmap.size());
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c1.pthnam));
		c.close();

		c = cctl.setPartialOffset(c1, 0);
		org.junit.Assert.assertSame(c1, c);
		org.junit.Assert.assertEquals(1, cmap.size());
		offset = cctl.getPartialOffset(c);
		org.junit.Assert.assertEquals(0, offset);
		org.junit.Assert.assertNull(DynLoader.getField(c1, "ostrm"));

		int partial_offset = 10;
		MessageCluster c2 = cctl.setPartialOffset(c1, partial_offset);
		org.junit.Assert.assertNotSame(c1, c2);
		org.junit.Assert.assertEquals(1, cmap.size());
		offset = cctl.getPartialOffset(c2);
		org.junit.Assert.assertEquals(partial_offset, offset);
		org.junit.Assert.assertEquals(2, StringOps.count(c2.filename, "-"));
		org.junit.Assert.assertTrue(c2.filename.endsWith(MessageCluster.PFX_OFFSET+partial_offset+MessageCluster.SUFFIX));
		org.junit.Assert.assertEquals(c1.pthnam.getParent(), c2.pthnam.getParent());
		org.junit.Assert.assertNotEquals(c1.pthnam, c2.pthnam);
		org.junit.Assert.assertNotEquals(c1.filename, c2.filename);
		org.junit.Assert.assertFalse(java.nio.file.Files.exists(c1.pthnam));
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c2.pthnam));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c2.pthnam));
		org.junit.Assert.assertNull(DynLoader.getField(c1, "ostrm"));
		org.junit.Assert.assertNull(DynLoader.getField(c2, "ostrm"));

		partial_offset = 20;
		MessageCluster c3 = cctl.setPartialOffset(c2, partial_offset);
		org.junit.Assert.assertNotSame(c2, c3);
		org.junit.Assert.assertEquals(1, cmap.size());
		offset = cctl.getPartialOffset(c3);
		org.junit.Assert.assertEquals(partial_offset, offset);
		org.junit.Assert.assertEquals(2, StringOps.count(c3.filename, "-"));
		org.junit.Assert.assertTrue(c3.filename.endsWith(MessageCluster.PFX_OFFSET+partial_offset+MessageCluster.SUFFIX));
		org.junit.Assert.assertEquals(c2.pthnam.getParent(), c3.pthnam.getParent());
		org.junit.Assert.assertNotEquals(c2.pthnam, c3.pthnam);
		org.junit.Assert.assertNotEquals(c2.filename, c3.filename);
		org.junit.Assert.assertFalse(java.nio.file.Files.exists(c2.pthnam));
		org.junit.Assert.assertTrue(java.nio.file.Files.exists(c3.pthnam));
		org.junit.Assert.assertEquals(databuf.size(), java.nio.file.Files.size(c3.pthnam));
		org.junit.Assert.assertNull(DynLoader.getField(c2, "ostrm"));
		org.junit.Assert.assertNull(DynLoader.getField(c3, "ostrm"));

		c = cctl.setPartialOffset(c3, partial_offset);
		org.junit.Assert.assertSame(c3, c);
		org.junit.Assert.assertEquals(1, cmap.size());
		offset = cctl.getPartialOffset(c);
		org.junit.Assert.assertEquals(partial_offset, offset);
		org.junit.Assert.assertNull(DynLoader.getField(c3, "ostrm"));
	}
}
/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem_cluster;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.utils.DynLoader;
import com.grey.mailismus.mta.queue.MessageRecip;
import com.grey.mailismus.mta.queue.SubmitHandle;

public class ClusteredQueueTest
	extends com.grey.mailismus.mta.queue.ManagerTest
{
	private static final String cfgxml = "<maxclustersize>5000</maxclustersize>"
			+"<deferred_maxignore>2s</deferred_maxignore>";

	@Override
	protected Class<?> getQueueClass() {return ClusteredQueue.class;}
	@Override
	protected String getQueueConfig() {return cfgxml;}

	@org.junit.Test
	public void testClusterFormat() throws Exception
	{
		ClusteredQueue cq = (ClusteredQueue)qmgr;
		SubmitHandle sph = new SubmitHandle();
		// create new-message MQC with 2 records (2 recips for same message)
		long recvtime = qmgr.dsptch.getSystemTime();
		sph.spid = 170;
		sph.iprecv = IP.convertDottedIP("11.12.13.14");
		sph.sender = new ByteChars("senderA@domainB");
		sph.recips = new java.util.ArrayList<EmailAddress>();
		sph.recips.add(new EmailAddress("recip1@domain2"));
		sph.recips.add(new EmailAddress("recip11@domain12"));
		boolean ok = cq.storeMessage(sph);
		org.junit.Assert.assertTrue(ok);

		// replace retrycnt|status fields of first record and mess up both line endings
		java.nio.file.Path incomingDir = (java.nio.file.Path)DynLoader.getField(qmgr, "incomingDir");
		java.util.ArrayList<String> filenames = FileOps.directoryListSimple(incomingDir);
		org.junit.Assert.assertEquals(1, filenames.size());
		java.io.File fh_newmsg = incomingDir.resolve(filenames.get(0)).toFile();
		byte[] buf = FileOps.read(fh_newmsg).toArray(true);
		java.io.OutputStream ostrm = new java.io.FileOutputStream(fh_newmsg);
		int off = ByteOps.indexOf(buf, '\n');
		ostrm.write(buf, 0, off - 3);
		ostrm.write("2|421 \r \t\r".getBytes()); //append white space to first line and change ending to CRLF
		ostrm.write(buf, off, buf.length - off - 1); //strip linefeed from second line
		ostrm.close();

		// copy file to bounces dir, to be picked up by getBounces()
		java.io.File dh_bounces = new java.io.File(fh_newmsg.getParentFile().getParentFile(), "bounces");
		java.io.File fh_bounce = new java.io.File(dh_bounces, "M000001523D6D6392-2.mqc");
		FileOps.ensureDirExists(dh_bounces);
		FileOps.copyFile(fh_newmsg.toPath(), fh_bounce.toPath());

		// load the MQC and verify it is parsed as expected
		com.grey.mailismus.mta.queue.Cache qcache = qmgr.initCache(2);
		for (int loop = 0; loop != 2; loop++) {
			qcache.clear();
			if (loop == 0) {
				qmgr.getMessages(qcache);
			} else {
				qmgr.getBounces(qcache);
			}
			org.junit.Assert.assertEquals(2, qcache.size());
			for (int idx = 0; idx != qcache.size(); idx++) {
				MessageRecip mr = qcache.get(idx);
				org.junit.Assert.assertEquals(170, mr.spid);
				org.junit.Assert.assertEquals(recvtime, mr.recvtime);
				org.junit.Assert.assertEquals("senderA@domainB", mr.sender.toString());
				if (idx == 0) {
					org.junit.Assert.assertEquals(1, mr.qid);
					org.junit.Assert.assertEquals("recip1", mr.mailbox_to.toString());
					org.junit.Assert.assertEquals("domain2", mr.domain_to.toString());
					org.junit.Assert.assertEquals(2, mr.retrycnt);
					if (loop == 0) {
						org.junit.Assert.assertEquals(0, mr.smtp_status);
					} else {
						org.junit.Assert.assertEquals(421, mr.smtp_status);
					}
				} else {
					org.junit.Assert.assertEquals(2, mr.qid);
					org.junit.Assert.assertEquals("recip11", mr.mailbox_to.toString());
					org.junit.Assert.assertEquals("domain12", mr.domain_to.toString());
					org.junit.Assert.assertEquals(0, mr.retrycnt);
					org.junit.Assert.assertEquals(0, mr.smtp_status);
				}
			}
		}
	}
}
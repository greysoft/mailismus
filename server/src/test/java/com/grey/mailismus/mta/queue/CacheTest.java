/*
 * Copyright 2012-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

public class CacheTest
{
	@org.junit.Test
	public void testSortSPID()
	{
		Cache cache = new Cache(5);
		MessageRecip mr1 = cache.addEntry(2, 10, 1, 0, new com.grey.base.utils.ByteChars("sender2"),
				new com.grey.base.utils.ByteChars("dom_b"), new com.grey.base.utils.ByteChars("mbox_b"),
				(short)0, (short)0);
		MessageRecip mr2 = cache.addEntry(2, 10, 2, 0, new com.grey.base.utils.ByteChars("sender1"),
				new com.grey.base.utils.ByteChars("dom_a"), new com.grey.base.utils.ByteChars("mbox_a"),
				(short)0, (short)0);
		cache.sort();
		org.junit.Assert.assertEquals(1, cache.get(0).recvtime);
		org.junit.Assert.assertEquals("sender2", cache.get(0).sender.toString());
		org.junit.Assert.assertEquals(2, cache.get(1).recvtime);
		org.junit.Assert.assertEquals("sender1", cache.get(1).sender.toString());
		org.junit.Assert.assertSame(mr1, cache.get(0));
		org.junit.Assert.assertSame(mr2, cache.get(1));

		//verify no change if leading element gets higher ranking SPID
		cache.get(0).spid = 5;
		cache.sort();
		org.junit.Assert.assertEquals(1, cache.get(0).recvtime);
		org.junit.Assert.assertEquals("sender2", cache.get(0).sender.toString());
		org.junit.Assert.assertEquals(2, cache.get(1).recvtime);
		org.junit.Assert.assertEquals("sender1", cache.get(1).sender.toString());
		org.junit.Assert.assertSame(mr1, cache.get(0));
		org.junit.Assert.assertSame(mr2, cache.get(1));

		//verify they swap places if second element gets higher ranking SPID
		cache.get(1).spid = 0;
		cache.sort();
		org.junit.Assert.assertEquals(2, cache.get(0).recvtime);
		org.junit.Assert.assertEquals("sender1", cache.get(0).sender.toString());
		org.junit.Assert.assertEquals(1, cache.get(1).recvtime);
		org.junit.Assert.assertEquals("sender2", cache.get(1).sender.toString());
		org.junit.Assert.assertSame(mr1, cache.get(1));
		org.junit.Assert.assertSame(mr2, cache.get(0));

		//local recips sort to top
		MessageRecip mr3 = cache.addEntry(3, 99, 0, 0, new com.grey.base.utils.ByteChars("sender3"),
				null, new com.grey.base.utils.ByteChars("mbox_z"),
				(short)0, (short)0);
		cache.sort();
		org.junit.Assert.assertEquals(3, cache.get(0).qid);
		org.junit.Assert.assertEquals("sender3", cache.get(0).sender.toString());
		org.junit.Assert.assertEquals(2, cache.get(1).recvtime);
		org.junit.Assert.assertEquals("sender1", cache.get(1).sender.toString());
		org.junit.Assert.assertEquals(1, cache.get(2).recvtime);
		org.junit.Assert.assertEquals("sender2", cache.get(2).sender.toString());
		org.junit.Assert.assertSame(mr3, cache.get(0));
		org.junit.Assert.assertSame(mr1, cache.get(2));
		org.junit.Assert.assertSame(mr2, cache.get(1));
	}
}
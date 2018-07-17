/*
 * Copyright 2010-2016 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

public final class Cache
{
	// Sorts MessageRecip objects by {spid, qid} in that order.
	// Although domains don't otherwise feature in the sort order, null domains (which represent local recipients) are a special
	// case that is considered to lexicographically precede all the non-null ones.
	// QID ordering doesn't functionally matter, but use it as the final tie-breaker, to allow for more determinate testing.
	private static class MessageRecipComparator_BySpid
		implements java.util.Comparator<MessageRecip>, java.io.Serializable
	{
		private static final long serialVersionUID = 1L;
		public MessageRecipComparator_BySpid() {} //eliminate warning about synthetic accessor
		@Override
		public int compare(MessageRecip mr1, MessageRecip mr2) {
			if (mr1.domain_to == null) {
				if (mr2.domain_to != null) return -1; //local recip mr1 precedes remote mr2
			} else {
				if (mr2.domain_to == null) return 1; //local recip mr2 precedes remote mr1
			}
			int cmp = mr1.spid - mr2.spid;
			if (cmp == 0) cmp = mr1.qid - mr2.qid;
			return cmp;
		}
	}

	private static final MessageRecipComparator_BySpid mrcmp_spid = new MessageRecipComparator_BySpid();
	private final MessageRecip[] reciplist;
	private int entrycnt;

	public MessageRecip get(int idx) {return reciplist[idx];}
	public int capacity() {return reciplist.length;}
	public int size() {return entrycnt;}

	Cache(int cap)
	{
		reciplist = new MessageRecip[cap];

		for (int idx = 0; idx != cap; idx++) {
			reciplist[idx] = new MessageRecip();
		}
	}

	public void clear()
	{
		truncate(0);
	}

	public MessageRecip addEntry(int qid, int spid, long recvtime, int iprecv,
			com.grey.base.utils.ByteChars sndr, com.grey.base.utils.ByteChars domto, com.grey.base.utils.ByteChars mboxto,
			int retrycnt, int status)
	{
		MessageRecip mr = reciplist[entrycnt].set(qid, spid, recvtime, iprecv, sndr, domto, mboxto, retrycnt, status);
		entrycnt++; //incrementing afterwards ensures it remains unchanged on ArrayIndexOutOfBoundsException
		return mr;
	}

	public void truncate(int newsize)
	{
		while (entrycnt > newsize) {
			reciplist[--entrycnt].clear();
		}
	}

	public void sort()
	{
		java.util.Arrays.sort(reciplist, 0, entrycnt, mrcmp_spid);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(size()*80);
		sb.append("QueueCache=").append(size()).append("/{");
		for (int idx = 0; idx != size(); idx++) {
			if (idx != 0) sb.append("; ");
			sb.append('[').append(idx).append(']');
			get(idx).toString(sb);
		}
		sb.append('}');
		return sb.toString();
	}
}
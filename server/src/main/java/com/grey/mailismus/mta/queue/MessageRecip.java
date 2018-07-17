/*
 * Copyright 2010-2016 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.base.utils.ByteOps;
import com.grey.base.utils.IntValue;

/*
 * This class is used by the Deliver path to pick up messages from the queue, whereas SubmitHandle
 * is used by the Submit path.
 */
public final class MessageRecip
{
	public static final int STATUS_NULL = 0; //has to be zero, as we rely on default constructor
	public static final int STATUS_READY = 1;
	public static final int STATUS_BUSY = 2;
	public static final int STATUS_DONE = 3;

	public int qid; //Queue-ID only guaranteed to be unique relative to the SPID
	public int spid; //Spool-ID - unique ID for a spool file, and hence for a particular msg (possibly with multiple recips)
	public long recvtime;
	public com.grey.base.utils.ByteChars sender;
	public com.grey.base.utils.ByteChars domain_to;
	public com.grey.base.utils.ByteChars mailbox_to;
	public int ip_recv;
	public int ip_send; //not preserved in queue, only available during SMTP-Client session
	public short retrycnt; //strictly speaking, this is the Try count, not the Retry count - becomes 1 after 1st fail, etc
	public short smtp_status;
	public byte qstatus;

	public MessageRecip()
	{
		clear();
	}

	// reset status and release object refs for benefit of GC
	public MessageRecip clear()
	{
		qstatus = STATUS_NULL;
		sender = null;
		domain_to = null;
		mailbox_to = null;
		return this;
	}

	public MessageRecip set(int id, int spoolid, long rcvtim, int iprecv,
			com.grey.base.utils.ByteChars sndr, com.grey.base.utils.ByteChars domto, com.grey.base.utils.ByteChars mboxto,
			int retries, int status)
	{
		qstatus = STATUS_READY;
		qid = id;
		spid = spoolid;
		recvtime = rcvtim;
		ip_recv = iprecv;
		sender = sndr;
		domain_to = domto;
		mailbox_to = mboxto;
		retrycnt = (short)retries;
		smtp_status = (short)status;
		ip_send = 0;
		return this;
	}

	@Override
	public String toString() {
		return toString(null).toString();
	}

	public StringBuilder toString(StringBuilder sb) {
		if (sb == null) sb = new StringBuilder(80);
		sb.append(sender).append("=>").append(mailbox_to);
		if (domain_to != null) sb.append('@').append(domain_to);
		sb.append("/spid=0x");
		IntValue.encodeHex(spid & ByteOps.INTMASK, true, sb).append("/qid=").append(qid);
		sb.append("/status=").append(status(qstatus)).append(':').append(smtp_status);
		if (retrycnt != 0) sb.append("/retries=").append(retrycnt);
		return sb;
	}

	public static String status(int sts) {
		switch (sts) {
		case STATUS_NULL: return "NULL";
		case STATUS_READY: return "READY";
		case STATUS_BUSY: return "BUSY";
		case STATUS_DONE: return "DONE";
		default: return Integer.toString(sts);
		}
	}
}
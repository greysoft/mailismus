/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.FileOps;
import com.grey.logging.Logger.LEVEL;

/*
 * This class is used for submitting messages into the queue.
 * See MessageRecip for the reverse direction.
 */
public final class SubmitHandle
{
	private static final byte[] CRLF = {'\r', '\n'};

	public int spid;
	public com.grey.base.utils.ByteChars sender;
	public java.util.ArrayList<EmailAddress> recips;
	public java.util.ArrayList<ByteChars> sender_rewrites;
	public int iprecv;

	private java.nio.file.Path pthnam;
	private com.grey.base.utils.MutableOutputStream strm;

	public java.nio.file.Path getMessage() {return pthnam;}
	boolean close(com.grey.logging.Logger logger) {return close(0, false, logger);}

	void create(java.nio.file.Path p, int bufsiz, boolean no_open) throws java.io.IOException
	{
		java.io.OutputStream fstrm = null;
		if (no_open) {
			java.nio.file.Files.createFile(p, FileOps.FATTR_NONE);
		} else {
			fstrm = java.nio.file.Files.newOutputStream(p, FileOps.OPENOPTS_CREATNEW);
		}
		if (fstrm != null) strm = new com.grey.base.utils.MutableOutputStream(fstrm, bufsiz);
		pthnam = p;
	}

	public boolean close(int truncate, boolean add_crlf, com.grey.logging.Logger logger)
	{
		if (strm == null) return true;
		boolean ok = true;
		try {
			if (truncate != 0) strm.truncateBy(truncate);
			if (add_crlf) strm.write(CRLF);
			strm.close();
		} catch (Throwable ex) {
			if (logger != null) logger.log(LEVEL.TRC, ex, false, "Failed to close spool-file - "+pthnam);
			ok = false;
		}
		strm = null;
		return ok;
	}

	// close() should already have been called to nullify strm
	SubmitHandle release()
	{
		pthnam = null;
		sender = null;
		recips = null;
		sender_rewrites = null;
		spid = 0;
		return this;
	}

	public void write(byte[] buf, int off, int len) throws java.io.IOException
	{
		strm.write(buf, off, len);
	}

	public void write(ByteArrayRef arr) throws java.io.IOException
	{
		write(arr.buffer(), arr.offset(), arr.size());
	}
}
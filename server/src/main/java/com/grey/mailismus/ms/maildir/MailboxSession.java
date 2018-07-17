/*
 * Copyright 2013-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.utils.StringOps;

public final class MailboxSession
{
	public interface UpdatesListener
	{
		public void reportExpunge(int seqnum, Object arg) throws java.io.IOException;
		public void reportMessageFlags(int seqnum, CharSequence newflags, boolean with_uid, Object arg) throws java.io.IOException;
	}

	public interface MessageTransmitter
	{
		public void transmitterSend(java.nio.channels.FileChannel chan, long off, long len) throws java.io.IOException;
		public void transmitterReportSize(int size, Object arg) throws java.io.IOException;
	}

	final MailboxUser uh; //user handle
	private Mailbox mbxCurrent;

	public char getHierarchyDelimiter() {return MaildirStore.DLM_HIERARCHY;}
	public String getUsername() {return uh.username;}
	public int getMailboxCount() {return uh.mailboxes.length;}
	public String getMailboxName(int idx) {return uh.mailboxes[idx];}
	public String existsMailbox(CharSequence mbxname) {return uh.existsMailbox(mbxname);}
	public String currentMailbox() {return mbxCurrent == null ? null : mbxCurrent.activeview.mbxname;}
	public MailboxView currentView() {return mbxCurrent == null ? null : mbxCurrent.activeview;}
	public boolean writeableMailbox() {return mbxCurrent == null ? false : !mbxCurrent.activeview.rdonly;}

	public MailboxSession(MailboxUser uh)
	{
		this.uh = uh;
		uh.loggedOn();
	}

	public void endSession()
	{
		closeMailbox();
		uh.loggedOff();
	}

	// We create mailboxes in the specified case, but in ensuring their uniqueness, we first make
	// sure they don't differ from any existing ones in case only.
	public boolean createMailbox(CharSequence mbxname)
	{
		return uh.createMailbox(mbxname);
	}

	public boolean deleteMailbox(CharSequence mbxname) throws java.io.IOException
	{
		return uh.deleteMailbox(mbxname);
	}

	public int renameMailbox(CharSequence srcname, CharSequence dstname)
	{
		return uh.renameMailbox(srcname, dstname);
	}

	public MailboxView openMailbox(CharSequence mbxname, boolean rdonly) throws java.io.IOException
	{
		mbxCurrent = openMailbox(mbxname, rdonly, false);
		return mbxCurrent.activeview;
	}

	public MailboxView statMailbox(CharSequence mbxname) throws java.io.IOException
	{
		Mailbox mbx = openMailbox(mbxname, true, true);
		return mbx.activeview;
	}

	private Mailbox openMailbox(CharSequence mbxname, boolean rdonly, boolean with_peek) throws java.io.IOException
	{
		java.io.File dh_top = uh.getMailboxDir(mbxname);
		if (dh_top == null) return null;
		return new Mailbox(this, mbxname, dh_top, rdonly, with_peek);
	}

	public void closeMailbox()
	{
		mbxCurrent = null;
	}

	public boolean messageExists(int seqnum)
	{
		return mbxCurrent.messageExists(seqnum);
	}

	public void setMessageFlags(int mode, com.grey.base.collections.NumberList seqlst, String flags, boolean report_uid, int off, int lmt,
			MailboxSession.UpdatesListener listener, Object argcb) throws java.io.IOException
	{
		mbxCurrent.setMessageFlags(mode, seqlst, flags, report_uid, off, lmt, listener, argcb);
	}

	public boolean getMessage(int seqnum, boolean peek, MimePart mime, boolean excl_headers, int off, int maxlen,
			MessageTransmitter transmitter, Object transmitArg) throws java.io.IOException
	{
		return mbxCurrent.getMessage(seqnum, peek, mime, excl_headers, off, maxlen, transmitter, transmitArg);
	}

	public boolean getHeaders(int seqnum, boolean peek, String[] hdrs, MimePart mime, boolean excl,
			com.grey.base.utils.ByteChars outbuf, com.grey.base.collections.HashedMap<String,String> outmap) throws java.io.IOException
	{
		return mbxCurrent.getHeaders(seqnum, peek, hdrs, mime, excl, outbuf, outmap);
	}

	public MimePart getMimeStructure(int seqnum) throws java.io.IOException
	{
		return mbxCurrent.getMimeStructure(seqnum);
	}

	public void expungeMailbox(MailboxSession.UpdatesListener listener, Object argcb) throws java.io.IOException
	{
		mbxCurrent.expunge(listener, false, argcb);
	}

	public boolean searchMessages(com.grey.base.collections.NumberList results, boolean uidmode, com.grey.base.collections.NumberList seqlst,
			java.util.HashMap<String, String> hdrs_incl, java.util.HashMap<String, String> hdrs_excl, String[] hdrnames,
			String flags_incl, String flags_excl, long mintime, long maxtime, int minsize, int maxsize,
			int off, int lmt)
	{
		return mbxCurrent.search(results, uidmode, seqlst,hdrs_incl, hdrs_excl, hdrnames, flags_incl, flags_excl,
				mintime, maxtime, minsize, maxsize, off, lmt);
	}

	public boolean loadUpdates(MailboxSession.UpdatesListener listener, Object arg, int opts) throws java.io.IOException
	{
		return mbxCurrent.loadUpdates(listener, arg, opts);
	}

	// dest_mbx is expected to be valid - if not, we'll merely end up copying to a spurious directory
	public void injectMessage(java.io.File fh, String dest_mbx, CharSequence msflags) throws java.io.IOException
	{
		uh.ms.deliver(uh.username, dest_mbx, fh, msflags, true, false);
	}

	// dest_mbx is expected to be valid - if not, we'll merely end up copying to a spurious directory
	public boolean copyMessage(int seqnum, String dest_mbx) throws java.io.IOException
	{
		boolean done = false;
		StringBuilder sb = uh.ms.sharedtmpsb;
		java.io.File fh = mbxCurrent.getMessageFile(seqnum);
		sb.setLength(0);
		mbxCurrent.activeview.getMessageFlags(seqnum, sb);
		int pos = StringOps.indexOf(sb, MaildirStore.MSGFLAG_RECENT);
		if (pos != -1) sb.deleteCharAt(pos);
		try {
			uh.ms.deliver(uh.username, dest_mbx, fh, sb, true, true);
			done = true;
		} catch (java.io.IOException ex) {
			//don't throw if somebody else has removed the source file
			if (fh.exists()) throw ex; //genuine error
		}
		return done;
	}
}
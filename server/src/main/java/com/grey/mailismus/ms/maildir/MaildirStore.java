/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.config.SysProps;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;

/*
 * The incoming message file is assumed to come from the MTA queue's spool area, where it is stored in
 * the dot-stuffed format.
 * See http://cr.yp.to/proto/maildir.html for message-file naming.
 */
public final class MaildirStore
	extends com.grey.mailismus.ms.MessageStoreImpl
{
	public static final char MSGFLAG_DRAFT = 'D';
	public static final char MSGFLAG_FLAGGED = 'F';
	public static final char MSGFLAG_PASSED = 'P';
	public static final char MSGFLAG_REPLIED = 'R';
	public static final char MSGFLAG_SEEN = 'S';
	public static final char MSGFLAG_DEL = 'T';
	public static final char MSGFLAG_RECENT = 'Z'; //non-standard pseudo-flag which will never appear in a filename

	public static final int RPT_EXCL_EXPUNGE = 1 << 0;
	public static final int RPT_EXCL_NEW = 1 << 1; //implies EXCL_RECENT as well
	public static final int RPT_EXCL_RECENT = 1 << 2;
	public static final int RPT_FLAGS_ONLY = (RPT_EXCL_EXPUNGE | RPT_EXCL_NEW);

	static final String MDIR_CUR = "cur";
	static final String MDIR_NEW = "new";
	static final String MDIR_TMP = "tmp";

	static final String UNSTUFFED_MARKER = "_NOTSTUFFED_";
	static final char DLM_HIERARCHY = '.';
	private static final String DLM_AS_STRING = String.valueOf(MaildirStore.DLM_HIERARCHY);

	private static final String TOKEN_USERNAME = "%U%";
	private static final byte[] ESCSEQ_DOT = new byte[]{'\n', '.', '.'};

	//True means we do/undo dotstuffing as messages enter and leave the MS, and they are stored in non-dotstuffed mode.
	//False means the MS holds messages in their intermediate dot-stuffed form.
	final boolean dotstuffing;
	final boolean mailismus_delivery;
	final String FLAGS_MARKER; //flags come after this, at the end of a filename

	private final com.grey.base.collections.HashedMap<String, MailboxUser> activeUsers = new com.grey.base.collections.HashedMap<String, MailboxUser>();
	private final boolean virtual_users = (directory() == null ? true : directory().virtualUsers());
	private final String path_users;
	private final String path_maildir;
	private final char symbol_colon;
	private final char symbol_comma;
	private final String chmod_tree;
	private final boolean chmod_msgfile;
	private final String suffix_newmsgfile;
	final int hdrbufsiz;
	final int msgbufsiz;
	private byte[] msgfilebuf; //not needed in all modes, so allocate if needed rather than making it final
	private int deliv_cnt;

	//pre-allocated purely for efficiency
	final StringBuilder sharedtmpsb = new StringBuilder();
	final StringBuilder sharedtmpsb2 = new StringBuilder();
	final com.grey.base.utils.ByteChars tmplightbc = new com.grey.base.utils.ByteChars(-1); //lightweight object without own storage
	final com.grey.base.collections.HashedMap<String,String> tmpmap = new com.grey.base.collections.HashedMap<String,String>();
	final byte[] mimebndry = new byte[80]; //RFC-2046 5.1.1 says max is 70 chars, excl the 2 leading hyphens, so add small safety margin
	java.nio.ByteBuffer tmpniobuf;

	private final StringBuilder localtmpsb = new StringBuilder(); //guaranteed not to conflict with other classes

	static boolean isInbox(CharSequence mbxname) {return DLM_AS_STRING.equals(mbxname.toString());}

	public MaildirStore(com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg, com.grey.mailismus.directory.Directory dtory)
		throws java.io.IOException
	{
		super(d, cfg, dtory);

		// Windows doesn't like colons in filenames (nor allegedly commas, but that seems to work)
		char dflt_colon = (SysProps.isWindows ? '+' : ':');
		String hostname = java.net.InetAddress.getLocalHost().getCanonicalHostName();

		path_users = dsptch.getApplicationContext().getConfig().getPath(cfg, "userpath", null, true, null, getClass());
		path_maildir = cfg.getValue("mailpath", true, "Maildir");
		dotstuffing = cfg.getBool("dotstuffing", false);
		mailismus_delivery = cfg.getBool("exclusive", false);
		symbol_colon = cfg.getChar("filename_colon", true, dflt_colon);
		symbol_comma = cfg.getChar("filename_comma", true, ',');
		chmod_tree = cfg.getValue("chmod_tree", false, "chown -R "+TOKEN_USERNAME+" ."); //to be run from ./Maildir
		chmod_msgfile = cfg.getBool("chmod_msgfile", true);

		int minsiz = 16 * 1024; //will barely work below 2K - 16K seems to be optimal
		hdrbufsiz = Math.max((int)cfg.getSize("hdrbufsiz", minsiz), minsiz);
		msgbufsiz = Math.max((int)cfg.getSize("msgbufsiz", "256K"), hdrbufsiz);

		FLAGS_MARKER = new StringBuilder().append(symbol_colon).append('2').append(symbol_comma).toString();
		suffix_newmsgfile = new StringBuilder(".").append(hostname.toLowerCase().replace('.', '_')).toString();

		dsptch.getLogger().info("MS-Maildir: users-path = "+path_users);
		dsptch.getLogger().info("MS-Maildir: maildir = "+path_maildir);
		dsptch.getLogger().info("MS-Maildir: dotstuffed="+dotstuffing+", mailismus_delivery="+mailismus_delivery);
		dsptch.getLogger().trace("MS-Maildir: colon="+symbol_colon+" ("+(int)symbol_colon+")");
		dsptch.getLogger().trace("MS-Maildir: comma="+symbol_comma+" ("+(int)symbol_comma+")");
		dsptch.getLogger().trace("MS-Maildir: iobuf="+ByteOps.expandByteSize(msgbufsiz, null, false)
				+", hdrbuf="+ByteOps.expandByteSize(hdrbufsiz, null, false));
		if (!virtual_users) dsptch.getLogger().info("MS-Maildir: chmod tree ["+chmod_tree+"] - msgfile="+chmod_msgfile);

		//make sure the Maildir suffix chars are acceptable for this platform
		java.io.File fh1 = new java.io.File(d.getApplicationContext().getConfig().getPathTemp()+"/ms_"+Thread.currentThread().getId()+".test"+FLAGS_MARKER+"x");
		java.io.File fh2 = new java.io.File(fh1.getParentFile(), fh1.getName()+"y");
		FileOps.deleteFile(fh1);
		FileOps.deleteFile(fh2);
		if (!fh1.createNewFile()) throw new java.io.IOException("MS cannot create files with flag suffixes");
		if (!fh1.renameTo(fh2)) throw new java.io.IOException("MS cannot rename files with flag suffixes");
		FileOps.deleteFile(fh2);
	}

	public InboxSession startInboxSession(CharSequence u)
	{
		String username = u.toString();
		return new InboxSession(this, username);
	}

	public MailboxSession startMailboxSession(CharSequence u)
	{
		String username = u.toString();
		MailboxUser uh = activeUsers.get(username);
		if (uh == null) {
			uh = new MailboxUser(this, username);
			activeUsers.put(username, uh);
		}
		return new MailboxSession(uh);
	}

	// this brackets startMailboxSession()
	void mailboxSessionEnded(MailboxUser uh)
	{
		if (uh.sessionCount() == 0) activeUsers.remove(uh.username);
	}

	// We don't bother checking if the username is valid here, as it must have already passed the test for
	// the message to get this far. Even if the user has been deleted while the message was in flight, their
	// old mailbox is still as good a place as any to park this message.
	@Override
	public void deliver(CharSequence username, java.io.File fh_msg) throws java.io.IOException
	{
		deliver(username, null, fh_msg, null, false, false);
	}

	/*
	 * I have considered whether the two file-creation loops in here (the fh_tmp.createNewFile() and fh_tmp.renameTo(fh_new) loops)
	 * should throw if they're still failing after some maximum number of attempts, as that's likely to indicate a filesystem or
	 * permissions issue that we're not going to overcome.
	 * However, in such an event, getting stuck in an infinite loop is probably less damaging than the alternative, which could
	 * consist of rapidly failing 1000s of delivery attempts that would have succeeded once the underlying system problem was fixed.
	 */
	void deliver(CharSequence username, CharSequence mbxname, java.io.File fh_msg, CharSequence msflags,
			boolean is_unstuffed, boolean preserve_attribs) throws java.io.IOException
	{
		boolean undo_dotstuffing = (is_unstuffed ? false : dotstuffing);
		String suffix = suffix_newmsgfile; //flagless filename ending
		StringBuilder sb = localtmpsb;

		makeRootPath(username, sb);
		if (mbxname != null) sb.append('/').append(mbxname);
		java.io.File dh_root = new java.io.File(sb.toString());
		java.io.File dh_tmp = new java.io.File(dh_root, MDIR_TMP);

		// Create unique filename in Maildir tmp area (may have to create the tmp directory, first time around)
		sb.setLength(0);
		if (preserve_attribs) {
			// Preserve existing mbxname flags (but if msflags was specified, it overrides), and also
			// preserve its timestamp portion.
			String srcname = fh_msg.getName();
			int pos1 = srcname.indexOf('.');
			int pos2 = (pos1 == -1 ? -1 : srcname.indexOf('.', pos1+1));
			if (pos2 == -1) {
				preserve_attribs = false;
			} else {
				int lmt = srcname.length();
				if (msflags != null && msflags.length() != 0) {
					//we're not preserving any flags
					int pos3 = srcname.indexOf(FLAGS_MARKER, pos2);
					if (pos3 != -1) lmt = pos3;
				}
				sb.append(srcname, 0, pos1); //copy timestamp - exclude terminating dot
				suffix = srcname.substring(pos2, lmt); //include initial dot
			}
		}
		if (is_unstuffed) suffix = UNSTUFFED_MARKER+suffix;
		if (!preserve_attribs) TimeOps.zeroPad(dsptch.getSystemTime(), sb);
		sb.append('.');

		// We're not worried about sort order beyond this point. If filenames are still tied, they represent simultaneous
		// messages, so it doesn't matter which one ultimately sorts above the other.
		int off_uniq = sb.length();
		boolean created = true;
		boolean init = false;
		java.io.File fh_tmp;
		do {
			sb.setLength(off_uniq);
			sb.append(++deliv_cnt).append(suffix);
			if (msflags != null && msflags.length() != 0) sb.append(FLAGS_MARKER).append(msflags);
			fh_tmp = new java.io.File(dh_tmp, sb.toString());
			try {
				created = fh_tmp.createNewFile();
			} catch (java.io.IOException ex) {
				// assume that creation failure was caused by missing TMP directory - a 2nd failure is genuine
				if (!dh_tmp.exists()) {
					FileOps.ensureDirExists(dh_tmp);
					init = true;
				}
				created = fh_tmp.createNewFile();
			}
		} while (!created);

		// Write message to tmp file.
		transferMessage(fh_msg, fh_tmp, undo_dotstuffing);

		// identify the associated new-message pathname
		java.io.File dh_new = new java.io.File(dh_root, MDIR_NEW);
		java.io.File fh_new = new java.io.File(dh_new, fh_tmp.getName());

		// rename TMP file into the NEW directory - handle naming collisions
		int failcnt = 0;
		while (!fh_tmp.renameTo(fh_new)) {
			if (failcnt++ == 0) {
				// failure might have been caused by missing NEW directory
				boolean existed = dh_new.exists();
				FileOps.ensureDirExists(dh_new);
				if (!existed) {
					init = true;
					continue;
				}
			}
			if (!fh_new.exists()) {
				if (failcnt == 10) {
					// After so many failures, this is hardly likely to be a race condition where
					// fh_new did exist, but was just deleted by somebody else.
					// We must have a permissions problem, so break out of otherwise infinite loop.
					throw new java.io.IOException("MS cannot create files in "+dh_new.getAbsolutePath()
							+" - src="+fh_tmp.exists()+":"+fh_tmp.getAbsolutePath());
				}
			}
			sb.setLength(off_uniq);
			sb.append(++deliv_cnt).append(suffix);
			if (msflags != null && msflags.length() != 0) sb.append(FLAGS_MARKER).append(msflags);
			fh_new = new java.io.File(dh_new, sb.toString());
		}
		setFilePermissions(username, fh_new, init ? dh_new : null);
	}

	// A dot-stuffed message, could never have "\r\n." followed by anything other than another dot, so it
	// should be sufficient to scan for "\r\n." and strip its dot.
	// However, scanning for the longer "\r\n.." sequence enables us to tolerate messages that haven't
	// been dot-stuffed (ie. we won't strip their accidental dot), while it still works correctly on
	// properly dot-stuffed messages.
	// Of course an accidental "\r\n.." in a non-escaped message will still get modified, but it's that bit
	// less likely to occur, and there's no non-invasive way of handling mis-shapen input.
	void transferMessage(java.io.File fh_src, java.io.File fh_dst, boolean undo_dotstuffing) throws java.io.IOException
	{
		java.io.FileInputStream istrm = new java.io.FileInputStream(fh_src);
		java.io.FileOutputStream ostrm = null;
		try {
			ostrm = new java.io.FileOutputStream(fh_dst);
			if (!undo_dotstuffing) {
				istrm.getChannel().transferTo(0, fh_src.length(), ostrm.getChannel());
			} else {
				byte[] xferbuf = getMessageBuffer();
				int offseq = 2; //ESCSEQ offset - set ourselves up to treat the 1st line like any other
				int offdata = 0; //xferbuf write offset - records how much we have written to ostrm
				int offscan = 0; //xferbuf scanning offset
				int bufsiz = 0;
				do {
					if (offscan == bufsiz) {
						// refill the exhausted buffer
						if (bufsiz != 0) {
							// flush its remaining bytes first
							ostrm.write(xferbuf, offdata, bufsiz - offdata);
						}
						if ((bufsiz = istrm.read(xferbuf)) == -1) break; //EOF - break out of loop
						offscan = 0;
						offdata = 0;
					}
					if (offseq == 0) {
						// scan for start of escaped-dots sequence
						if ((offscan = ByteOps.indexOf(xferbuf, offscan, bufsiz - offscan, ESCSEQ_DOT[0])) == -1) {
							// this buffer has now been scanned in full, so trigger a refill
							offscan = bufsiz;
							continue;
						}
						offseq = 1;
						offscan++;
					} else {
						// we are in in mid-sequence, so check if current offset still matches
						if (xferbuf[offscan] == ESCSEQ_DOT[offseq++]) {
							// so far so good - advance onwards
							offscan++;
							if (offseq == ESCSEQ_DOT.length) {
								// we have now detected the full escape sequence, so undo it as we write
								ostrm.write(xferbuf, offdata, offscan - offdata - 1); //strip final ESCSEQ dot
								offdata = offscan; //advance past final ESCSEQ dot
								offseq = 0;
							}
						} else {
							// matching sequence terminated early - we're not on an ESCSEQ match after all
							offseq = 0;
						}
					}
				} while (bufsiz != -1);
			}
		} finally {
			try {
				istrm.close();
			} finally {
				if (ostrm != null) ostrm.close();
			}
		}
	}

	// do ownership/permission settings for native users
	void setFilePermissions(CharSequence username, java.io.File fh, java.io.File dh) throws java.io.IOException
	{
		if (virtual_users) return;
		if (dh != null && chmod_tree != null) {
			//waitFor() for chown returns 0 on Unix, 1 on Cygwin (was bad user), throws in Windows
			String cmd = chmod_tree.replace(TOKEN_USERNAME, username);
			Process proc = Runtime.getRuntime().exec(cmd, null, dh.getParentFile());
			try {proc.waitFor();} catch (InterruptedException ex) {}
		}
		if (chmod_msgfile) {
			boolean wsts = fh.setWritable(true, true);
			boolean rsts = fh.setReadable(true, true);
			if (!rsts || !wsts) dsptch.getLogger().warn("Maildir failed to chmod new msg - readable="+rsts+", writeable="+wsts);
		}
	}

	byte[] getMessageBuffer()
	{
		if (msgfilebuf == null) msgfilebuf = new byte[msgbufsiz];
		return msgfilebuf;
	}

	java.io.File getDropDir(CharSequence username)
	{
		makeRootPath(username, localtmpsb).append('/').append(MDIR_NEW);
		return new java.io.File(localtmpsb.toString());
	}

	private StringBuilder makeRootPath(CharSequence username, StringBuilder sb)
	{
		sb.setLength(0);
		sb.append(path_users).append("/").append(username).append("/").append(path_maildir);
		return sb;
	}
}
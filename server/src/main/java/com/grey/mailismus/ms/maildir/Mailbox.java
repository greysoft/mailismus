/*
 * Copyright 2013-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.config.SysProps;
import com.grey.base.utils.ByteOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.StringOps;
import com.grey.logging.Logger;

final class Mailbox
{
	private static boolean REPORTNULLSTORE = SysProps.get("grey.maildir.reportnullstore", true); //RFC-3501 behaviour
	private static boolean UNFOLDALLHEADERS = SysProps.get("grey.maildir.unfoldall", true);
	private static final Logger.LEVEL MSGTRC = Logger.LEVEL.TRC2;

	private static final int UIDMETABUF = ByteOps.INTBYTES*2; //binary buffer consists two Ints in Big Endian order
	private static final String[] MIME_HDRS = new String[]{MimePart.HDR_CTYPE, MimePart.HDR_ENCODING, MimePart.HDR_DISPOSITION,
				MimePart.HDR_CID, MimePart.HDR_CDESC, MimePart.HDR_LANG};
	private static final char TOKEN_SIZE = 'A'; //will be first non-digit/punctuation char in provisional filename
	private static final MessageFilter msgFilter = new MessageFilter();
	private static final MessageSorter msgSorter = new MessageSorter();

	final MailboxView activeview;
	private final MailboxSession usess;
	private final MailboxUser.MailboxStats ustats;
	private final java.io.File dh_root;
	private final java.io.File dh_cur;

	private java.io.File dh_new;
	private java.io.File dh_tmp;
	private long lastmodtime;
	private int modcount;

	public Mailbox(MailboxSession s, CharSequence m, java.io.File dh_top,
						boolean rdonly, boolean with_peek) throws java.io.IOException
	{
		String mbxname = m.toString();
		usess = s;
		ustats = usess.uh.getStats(mbxname);
		dh_root = dh_top;
		dh_cur = new java.io.File(dh_root, MaildirStore.MDIR_CUR);
		activeview = new MailboxView(usess.uh, mbxname, rdonly, true);

		if (!getMetaData(mbxname)) {
			// We have to reset our UID stats.
			// This requires existing messages to be allocated new UIDs and renamed accordingly.
			String[] oldfiles = dh_cur.list();
			if (oldfiles != null && oldfiles.length != 0) {
				//rename existing CUR directory out of the way and create a new one
				dh_tmp = new java.io.File(dh_root, MaildirStore.MDIR_TMP);
				java.io.File dh_former = new java.io.File(dh_tmp, "prevcur");
				FileOps.deleteDirectory(dh_former);
				FileOps.ensureDirExists(dh_tmp);
				if (!dh_cur.renameTo(dh_former)) throw new java.io.IOException("Maildir: Failed to regenerate CUR: "+dh_former.getAbsolutePath());
				FileOps.ensureDirExists(dh_cur); //recreate
				java.util.Arrays.sort(oldfiles); //ought to rank them by age
				//move the messages back into CUR, renaming with a new UID along the way
				for (int idx = 0; idx != oldfiles.length; idx++) {
					java.io.File fh_old = new java.io.File(dh_former, oldfiles[idx]);
					String newname = allocMessage(fh_old, true, false, false);
					if (newname == null) continue; //discarded
					java.io.File fh_new = new java.io.File(dh_cur, newname);
					if (!fh_old.renameTo(fh_new)) {
						throw new java.io.IOException("Maildir: Failed to generate UID: "+fh_old.getAbsolutePath()+" => "+fh_new.getAbsolutePath());
					}
				}
				FileOps.deleteDirectory(dh_former);
				syncMetaData(mbxname);
				usess.uh.ms.dsptch.getLogger().info("Mailbox="+mbxname+" reset uidvalidity="+ustats.uidvalidity
						+", messages="+oldfiles.length);
			}
		}
		loadMessages(mbxname, activeview, !rdonly);
		lastmodtime = dh_cur.lastModified();
		modcount = ustats.modcount;

		if (with_peek) {
			//this is intended to qualify a read-only open - we must not (cannot) access the pending messages
			dh_new = new java.io.File(dh_root, MaildirStore.MDIR_NEW);
			String[] filenames = dh_new.list();
			int cnt = (filenames == null ? 0 : filenames.length);
			activeview.setPending(cnt);
		}
	}

	public java.io.File getMessageFile(int seqnum)
	{
		String filename = activeview.getMessage(seqnum).filename;
		return new java.io.File(dh_cur, filename);
	}

	public boolean messageExists(int seqnum)
	{
		java.io.File fh = getMessageFile(seqnum);
		return fh.exists();
	}

	public void setMessageFlags(int mode, com.grey.base.collections.NumberList seqlst, String flags, boolean report_uid, int lst_off, int lst_lmt,
			MailboxSession.UpdatesListener listener, Object argcb) throws java.io.IOException
	{
		final StringBuilder final_flags = usess.uh.ms.sharedtmpsb;
		final StringBuilder filenamebuf = usess.uh.ms.sharedtmpsb2;

		for (int idx = lst_off; idx != lst_lmt; idx++) {
			final int seqnum = seqlst.get(idx);
			final MailboxView.Message msg = activeview.getMessage(seqnum);
			final String filename = msg.filename;
			final int pos_marker = filename.indexOf(usess.uh.ms.FLAGS_MARKER);
			final int pos_flags = (pos_marker == -1 ? -1 : pos_marker + usess.uh.ms.FLAGS_MARKER.length());
			boolean modified = false;
			final_flags.setLength(0);

			if (mode == -1) {
				// remove the specified flags
				if (pos_flags != -1) { //-1 means there are no flags to remove
					for (int pos = pos_flags; pos != filename.length(); pos++) {
						char oldflag = filename.charAt(pos);
						if (flags.indexOf(oldflag) == -1) {
							final_flags.append(oldflag); //keep this flag
						} else {
							modified = true; //dropping this flag
						}
					}
				}
			} else if (mode == 1) {
				// add the specified flags
				if (pos_flags != -1) {
					// we're keeping all the existing flags, for starters
					final_flags.append(filename, pos_flags, filename.length());
				}
				for (int idx2 = 0; idx2 != flags.length(); idx2++) {
					char newflag = flags.charAt(idx2);
					if (StringOps.indexOf(final_flags, newflag) == -1) {
						// this flag isn't set yet, so add it now
						final_flags.append(newflag);
						modified = true;
					}
				}
			} else {
				// The specified flags replace the existing ones
				// Simplest to just set modified unconditionally, since a redundant untagged response won't hurt
				final_flags.append(flags);
				modified = true;
			}

			if (modified) {
				//Most likely cause of a rename failure is that the message was expunged by another session, but
				//this session won't find that out until it gets a chance to report external expunges, so we have
				//to lie that the update succeeded.
				filenamebuf.setLength(0);
				filenamebuf.append(filename, 0, pos_marker == -1 ? filename.length() : pos_marker);
				if (final_flags.length() != 0) filenamebuf.append(usess.uh.ms.FLAGS_MARKER).append(final_flags);
				java.io.File fh_old = new java.io.File(dh_cur, filename);
				updateMessage(seqnum, filenamebuf.toString(), fh_old, -2);
			}
			if (modified || REPORTNULLSTORE) {
				if (listener != null) {
					if (msg.recent) final_flags.append(MaildirStore.MSGFLAG_RECENT);
					listener.reportMessageFlags(seqnum, final_flags, report_uid, argcb);
				}
			}
		}
	}

	public void expunge(MailboxSession.UpdatesListener listener, boolean discovery_mode, Object argcb) throws java.io.IOException
	{
		boolean modified = false;
		for (int idx = activeview.getMsgCount() - 1; idx >= 0; idx--) {
			final int seqnum = idx + 1;
			if (!discovery_mode && !activeview.hasFlag(seqnum, MaildirStore.MSGFLAG_DEL)) continue;
			java.io.File fh = new java.io.File(dh_cur, activeview.getMessage(seqnum).filename);
			if (discovery_mode) {
				if (fh.exists()) continue;
			} else {
				try {
					FileOps.deleteFile(fh);
					modified = true;
				} catch (Exception ex) {
					//message still exists on disk, so keep our refs to it
					usess.uh.ms.dsptch.getLogger().warn("Maildir: Failed to expunge message="+fh.getAbsolutePath()+" - "+ex);
					continue;
				}
			}
			if (usess.uh.ms.dsptch.getLogger().isActive(MSGTRC)) {
				usess.uh.ms.dsptch.getLogger().log(MSGTRC, "Mailbox="+activeview.mbxname
						+" expunged msg="+seqnum+"/"+activeview.getMsgCount()+" - "+fh.getName());
			}
			messageDeleted(seqnum, listener, argcb);
		}
		if (modified) indicateModified();
	}

	public boolean getMessage(int seqnum, boolean peek, MimePart mime, boolean excl_headers, int off, int maxlen,
			MailboxSession.MessageTransmitter transmitter, Object transmitArg) throws java.io.IOException
	{
		java.io.File fh = getMessageFile(seqnum);
		java.io.RandomAccessFile strm = openMessageFile(fh);
		if (strm == null) return false;
		try {
			long file_off = (mime == null ? 0 : mime.file_off);
			long readsize = (mime == null ? strm.length() : mime.totalsiz);
			if (excl_headers) {
				int hdrbytes;
				if (mime == null) {
					if (file_off != 0) strm.seek(file_off);
					hdrbytes = getHeaders(strm, null, false, null, null);
				} else {
					hdrbytes = mime.totalsiz - mime.bodysiz;
				}
				file_off += hdrbytes;
				readsize -= hdrbytes;
			}
			file_off += off;
			readsize -= off;
			if (maxlen != 0 && maxlen < readsize) readsize = maxlen;
			long filesize = strm.length();
			if (file_off + readsize > filesize) readsize = filesize - file_off;
			transmitter.transmitterReportSize((int)readsize, transmitArg);
			java.nio.channels.FileChannel chan = strm.getChannel();
			strm = null;
			transmitter.transmitterSend(chan, file_off, readsize);
		} finally {
			if (strm != null) strm.close();
		}
		if (!peek) markMessageSeen(seqnum, fh);
		return true;
	}

	public boolean getHeaders(int seqnum, boolean peek, String[] hdrs, MimePart mime, boolean excl,
			com.grey.base.utils.ByteChars outbuf, com.grey.base.collections.HashedMap<String,String> outmap) throws java.io.IOException
	{
		java.io.File fh = getMessageFile(seqnum);
		java.io.RandomAccessFile strm = openMessageFile(fh);
		if (strm == null) return false;
		long file_off = (mime == null ? 0 : mime.file_off);
		try {
			if (file_off != 0) strm.seek(file_off);
			getHeaders(strm, hdrs, excl, outbuf, outmap);
		} finally {
			strm.close();
		}
		if (!peek) markMessageSeen(seqnum, fh);
		return true;
	}

	public MimePart getMimeStructure(int seqnum) throws java.io.IOException
	{
		java.io.File fh = getMessageFile(seqnum);
		java.io.RandomAccessFile strm = openMessageFile(fh);
		if (strm == null) return null;
		try {
			MimePart mime = new MimePart(true);
			mime.totalsiz = activeview.getMessageSize(seqnum);
			parseMimePart(strm, mime);
			return mime;
		} finally {
			strm.close();
		}
	}

	public boolean search(com.grey.base.collections.NumberList results, boolean uidmode, com.grey.base.collections.NumberList seqlst,
			java.util.HashMap<String, String> hdrs_incl, java.util.HashMap<String, String> hdrs_excl, String[] hdrnames,
			String flags_incl, String flags_excl, long mintime, long maxtime, int minsize, int maxsize,
			int msg0, int msglmt)
	{
		if (seqlst != null && seqlst.size() == 0) seqlst = null;
		if (msglmt > activeview.getMsgCount()) msglmt = activeview.getMsgCount();
		if (msg0 > msglmt) msg0 = msglmt;

		for (int idx = msg0; idx != msglmt; idx++) {
			final int seqnum = idx+1;
			final String filename = activeview.getMessage(seqnum).filename;
			boolean match = true;

			if (seqlst != null) {
				match = false;
				for (int idx2 = 0; idx2 != seqlst.size(); idx2++) {
					if (seqlst.get(idx2) == seqnum) {
						match = true;
						break;
					}
				}
			}

			if (match && (flags_incl != null || flags_excl != null)) {
				int pos_flags = filename.indexOf(usess.uh.ms.FLAGS_MARKER);
				if (pos_flags != -1) pos_flags += usess.uh.ms.FLAGS_MARKER.length();
				int len_flags = (pos_flags == -1 ? 0 : filename.length() - pos_flags);
				if (flags_incl != null) {
					// this message must contain all these flags
					if (len_flags == 0) {
						match = false;
					} else {
						for (int idx2 = 0; idx2 != flags_incl.length(); idx2++) {
							if (StringOps.indexOf(filename, pos_flags, len_flags, flags_incl.charAt(idx2)) == -1) {
								match = false;
								break;
							}
						}
					}
				}
				if (match && flags_excl != null && len_flags != 0) {
					// this message must not contain any of these flags
					int lmt = pos_flags + len_flags;
					for (int idx2 = 0; idx2 != lmt; idx2++) {
						if (flags_excl.indexOf(filename.charAt(idx2)) != -1) {
							match = false;
							break;
						}
					}
				}
			}

			if (match && (mintime != 0 || maxtime != 0)) {
				long msgtime = activeview.getMessageTime(seqnum);
				if (msgtime < mintime || (maxtime != 0 && msgtime > maxtime)) match = false;
			}
			if (match && (minsize != 0 || maxsize != 0)) {
				int msgsize = activeview.getMessageSize(seqnum);
				if (msgsize < minsize || (maxsize != 0 && msgsize > maxsize)) match = false;
			}

			if (match && (hdrs_incl.size() + hdrs_excl.size() != 0)) {
				com.grey.base.collections.HashedMap<String,String> msghdrs = usess.uh.ms.tmpmap;
				msghdrs.clear();
				try {
					getHeaders(seqnum, true, hdrnames, null, false, null, msghdrs);
				} catch (Exception ex) {
					match = false;
				}
				for (int idx2 = 0; match && idx2 != hdrs_incl.size(); idx2++) {
					String msgval = msghdrs.get(hdrnames[idx2]);
					String srchval = hdrs_incl.get(hdrnames[idx2]);
					if (srchval.length() == 0 && msgval != null) continue; //mere presence means a match
					if (msgval == null || !msgval.toLowerCase().contains(srchval)) match = false;
				}
				if (match) {
					for (int idx2 = hdrs_incl.size(); match && idx2 != hdrnames.length; idx2++) {
						String msgval = msghdrs.get(hdrnames[idx2]);
						if (msgval == null) continue;
						String srchval = hdrs_excl.get(hdrnames[idx2]);
						if (srchval.length() == 0 || msgval.toLowerCase().contains(srchval)) match = false;
					}
				}
			}

			if (match) {
				int id = seqnum;
				if (uidmode) id = activeview.getMessageUID(seqnum);
				results.append(id);
			}
		}
		return (msglmt == activeview.getMsgCount());
	}

	private int getHeaders(java.io.RandomAccessFile strm, String[] hdrs, boolean excl,
			com.grey.base.utils.ByteChars outbuf, com.grey.base.collections.HashedMap<String,String> outmap) throws java.io.IOException
	{
		int len_separatorline = 2; //we expect headers to be terminated by a CRLF line, but might be LineFeed only
		byte[] filebuf = usess.uh.ms.getMessageBuffer();
		int filebufsiz = usess.uh.ms.hdrbufsiz;
		com.grey.base.utils.ByteChars tmplightbc = usess.uh.ms.tmplightbc;
		int rdtotal = 0;
		boolean in_headers = true;
		int off_buf = 0;
		while (in_headers) {
			if (off_buf == filebufsiz) {
				//sanity check - should never happen. Just return whatever we've got so far
				usess.uh.ms.dsptch.getLogger().info("Zero read at off="+strm.getFilePointer()+"/"+strm.length()+" - mailbox="+activeview.mbxname);
				break;
			}
			int nbytes = strm.read(filebuf, off_buf, filebufsiz - off_buf);
			if (nbytes == -1) break;
			rdtotal += nbytes;
			nbytes += off_buf; //include any pre-existing content at start of buffer
			int off_scan = 0;
			while (off_scan != nbytes) {
				// read in current header, bearing in mind that it may be folded
				boolean hdrtrunc = false;
				int off_nextline = off_scan;
				int linebreaks = 0;
				do {
					off_nextline = ByteOps.indexOf(filebuf, off_nextline, nbytes - off_nextline, (byte)'\n');
					if (off_nextline == -1 || off_nextline == nbytes - 1) {
						// if newline is last char in buffer, we can't step off_nextline past it - need to read on
						if (off_scan == 0 || strm.getFilePointer() == strm.length()) {
							//... unless we're at EOF or overflow, in which case we can consider this header line to be complete
							//off_scan zero means header is too large, so just drop the rest of it
							//EOF means the header is truncated
							hdrtrunc = true;
							off_nextline = nbytes;
						} else {
							off_nextline = -1;
						}
						break;
					}
					linebreaks++;
					off_nextline++; //point to start of next line
				} while (filebuf[off_nextline] == ' ' || filebuf[off_nextline] == '\t');
				if (off_nextline == -1) break;

				// Are headers finished? Because we've already scanned to next line, we know there's at least one LineFeed ahead of us
				if (filebuf[off_scan] == '\r' && filebuf[off_scan+1] == '\n') {
					in_headers = false;
				} else if (filebuf[off_scan] == '\n') {
					in_headers = false;
					len_separatorline = 1;
				}
				if (!in_headers) {
					int bodybytes = nbytes - off_scan - len_separatorline; //separator line is part of the header
					rdtotal -= bodybytes;
					if (outbuf != null) {
						//blank separator line always returned for IMAP, see RFC-3501 section 6.4.5 BODY[<section>]<<partial>>
						outbuf.append(filebuf, off_scan, len_separatorline);
					}
					break;
				}

				// check if current header needs to be returned
				if (outbuf != null || outmap != null) {
					int hdrlen = off_nextline - off_scan;
					int pos_dlm = ByteOps.indexOf(filebuf, off_scan, hdrlen, (byte)':');
					// there should always be a colon in a header line, but just ignore it if not
					if (pos_dlm != -1) {
						tmplightbc.set(filebuf, off_scan, pos_dlm - off_scan);
						String hdrname = null;
						if (hdrs != null) {
							for (int idx = 0; idx != hdrs.length; idx++) {
								if (StringOps.sameSeqNoCase(hdrs[idx], tmplightbc)) {
									hdrname = hdrs[idx];
									break;
								}
							}
						}
						if ((hdrname != null) ^ excl) {
							// Note that line-folding is merely an RFC-822 artifact -see [FWS] grammar production in RFC-822.
							// Therefore header lines should be unfolded before being sent to a client, and Subject is a
							// particularly important case in point, since it appears in the IMAP Envelope structure, where
							// line breaks could well be fatal.
							// It seems IMAP servers could get away without unfolding any other headers, but probably safest
							// to assume we should.
							// See http://mailman2.u.washington.edu/pipermail/imap-protocol/2010-July/001274.html
							// I think I've also seen Timo's ImapTest works better with unfolding on.
							if (linebreaks != 1 &&
									(UNFOLDALLHEADERS || StringOps.sameSeqNoCase(MimePart.HDR_SUBJECT, tmplightbc))) {
								int pos = off_scan;
								int lmt = 1;
								if (hdrtrunc && filebuf[pos+hdrlen-1] != '\n') lmt = 0; //last LF is in middle, so remove
								while (linebreaks > lmt) {
									//we know there's another LF between here and off_scan+hdrlen, so excess length arg is ok
									int pos2 = ByteOps.indexOf(filebuf, pos, hdrlen, (byte)'\n');
									if (pos2 == -1) break; //no LF, so message must have have been truncated on this header
									filebuf[pos2] = ' ';
									if (filebuf[pos2-1] == '\r') filebuf[pos2-1] = ' ';
									pos = pos2;
									linebreaks--;
								}
							}
							if (outbuf != null) {
								//do before outmap as that modifies header
								outbuf.append(filebuf, off_scan, hdrlen);
								if (hdrtrunc && filebuf[off_scan+hdrlen-1] != '\n') outbuf.append('\n'); //in case header truncated before EOL
							}
							if (outmap != null) {
								while (filebuf[++pos_dlm] == ' ');//strip leading spaces
								hdrlen--; //strip trailing LineFeed
								if (filebuf[off_scan+hdrlen-1] == '\r') hdrlen--; //... and the CarriagReturn
								hdrlen -= (pos_dlm - off_scan);
								if (hdrlen != 0) outmap.put(hdrname, new String(filebuf, pos_dlm, hdrlen));
							}
						}
					}
				}
				// move on to next line
				off_scan = off_nextline;
			}
			// shuffle incomplete line at end of read buffer up to the start
			int unscanned = nbytes - off_scan;
			System.arraycopy(filebuf, off_scan, filebuf, 0, unscanned);
			off_buf = unscanned;
		}
		return rdtotal;
	}

	private void parseMimePart(java.io.RandomAccessFile strm, MimePart mime_parent) throws java.io.IOException
	{
		com.grey.base.collections.HashedMap<String,String> hdrmap = usess.uh.ms.tmpmap;
		hdrmap.clear();
		strm.seek(mime_parent.file_off);
		final int hdrsiz = getHeaders(strm, MIME_HDRS, false, null, hdrmap);
		mime_parent.parseHeaders(hdrmap, mime_parent.ctype, mime_parent.subtype);
		mime_parent.bodysiz = mime_parent.totalsiz - hdrsiz;
		long body_off = mime_parent.file_off + hdrsiz;

		if (mime_parent.isNestedMessage()) {
			// NB: msgnode.linecnt omits subpart's header lines - does accuracy really matter here?
			MimePart msgnode = new MimePart(true);
			msgnode.file_off = body_off;
			msgnode.totalsiz = mime_parent.bodysiz;
			parseMimePart(strm, msgnode);
			mime_parent.setMessage(msgnode);
			return;
		}
		strm.seek(body_off);

		final byte[] filebuf = usess.uh.ms.getMessageBuffer();
		final byte[] mimebndry = usess.uh.ms.mimebndry;
		final int bndrylen = (mime_parent.bndry == null ? 0 : mime_parent.bndry.length() + 2);
		if (mime_parent.bndry != null) {
			int pfx = 0;
			mimebndry[pfx++] = '-';
			mimebndry[pfx++] = '-';
			for (int idx = 0; idx != bndrylen - 2; idx++) {
				mimebndry[idx+pfx] = (byte)mime_parent.bndry.charAt(idx);
			}
		}
		boolean end_of_parent = false;
		MimePart subpart = null;
		int rdtotal = 0;
		int off_buf = 0;

		while (!end_of_parent) {
			int nbytes;
			if (rdtotal >= mime_parent.bodysiz) break;
			final long file_off = mime_parent.file_off + hdrsiz + rdtotal - off_buf; //file offset of 1st byte in filebuf
			final int maxbuf = filebuf.length - off_buf;
			int bufsiz = mime_parent.bodysiz - rdtotal;
			if (bufsiz > maxbuf) bufsiz = maxbuf;
			if ((nbytes = strm.read(filebuf, off_buf, bufsiz)) == -1) break;
			rdtotal += nbytes;
			nbytes += off_buf; //bump nbytes up to total filebuf size (include previously read but unscanned bytes at start)
			int off_scan = 0;
			int off_eol;
			while ((off_eol = ByteOps.indexOf(filebuf, off_scan, nbytes - off_scan, (byte)'\n')) != -1) {
				mime_parent.linecnt++;
				final int off_line = off_scan; //start of current line (which we'll compare to boundary)
				final int len_line = off_eol - off_scan; //excludes LineFeed
				off_scan = off_eol + 1; //advance to start of next line (may turn out to be first line after boundary)
				if (bndrylen == 0 || len_line < bndrylen) continue;
				if (!ByteOps.cmp(filebuf, off_line, mimebndry, 0, bndrylen)) continue;
				//we are on a boundary line
				if (subpart != null) subpart.totalsiz = (int)(file_off - subpart.file_off) + off_line;
				if (filebuf[off_line+bndrylen] == '-' &&filebuf[off_line+bndrylen+1] == '-') {
					//it's the terminal boundary, ignore any epilogue;
					end_of_parent = true;
					subpart = null;
					break;
				}
				subpart = mime_parent.addChildPart();
				subpart.file_off = file_off + off_scan;
			}
			if (!end_of_parent) {
				// shuffle unscanned contents at end of buffer up to the start
				int unscanned = nbytes - off_scan;
				System.arraycopy(filebuf, off_scan, filebuf, 0, unscanned);
				off_buf = unscanned;
			}
		}

		if (subpart != null) {
			// message was truncated before we found end-marker of final bodypart
			subpart.totalsiz = (int)(strm.getFilePointer() - subpart.file_off);
		}
		for (int idx = 0; idx != mime_parent.childCount(); idx++) {
			parseMimePart(strm, mime_parent.getChild(idx));
		}
	}

	// Detect external changes to the mailbox, and then load new messages
	// Note that lastmodtime <= dh_cur.lastModified() would be a sufficient reliable test for updates, but
	// it would also give a few false positives in the split second after each change to the directory, as
	// well as the first updates check after our select. These false positives do not result in errors, but
	// merely in unnecessary directory scans.
	// Adding modcount into the mix allows us to effectively test only lastmodtime < dh_cur.lastModified()
	// and prevents us mistaking new message's we've loaded into dh_cur ourselves for an external change.
	// It will also save us doing a stat on dh_cur to get its timestamp, for our frequent no-expunge loads.
	public boolean loadUpdates(MailboxSession.UpdatesListener listener, Object argcb,
		int opts) throws java.io.IOException
	{
		if ((opts & MaildirStore.RPT_EXCL_NEW) != 0) opts |= MaildirStore.RPT_EXCL_RECENT;
		boolean partial = ((opts & (MaildirStore.RPT_EXCL_EXPUNGE | MaildirStore.RPT_EXCL_NEW)) != 0);
		boolean is_mod = (modcount != ustats.modcount);
		final long lastmod = (is_mod || !partial ? dh_cur.lastModified() : lastmodtime);
		if (!is_mod) is_mod = (lastmodtime != lastmod);
		boolean updates_hidden = false;

		if (is_mod) {
			MailboxView latestview = new MailboxView(usess.uh, activeview.mbxname, true, false);
			loadMessages(activeview.mbxname, latestview, false);
			StringBuilder flagsbuf = usess.uh.ms.sharedtmpsb;

			for (int idx_old = activeview.getMsgCount() - 1; idx_old >= 0; idx_old--) {
				final int seqnum_old = idx_old+1;
				final int uid = activeview.getMessageUID(seqnum_old);
				final int seqnum_new = latestview.getMessageSequence(uid);

				if (seqnum_new == 0) {
					// this message no longer exists
					if ((opts & MaildirStore.RPT_EXCL_EXPUNGE) != 0) {
						//but we can't report its deletion just yet
						updates_hidden = true;
					} else {
						//safe to remove this element, because we're looping on array from end to start
						messageDeleted(seqnum_old, listener, argcb);
					}
					continue;
				}
				final String msgfile_new = latestview.getMessage(seqnum_new).filename;
				final String msgfile_old = activeview.getMessage(seqnum_old).filename;
				latestview.removeMessage(seqnum_new); //not a new message
				if (msgfile_old.equals(msgfile_new)) continue; //completely unchanged

				//the flags have been modified externally, so update our records
				activeview.updateFlags(seqnum_old, msgfile_new, -2);
				if (listener != null) {
					flagsbuf.setLength(0);
					activeview.getMessageFlags(seqnum_old, flagsbuf);
					listener.reportMessageFlags(seqnum_old, flagsbuf, true, argcb);
				}
			}

			// Only new messages now remain in latestview.
			// They must have been loaded by another session, so add them to the live list now.
			int msgcnt_new = latestview.getMsgCount();
			if (msgcnt_new != 0 && (opts & MaildirStore.RPT_EXCL_NEW) != 0) {
				msgcnt_new = 0;
				updates_hidden = true;
			}
			for (int idx = 0; idx != msgcnt_new; idx++) {
				String msgfile = latestview.getMessage(idx+1).filename;
				activeview.loadMessage(msgfile, false, 0);
			}
			if (!updates_hidden) {
				lastmodtime = lastmod;
				modcount = ustats.modcount;
			}
		}
		if (!activeview.rdonly && (opts & MaildirStore.RPT_EXCL_RECENT) == 0) loadNewMessages();
		return updates_hidden;
	}

	// we guarantee that this is only called with load_new=true for view=activeview
	private void loadMessages(String mbxname, MailboxView view, boolean load_new) throws java.io.IOException
	{
		String[] filenames = dh_cur.list(msgFilter);
		int cnt = (filenames == null ? 0 : filenames.length);
		if (cnt != 0) java.util.Arrays.sort(filenames, msgSorter);

		for (int idx = 0; idx != cnt; idx++) {
			view.loadMessage(filenames[idx], false, 0);
		}
		if (load_new) loadNewMessages(); //these will be added in sorted order
	}

	private void loadNewMessages() throws java.io.IOException
	{
		if (dh_new == null) dh_new = new java.io.File(dh_root, MaildirStore.MDIR_NEW);
		String[] srcnames = dh_new.list(); //don't filter here, as allocMessage does that
		int cnt = (srcnames == null ? 0 : srcnames.length);
		if (cnt == 0) return;

		java.util.Arrays.sort(srcnames); //this ranks them by age
		FileOps.ensureDirExists(dh_cur);

		for (int idx = 0; idx != cnt; idx++) {
			final boolean preserve = srcnames[idx].contains(MaildirStore.UNSTUFFED_MARKER);
			final boolean unstuffed = (preserve || usess.uh.ms.dotstuffing); //already unstuffed?
			java.io.File fh_src = new java.io.File(dh_new, srcnames[idx]);
			String dstname = allocMessage(fh_src, preserve, preserve || usess.uh.ms.mailismus_delivery, !unstuffed);
			if (dstname == null) continue; //invalid filename format - discarded
			if (!unstuffed) {
				// unstuff into another tmp filename, in case subsequent file ops fail
				if (dh_tmp == null) dh_tmp = new java.io.File(dh_root, MaildirStore.MDIR_TMP);
				final java.io.File fh_tmp = new java.io.File(dh_tmp, dstname);
				usess.uh.ms.transferMessage(fh_src, fh_tmp, true);
				try {
					FileOps.deleteFile(fh_src);
				} catch (Exception ex) {
					usess.uh.ms.dsptch.getLogger().info("Maildir: Failed to remove original message="+fh_src.getAbsolutePath()+" - "+ex);
					try {FileOps.deleteFile(fh_tmp);} catch (Exception ex2) { //make best effort to remove
						usess.uh.ms.dsptch.getLogger().trace("Maildir: Failed to remove dest message="+fh_tmp.getAbsolutePath()+" - "+ex2);
					}
					continue;
				}
				usess.uh.ms.setFilePermissions(usess.getUsername(), fh_tmp, null);
				//we didn't know final size till now, with the dotstuffing undone
				int pos = dstname.indexOf(TOKEN_SIZE);
				StringBuilder sb = usess.uh.ms.sharedtmpsb;
				sb.setLength(0);
				sb.append(dstname, 0, pos).append(fh_tmp.length()).append(dstname, pos+1, dstname.length());
				dstname = sb.toString();
				fh_src = fh_tmp;
			}
			final java.io.File fh_dst = new java.io.File(dh_cur, dstname);
			if (!fh_src.renameTo(fh_dst)) {
				throw new java.io.IOException("Maildir: Failed to load new="+fh_src.getAbsolutePath()+" as "+fh_dst.getAbsolutePath());
			}
			activeview.loadMessage(dstname, true, ustats.uidnext-1);
			if (usess.uh.ms.dsptch.getLogger().isActive(MSGTRC)) {
				usess.uh.ms.dsptch.getLogger().log(MSGTRC, "Mailbox="+activeview.mbxname
						+" received msg="+activeview.getMsgCount()+" - "+dstname);
			}
		}
		indicateModified();
		syncMetaData(activeview.mbxname);
	}

	// The UID is embedded in the filename, so constructing the filename allocates everything we need
	// for a new message.
	private String allocMessage(java.io.File fh_src, boolean preserve_flags, boolean trust_timestamp,
			boolean unknown_size) throws java.io.IOException
	{
		StringBuilder sb = usess.uh.ms.sharedtmpsb;

		// parse the parts of the source-file name
		String srcname = fh_src.getName();
		int pos1 = srcname.indexOf('.');
		int pos2 = (pos1 == -1 ? -1 : srcname.indexOf('.', pos1+1));
		if (pos1 == 0 || pos1 == -1 || pos2 == -1) {
			discardMessage(fh_src);
			return null;
		}
		int lmt = (preserve_flags ? -1 : srcname.indexOf(usess.uh.ms.FLAGS_MARKER));
		if (lmt == -1) lmt = srcname.length();
		int uid = ustats.uidnext++;

		// generate the new name: timestamp.uid_size.rest
		sb.setLength(0);
		if (trust_timestamp) {
			sb.append(srcname, 0, pos1+1);
		} else {
			sb.append(fh_src.lastModified()).append('.');
		}
		sb.append(uid).append('_');
		if (unknown_size) {
			sb.append(TOKEN_SIZE);
		} else {
			sb.append(fh_src.length());
		}
		sb.append(srcname, pos2, lmt);
		return sb.toString();
	}

	// Ignore rename failure, as message may have been externally deleted
	private void markMessageSeen(int seqnum, java.io.File fh)
	{
		if (activeview.hasFlag(seqnum, MaildirStore.MSGFLAG_SEEN)) return;
		String filename = activeview.getMessage(seqnum).filename;
		StringBuilder filename_new = usess.uh.ms.sharedtmpsb;
		filename_new.setLength(0);
		filename_new.append(filename);
		if (filename.indexOf(usess.uh.ms.FLAGS_MARKER) == -1) filename_new.append(usess.uh.ms.FLAGS_MARKER);
		filename_new.append(MaildirStore.MSGFLAG_SEEN);
		updateMessage(seqnum, filename_new.toString(), fh, 1);
	}

	// Message flags are embedded in its filename, so updates are implemented as a file rename
	private boolean updateMessage(int seqnum, String newname, java.io.File fh_old, int seen_delta)
	{
		boolean renamed = false;
		if (!newname.equals(fh_old.getName())) {
			java.io.File fh_new = new java.io.File(dh_cur, newname);
			if (!fh_old.renameTo(fh_new)) {
				if (fh_old.exists()) usess.uh.ms.dsptch.getLogger().warn("Mailbox="+activeview.mbxname
						+": Failed to update message="+seqnum+": "+fh_old.getName()+" => "+fh_new.getName());
				return false;
			}
			renamed = true;
			indicateModified();
		}
		activeview.updateFlags(seqnum, newname, seen_delta);

		if (renamed && usess.uh.ms.dsptch.getLogger().isActive(MSGTRC)) {
			usess.uh.ms.dsptch.getLogger().log(MSGTRC, "Mailbox="+activeview.mbxname
					+" updated msg="+seqnum+"/"+activeview.getMsgCount()+" - "+fh_old.getName()+" => "+newname);
		}
		return true;
	}

	private void messageDeleted(int seqnum, MailboxSession.UpdatesListener listener, Object argcb) throws java.io.IOException
	{
		activeview.removeMessage(seqnum);
		if (listener != null) listener.reportExpunge(seqnum, argcb);
	}

	private java.io.RandomAccessFile openMessageFile(java.io.File fh) throws java.io.IOException
	{
		java.io.RandomAccessFile strm = null;
		try {
			strm = new java.io.RandomAccessFile(fh, "r");
		} catch (java.io.IOException ex) {
			if (!fh.exists()) return null; //somebody else has removed this file
			throw ex; //genuine error
		}
		return strm;
	}

	// this ia a rare and erroneous occurrence, so there is no performance imperative
	private void discardMessage(java.io.File fh_msg) throws java.io.IOException
	{
		usess.uh.ms.dsptch.getLogger().info("Maildir: Discarding invalid message - "+fh_msg.getAbsolutePath());
		String filename = fh_msg.getName();
		if (dh_tmp == null) dh_tmp = new java.io.File(dh_root, MaildirStore.MDIR_TMP);
		java.io.File dh_discard = new java.io.File(dh_tmp, "discard");
		java.io.File fh_discard = new java.io.File(dh_discard, filename);
		int failcnt = 0;
		while (!fh_msg.renameTo(fh_discard)) {
			if (failcnt++ == 0) {
				//failure might have been caused by missing directory, so allow that
				if (!dh_discard.exists()) {
					FileOps.ensureDirExists(dh_discard);
					continue;
				}
			}
			if (fh_msg.exists() && !fh_discard.exists()) {
				if (failcnt == 10) {
					//Same logic as check in MaildirStore.deliver() rename() loop - can't be race condition.
					//Failure could be due to external reasons, and doesn't really matter in this case anyway.
					usess.uh.ms.dsptch.getLogger().info("Maildir: Failed to discard "+fh_msg.getAbsolutePath()+" to "+fh_discard.getAbsolutePath());
					break;
				}
			}
			fh_discard = new java.io.File(dh_discard, filename+"_"+(++failcnt));
		}
	}

	private boolean getMetaData(String mbxname) throws java.io.IOException
	{
		if (ustats.uidvalidity == 0) {
			// first session on this mailbox has to read in the metadata
			byte[] uidbuf = usess.uh.ms.mimebndry; //mimebndry is more than big enough for our needs
			java.io.FileInputStream strm = null;
			try {
				strm = new java.io.FileInputStream(ustats.fh_meta);
				int nbytes = strm.read(uidbuf, 0, UIDMETABUF);
				if (nbytes != UIDMETABUF) {
					throw new java.io.IOException("Maildir: read="+nbytes+"/"+UIDMETABUF+" on "+mbxname);
				}
			} catch (java.io.FileNotFoundException ex) {
				ustats.uidvalidity = (int)(System.currentTimeMillis() / 1000);
				ustats.uidnext = 1;
				return false;
			} finally {
				if (strm != null) strm.close();
			}
			ustats.uidvalidity = ByteOps.decodeInt(uidbuf, 0, ByteOps.INTBYTES);
			ustats.uidnext = ByteOps.decodeInt(uidbuf, ByteOps.INTBYTES, ByteOps.INTBYTES);
		}
		return true;
	}

	private void syncMetaData(String mbxname) throws java.io.IOException
	{
		byte[] uidbuf = usess.uh.ms.mimebndry; //mimebndry is more than big enough for our needs
		java.io.FileOutputStream strm = null;
		try {
			strm = new java.io.FileOutputStream(ustats.fh_meta);
		} catch (Exception ex) {
			// Ensure failure wasn't due to missing meta directory. A second failure is genuine.
			FileOps.ensureDirExists(ustats.fh_meta.getParentFile());
			strm = new java.io.FileOutputStream(ustats.fh_meta);
		}
		ByteOps.encodeInt(ustats.uidvalidity, uidbuf, 0, ByteOps.INTBYTES);
		ByteOps.encodeInt(ustats.uidnext, uidbuf, ByteOps.INTBYTES, ByteOps.INTBYTES);
		try {
			strm.write(uidbuf, 0, UIDMETABUF);
		} finally {
			strm.close();
		}
	}

	private void indicateModified()
	{
		ustats.modcount++;
		modcount++;
	}


	// This filters out filenams that don't correspond to the Maildir format for a message
	private static final class MessageFilter
		implements java.io.FilenameFilter
	{
		MessageFilter() {} //make explicit with non-private access, to eliminate synthetic accessor
		@Override
		public boolean accept(java.io.File dirh, String filename) {
			//Verify basic Maildir format first: timestamp.middle.suffix
    		int dot1 = filename.indexOf('.');
    		int dot2 = (dot1 == filename.length() - 1 ? -1 : filename.indexOf('.', dot1+1));
    		if (dot1 == -1 || dot1 == 0 || dot2 == -1) return false;
    		//Now do IMAP-specific format checks: timestamp.uid_size.suffix
    		int uidmark = filename.indexOf('_', dot1+1);
    		if (uidmark == -1 || uidmark > dot2 || uidmark == dot1+1 || dot2 == uidmark+1) return false;
    		for (int idx = 0; idx != dot1; idx++) if (!Character.isDigit(filename.charAt(idx))) return false;
    		for (int idx = dot1+1; idx != uidmark; idx++) if (!Character.isDigit(filename.charAt(idx))) return false;
    		for (int idx = uidmark+1; idx != dot2; idx++) if (!Character.isDigit(filename.charAt(idx))) return false;
    		return true;
		}
	}


	// IMAP requires messages to be sorted by ascending UID - see RFC-3051 section 2.3.1.2
	// The message filenames passed in here are guaranteed to be in the format we expect, with an
	// embedded UID (the MessageFilter class ensures that).
    private static final class MessageSorter implements java.util.Comparator<String>, java.io.Serializable
    {
    	MessageSorter() {} //make explicit with non-private access, to eliminate synthetic accessor
		private static final long serialVersionUID = 1L;
		@Override
        public int compare(String s1, String s2) {
    		int off1 = s1.indexOf('.');
    		int lmt1 = s1.indexOf('_', off1);
    		int len1 = lmt1 - off1;
    		int off2 = s2.indexOf('.');
    		int lmt2 = s2.indexOf('_', off2);
    		int len2 = lmt2 - off2;
    		if (len1 != len2) return len1 - len2;
    		while (off1 != lmt1) {
    			char ch1 = s1.charAt(off1++);
    			char ch2 = s2.charAt(off2++);
    			if (ch1 != ch2) return ch1 - ch2;
    		}
            return 0;
        }
    }
}
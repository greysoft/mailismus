/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.utils.StringOps;

public final class MailboxView
{
	static final class Message
	{
		public String filename; //filename within MDIR_CUR
		public final boolean recent;
		Message(String f, boolean r) {filename=f; recent=r;}
	}

	public final String mbxname;
	public final boolean rdonly;

	private final java.util.ArrayList<Message> msglst = new java.util.ArrayList<Message>();
	private final com.grey.base.collections.HashedMapIntInt uidmap = new com.grey.base.collections.HashedMapIntInt();
	private final MaildirStore ms;
	private final MailboxUser.MailboxStats ustats;

	private int recent_cnt;
	private int seen_cnt;
	private int unseen_seq; //initial sequence number (from 1) of 1st unseen msg - zero means all seen
	private int pending_cnt; //represents extra messages not stored on msglst

	public int getMsgCount() {return msglst.size() + pending_cnt;}
	public int getRecentCount() {return recent_cnt + pending_cnt;}
	public int getSeenCount() {return seen_cnt;}
	public int getFirstUnseen() {return unseen_seq;}
	public int getUIDValidity() {return ustats.uidvalidity;}
	public int getNextUID() {return ustats.uidnext;}

	public int getMessageSequence(int uid) {return uidmap.get(uid);}
	public int getMessageUID(int seqnum) {return parseUID(getMessage(seqnum).filename);}
	public boolean hasFlag(int seqnum, char flag) {return hasFlag(getMessage(seqnum).filename, flag);}

	Message getMessage(int seqnum) {return msglst.get(seqnum - 1);}
	void setPending(int cnt) {pending_cnt = cnt;}

	MailboxView(MailboxUser uh, String mbx, boolean rd, boolean full_view)
	{
		if (full_view) {
			ustats = uh.getStats(mbx);
		} else {
			ustats = null;
		}
		mbxname = mbx;
		ms = uh.ms;
		rdonly = rd;
	}

	public int getMessageSize(int seqnum)
	{
		final String filename = getMessage(seqnum).filename;
		int pos1 = filename.indexOf('.');
		pos1 = filename.indexOf('_', pos1+1);
		int pos2 = filename.indexOf('.', pos1+1);
		return (int)StringOps.parseNumber(filename, pos1+1, pos2-pos1-1, 10);
	}

	public long getMessageTime(int seqnum)
	{
		final String filename = getMessage(seqnum).filename;
		int pos = filename.indexOf('.');
		return StringOps.parseNumber(filename, 0, pos, 10);
	}

	public void getMessageFlags(int seqnum, StringBuilder flagsbuf)
	{
		final Message msg = getMessage(seqnum);
		if (msg.recent) flagsbuf.append(MaildirStore.MSGFLAG_RECENT);
		int pos = msg.filename.indexOf(ms.FLAGS_MARKER);
		if (pos == -1) return;
		pos += ms.FLAGS_MARKER.length();
		flagsbuf.append(msg.filename, pos, msg.filename.length());
	}

	void loadMessage(String filename, boolean recent, int uid)
	{
		msglst.add(new Message(filename, recent));
		if (uid == 0) uid = parseUID(filename);
		int seqnum = msglst.size();
		uidmap.put(uid, seqnum);
		if (ustats == null) return;

		if (loadMessageFlags(filename)) {
			if (unseen_seq == 0) unseen_seq = seqnum;
		}
		if (recent) recent_cnt++;
	}

	void removeMessage(int seqnum)
	{
		final Message msg = getMessage(seqnum);
		final int uid = parseUID(msg.filename);
		msglst.remove(seqnum - 1);
		uidmap.remove(uid);

		//update all affected UID-map entries, to reflect the shuffling up of subsequent list entries
		com.grey.base.collections.IteratorInt it = uidmap.recycledKeysIterator();
		while (it.hasNext()) {
			int u = it.next();
			int s = uidmap.get(u);
			if (s > seqnum) uidmap.put(u, s-1);
		}

		if (ustats != null) {
			if (hasFlag(msg.filename, MaildirStore.MSGFLAG_SEEN)) seen_cnt--;
			if (msg.recent) recent_cnt--;
		}
	}

	void updateFlags(int seqnum, String new_filename, int seen_delta)
	{
		if (seen_delta == -2) {
			seen_delta = hasFlag(seqnum, MaildirStore.MSGFLAG_SEEN) ? -1 : 0;
			if (hasFlag(new_filename, MaildirStore.MSGFLAG_SEEN)) seen_delta++;
		}
		getMessage(seqnum).filename = new_filename;
		seen_cnt += seen_delta;
	}

	private boolean hasFlag(String filename, char flag)
	{
		int pos = filename.indexOf(ms.FLAGS_MARKER);
		if (pos != -1) pos = filename.indexOf(flag, pos);
		return (pos != -1);
	}

	private boolean loadMessageFlags(String msgfile)
	{
		int pos = msgfile.indexOf(ms.FLAGS_MARKER);
		boolean unseen = (msgfile.indexOf(MaildirStore.MSGFLAG_SEEN, pos) == -1);
		if (!unseen) seen_cnt++;
		return unseen;
	}

	private static int parseUID(String msgfile)
	{
		//these chars are guaranteed to exist, as we validated filename during load
		int pos1 = msgfile.indexOf('.');
		int pos2 = msgfile.indexOf('_', pos1+1);
		return (int)StringOps.parseNumber(msgfile, pos1+1, pos2-pos1-1, 10);
	}
}
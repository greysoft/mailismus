/*
 * Copyright 2013-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.utils.FileOps;

// There is only one instance of this class per active user, no matter how many
// connections they are logged in as.
final class MailboxUser
{
	// There is only one instance of this per open mailbox, no matter how many
	// connections this user has on that mailbox.
	public static final class MailboxStats
	{
		public final java.io.File fh_meta;
		public int uidvalidity;
		public int uidnext;
		public int modcount;
		MailboxStats(java.io.File fh) {fh_meta = fh;}
	}

	private static final String DIRPATH_META = "_mailismus_meta";
	private static final MailboxFilter mbxFilter = new MailboxFilter();
	private static final String RGX_DLM_HIERARCHY = "\\"+String.valueOf(MaildirStore.DLM_HIERARCHY);

	private final java.util.HashMap<String, MailboxStats> mbxstats
							= new java.util.HashMap<String, MailboxStats>();
	private final com.grey.base.collections.HashedMap<String,String> mbxCaseMap
							= new com.grey.base.collections.HashedMap<String,String>();
	private final MailboxSorter mbxSorter = new MailboxSorter(mbxCaseMap);

	public final MaildirStore ms;
	public final String username;
	public String[] mailboxes; //case-insensitively sorted list of all the mailbox names

	private final java.io.File dh_root; //root path of user's Maildir store
	private final java.io.File dh_meta; //user's Mailismus-specific metadata directory
	private int session_cnt;

	public String existsMailbox(CharSequence mbxname) {return existsCanonMailbox(makeMailboxName(mbxname, ms.sharedtmpsb));}

	// mailbox name has to be in canonical form, ie. fully qualified, beginning with DLM_HIERARCHY
	public int sessionCount() {return session_cnt;}
	public void loggedOn() {session_cnt++;}
	public void loggedOff() {session_cnt--; ms.mailboxSessionEnded(this);}
	private String existsCanonMailbox(CharSequence mbxname) {return mbxCaseMap.get(mbxname.toString().toLowerCase());}

	public MailboxUser(com.grey.mailismus.ms.MessageStore m, String u)
	{
		ms = (MaildirStore)m;
		username = u;
		dh_root = ms.getDropDir(username).getParentFile();
		dh_meta = new java.io.File(dh_root, DIRPATH_META);
		getMailboxes();
	}

	// We create mailboxes in the specified case, but in ensuring their uniqueness, we first make
	// sure they don't differ from any existing ones in case only.
	public boolean createMailbox(CharSequence mbxname)
	{
		StringBuilder sb = ms.sharedtmpsb;
		String[] mbxchain = makeMailboxName(mbxname, sb).toString().split(RGX_DLM_HIERARCHY);
		sb.setLength(0);
		int finalmbx = -1;
		for (int idx = 1; idx != mbxchain.length; idx++) { //start at 1 because leading dot means blank@idx=0
			sb.append(MaildirStore.DLM_HIERARCHY).append(mbxchain[idx]);
			String name = sb.toString();
			if (existsCanonMailbox(name) != null) continue;
			java.io.File dh = new java.io.File(dh_root, name);
			java.io.File dh_cur = new java.io.File(dh, MaildirStore.MDIR_CUR);
			java.io.File dh_new = new java.io.File(dh, MaildirStore.MDIR_NEW);
			java.io.File dh_tmp = new java.io.File(dh, MaildirStore.MDIR_TMP);
			if (!dh_cur.mkdirs() || !dh_new.mkdirs() || !dh_tmp.mkdirs()) return false;
			finalmbx = idx;
		}
		if (finalmbx != -1) getMailboxes();
		return (finalmbx == mbxchain.length - 1); //false if leaf mailbox already existed
	}

	// Would any clients that have this mailbox open expect to see Expunge responses for its messages?
	public boolean deleteMailbox(CharSequence mbxname) throws java.io.IOException
	{
		mbxname = existsMailbox(mbxname);
		if (mbxname == null) return false;

		java.io.File dh = new java.io.File(dh_root, mbxname.toString());
		getMailboxMeta(mbxname.toString()).delete();

		boolean has_inferiors = false;
		for (int idx = 0; idx != mailboxes.length; idx++) {
			if (mailboxes[idx].startsWith(mbxname.toString())) {
				if (mailboxes[idx].length() == mbxname.length()) continue; //it's matched itself, ignore
				has_inferiors = true;
				break;
			}
		}

		if (has_inferiors) {
			//we merely remove its messages - simplest way is to remove and recreate the main directory
			dh = new java.io.File(dh, MaildirStore.MDIR_CUR);
			FileOps.deleteDirectory(dh);
			if (!dh.mkdirs()) return false;
		} else {
			FileOps.deleteDirectory(dh);
			if (dh.exists()) return false;
			getMailboxes();
		}
		return true;
	}

	// This method ensures we don't create any mailboxes that differ from existing ones only
	// in case.
	public int renameMailbox(CharSequence srcname, CharSequence dstname)
	{
		StringBuilder sb = ms.sharedtmpsb;
		srcname = existsMailbox(srcname);
		dstname = makeMailboxName(dstname, sb);
		if (srcname == null || existsCanonMailbox(dstname) != null) return 0;

		int rootlen = sb.length();
		int cnt = 0;
		for (int idx = 0; idx != mailboxes.length; idx++) {
			if (!mailboxes[idx].startsWith(srcname.toString())) {
				if (cnt != 0) break; //we've advanced past all the matches
				continue;
			}
			sb.setLength(rootlen);
			if (mailboxes[idx].length() != srcname.length()) {
				if (mailboxes[idx].charAt(srcname.length()) != MaildirStore.DLM_HIERARCHY) {
					continue; //incidental match on a partial name
				}
				sb.append(mailboxes[idx], srcname.length(), mailboxes[idx].length());
			}
			java.io.File fhsrc = new java.io.File(dh_root, mailboxes[idx]);
			java.io.File fhdst = new java.io.File(dh_root, sb.toString());
			if (!fhsrc.renameTo(fhdst)) return -1;
			getMailboxMeta(mailboxes[idx]).delete();
			cnt++;
		}
		if (cnt != 0) getMailboxes();
		return cnt;
	}

	public java.io.File getMailboxDir(CharSequence mbxname)
	{
		java.io.File dh_top = null;
		if (MaildirStore.isInbox(mbxname)) {
			dh_top = dh_root;
		} else {
			mbxname = existsMailbox(mbxname);
			if (mbxname != null) dh_top = new java.io.File(dh_root, mbxname.toString());
		}
		return dh_top;
	}

	public MailboxStats getStats(String mbxname)
	{
		MailboxStats s = mbxstats.get(mbxname);
		if (s == null) {
			s = new MailboxStats(getMailboxMeta(mbxname));
			mbxstats.put(mbxname, s);
		}
		return s;
	}

	private java.io.File getMailboxMeta(String mbxname)
	{
		String filename = (MaildirStore.isInbox(mbxname) ? "INBOX" : mbxname);
		return new java.io.File(dh_meta, filename);
	}

	private void getMailboxes()
	{
		mbxCaseMap.clear();
		String[] mboxes = dh_root.list(mbxFilter);
		if (mboxes == null) mboxes = new String[0];
		if (mboxes.length == 1) {
			//sort would be a no-op, so mbxCaseMap wouldn't get populated
        	mbxCaseMap.put(mboxes[0].toLowerCase(), mboxes[0]);
		} else {
			java.util.Arrays.sort(mboxes, mbxSorter);
		}
		mailboxes = mboxes;
	}

	private StringBuilder makeMailboxName(CharSequence mbxname, StringBuilder sb)
	{
		sb.setLength(0);
		int len = mbxname.length();
		if (mbxname.charAt(len -1) == MaildirStore.DLM_HIERARCHY) len--;
		if (mbxname.charAt(0) != MaildirStore.DLM_HIERARCHY) sb.append(MaildirStore.DLM_HIERARCHY);
		sb.append(mbxname, 0, len);
		return sb;
	}


    private static final class MailboxSorter implements java.util.Comparator<String>
    {
		private final com.grey.base.collections.HashedMap<String,String> mboxes;
		public MailboxSorter(com.grey.base.collections.HashedMap<String,String> m) {
			mboxes = m;
		}
    	@Override
        public int compare(String s1, String s2) {
        	String s1b = s1.toLowerCase();
        	String s2b = s2.toLowerCase();
        	mboxes.put(s1b, s1);
        	mboxes.put(s2b, s2);
            return s1b.compareTo(s2b);
        }
    }


	private static final class MailboxFilter
		implements java.io.FilenameFilter
	{
		MailboxFilter() {} //make explicit with non-private access, to eliminate synthetic accessor
		@Override
		public boolean accept(java.io.File dirh, String filename) {
			return filename.charAt(0) == MaildirStore.DLM_HIERARCHY;
		}
	}
}
/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.base.utils.FileOps;
import com.grey.mailismus.imap.IMAP4Protocol;
import com.grey.mailismus.ms.maildir.MaildirStore;

/*
 * Maintain mapping of supported message flags.
 * RFC-5788 (ISODE, Mar 2010) defines a Keywork Registry, which can be found here:
 * 		http://www.iana.org/assignments/imap-keywords/imap-keywords.xml
 * Note that it does not encompass the commonly used $label ones, or Thunderbird's Junk/NonJunk.
 *
 * We would rarely expect to see any new IMAP keywords once the system has dealt with the initial few connections, so
 * efficiency and memory churn are not a concern when allocating a new one.
 */
final class MessageFlags
	implements com.grey.base.utils.FileOps.LineReader
{
	public static final char NOFLAG = 0;

	private final com.grey.base.collections.HashedMapIntKey<String> byMS = new com.grey.base.collections.HashedMapIntKey<String>();
	private final com.grey.base.collections.HashedMapIntValue<String> byIMAP = new com.grey.base.collections.HashedMapIntValue<String>();
	private final com.grey.base.collections.HashedMapIntKey<String> reservedFlags = new com.grey.base.collections.HashedMapIntKey<String>();
	private final java.util.HashMap<String, String> canonicalFlags = new java.util.HashMap<String, String>();
	private final java.io.File fhmap;
	private final boolean dynkwords;
	private final char[] msflags_srchpath;

	private String responseFlags;
	private String responsePermFlags;

	public String getMapFile() {return fhmap.getAbsolutePath();}
	public boolean permitDynamic() {return dynkwords;}
	public String getResponseFlags() {return responseFlags;}
	public String getResponsePermFlags() {return responsePermFlags;}
	public String getFlagIMAP(char msflag) {return byMS.get(msflag);}
	public int getNumFlags() {return byMS.size();}

	public MessageFlags(String pthnam, boolean dynkw) throws java.io.IOException
	{
		dynkwords = dynkw;
		fhmap = new java.io.File(pthnam);
		FileOps.ensureDirExists(fhmap.getParentFile()); //check we have permission, before going any further

		// define the hard-coded mappings of standard Maildir flags to IMAP system flags
		reservedFlags.put(MaildirStore.MSGFLAG_DRAFT, IMAP4Protocol.MSGFLAG_DRAFT);
		reservedFlags.put(MaildirStore.MSGFLAG_FLAGGED, IMAP4Protocol.MSGFLAG_FLAGGED);
		reservedFlags.put(MaildirStore.MSGFLAG_REPLIED, IMAP4Protocol.MSGFLAG_ANSWERED);
		reservedFlags.put(MaildirStore.MSGFLAG_SEEN, IMAP4Protocol.MSGFLAG_SEEN);
		reservedFlags.put(MaildirStore.MSGFLAG_DEL, IMAP4Protocol.MSGFLAG_DEL);
		reservedFlags.put(MaildirStore.MSGFLAG_RECENT, IMAP4Protocol.MSGFLAG_RECENT);

		// define the set of valid Maildir flags to assign, and their hunt path
		char[][] alloc_order = {{'a', 'z'}, {'0', '9'}, {'A', 'Z'}};
		java.util.ArrayList<Character> lst = new java.util.ArrayList<Character>();
		for (int idx = 0; idx != alloc_order.length; idx++) {
			char min = alloc_order[idx][0];
			char max = alloc_order[idx][1];
			for (char ch = min; ch <= max; ch++) {
				if (!reservedFlags.containsKey(ch)) lst.add(Character.valueOf(ch));
			}
		}
		msflags_srchpath = new char[lst.size()];
		for (int idx = 0; idx != lst.size(); idx++) msflags_srchpath[idx] = lst.get(idx).charValue();

		// load the mappings
		loadMap();
	}

	public char getFlagMS(String imapflag, boolean create) throws java.io.IOException
	{
		char msflag = (char)byIMAP.get(imapflag);
		if (msflag == NOFLAG) msflag = (char)byIMAP.get(canonicalFlags.get(imapflag.toLowerCase()));
		if (msflag == NOFLAG && create) msflag = allocKeyword(imapflag);
		return msflag;
	}

	public char getFlagMS(CharSequence imapflag, int off, int len, boolean create) throws java.io.IOException
	{
		return getFlagMS(imapflag.subSequence(off, off+len).toString(), create);
	}

	private void loadMap() throws java.io.IOException
	{
		// start with the reserved mappings - these cannot be overridden
		com.grey.base.collections.IteratorInt it = reservedFlags.recycledKeysIterator();
		while (it.hasNext()) {
			char msflag = (char)it.next();
			addMapping(msflag, reservedFlags.get(msflag));
		}
		// now load any user-defined IMAP flags (aka IMAP keywords)
		if (fhmap.exists()) FileOps.readTextLines(fhmap, this, 1024, null, 0, null);
		updatedMappings(false);
	}

	private void updatedMappings(boolean sync) throws java.io.IOException
	{
		char[] msflags = getMSFlags();
		if (sync) syncMappings(msflags);
		buildResponses(msflags);
	}

	private void syncMappings(char[] msflags) throws java.io.IOException
	{
		StringBuilder sb = new StringBuilder(100);
		for (int idx = 0; idx != msflags.length; idx++) {
			char msflag = msflags[idx];
			if (reservedFlags.containsKey(msflag)) continue;
			String imapflag = byMS.get(msflag);
			sb.append(msflag).append(' ').append(imapflag).append('\n');
		}
		FileOps.ensureDirExists(fhmap.getParentFile());
		FileOps.writeTextFile(fhmap, sb.toString(), false);
	}

	private void buildResponses(char[] msflags)
	{
		StringBuilder sb = new StringBuilder(100);
		String dlm = "";
		for (int idx = 0; idx != msflags.length; idx++) {
			char msflag = msflags[idx];
			if (msflag == MaildirStore.MSGFLAG_RECENT) continue; //excluded according to RFC-3501 ABNF
			String imapflag = byMS.get(msflag);
			sb.append(dlm).append(imapflag);
			dlm = " ";
		}
		responseFlags = sb.toString();
		if (dynkwords) sb.append(" \\*");
		responsePermFlags = sb.toString();
	}

	private char allocKeyword(String imapflag) throws java.io.IOException
	{
		char msflag = NOFLAG;
		if (!dynkwords || !validateKeyword(imapflag)) return msflag;
		for (int idx = 0; idx != msflags_srchpath.length; idx++) {
			if (!byMS.containsKey(msflags_srchpath[idx])) {
				msflag = msflags_srchpath[idx];
				addMapping(msflag, imapflag);
				updatedMappings(true);
				break;
			}
		}
		return msflag;
	}

	@Override
	public boolean processLine(String line, int lno, int mode, Object cbdata) throws java.io.IOException
	{
		// Minimum valid length is the  Maildir flag (always single-char), followed by space, followed by a single-char IMAP flag.
		// Ignore invalid lines
		line = line.trim();
		if (line.length() < 3 || line.charAt(1) > ' ') return false;

		// validate the MS flag
		char msflag = line.charAt(0);
		if (byMS.containsKey(msflag)) return false; //duplicate
		if (!validateMSFlag(msflag)) return false;

		// validate the IMAP flag
		String imapflag = line.substring(2).trim();
		if (byIMAP.containsKey(imapflag)) return false; //duplicate
		if (canonicalFlags.containsKey(imapflag.toLowerCase())) return false; //duplicate
		if (!validateKeyword(imapflag)) return false;

		addMapping(msflag, imapflag);
		return false;
	}

	// Validate the IMAP flag - see atom-specials ABNF in RFC-3501.
	// Note that backslash is forbidden, which ensures that the namespace of system flags remains reserved.
	private boolean validateKeyword(String imapflag)
	{
		int len = imapflag.length();
		if (len == 0) return false;
		for (int idx = 0; idx != len; idx++) {
			char ch = imapflag.charAt(idx);
			if (ch <= ' ' || ch >= 126) return false;
			if (ch == '(' || ch == ')' || ch == '{' || ch == '}'  || ch == '[' || ch == ']'
					|| ch == '%' || ch == '*' || ch == '"' || ch == '\\') return false;
		}
		return true;
	}

	private boolean validateMSFlag(char msflag)
	{
		for (int idx = 0; idx != msflags_srchpath.length; idx++) {
			if (msflag == msflags_srchpath[idx]) return true;
		}
		return false;
	}

	private void addMapping(char msflag, String imapflag)
	{
		byMS.put(msflag, imapflag);
		byIMAP.put(imapflag, msflag);
		canonicalFlags.put(imapflag.toLowerCase(), imapflag);
	}

	// We very rarely update mappings, and a deterministic order makes testing easier
	private char[] getMSFlags()
	{
		char[] arr = new char[getNumFlags()];
		com.grey.base.collections.IteratorInt it = byMS.recycledKeysIterator();
		int idx = 0;
		while (it.hasNext()) arr[idx++] = (char)it.next();
		java.util.Arrays.sort(arr);
		return arr;
	}
}
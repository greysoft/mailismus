/*
 * Copyright 2010-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem;

import com.grey.base.utils.ByteChars;

// The SPID and QID are encoded within the MMQ filenames.
// This class parses them out it.
final class FilenameParser
{
	public static final String SFX_CTRLFILE = ".mmq"; //short for "Mail Meta Queue", the Queue control files
	public static final char DLM_FILENAME = '='; //delimiter between the fields that make up an MMQ filename
	public static final char PFX_MSGFILE = 'M';
	public static final char PFX_BOUNCEFILE = 'B';
	public static final char PFX_DEFERDIR = 'T';
	public static final int FILENAME_PFXLEN = 1; //all 3 filename prefixes above must be this length

	private final com.grey.mailismus.mta.queue.QueueManager qmgr;

	// These are output-only fields which are set upon return from parse().
	// Beware that they are transient results that are overwritten by each call to parse().
	public int parsed_spid;
	public int parsed_qid;

	// temp work areas, pre-allocated merely for efficiency
	private final StringBuilder tmpsb = new StringBuilder();
	private final ByteChars tmpbc = new ByteChars();
	public final ByteChars tmplightbc = new ByteChars(-1); //lightweight object without own storage

	public FilenameParser(com.grey.mailismus.mta.queue.QueueManager qm) {qmgr = qm;}

	// see buildFilename() for the format
	public boolean parse(String filename)
	{
		char ch0 = filename.charAt(0);
		if (ch0 != PFX_MSGFILE && ch0 != PFX_BOUNCEFILE) return false;
		if (!filename.endsWith(SFX_CTRLFILE)) return false; //not an MMQ file
		int spid_off = FILENAME_PFXLEN;
		int spid_lmt = filename.indexOf(DLM_FILENAME);
		int qid_off = spid_lmt + 1;
		int qid_lmt = filename.indexOf(SFX_CTRLFILE, qid_off);
		if (qid_lmt == -1 || spid_lmt == -1) return false; //not a valid MMQ file
		tmpbc.populate(filename);
		tmplightbc.set(tmpbc, spid_off, spid_lmt - spid_off);
		parsed_spid = (int)tmplightbc.parseHexadecimal();
		tmplightbc.set(tmpbc, qid_off, qid_lmt - qid_off);
		parsed_qid = (int)tmplightbc.parseDecimal();
		return true;
	}

	private String buildFilename(char pfx, int spid, int qid)
	{
		tmpsb.setLength(0);
		tmpsb.append(pfx).append(qmgr.externalSPID(spid)).append(DLM_FILENAME).append(qid);
		return tmpsb.append(SFX_CTRLFILE).toString();
	}

	public String buildNew(int spid, int qid) {
		return buildFilename(PFX_MSGFILE, spid, qid);
	}

	public String buildRetry(int spid, int qid) {
		return buildNew(spid, qid);
	}

	public String buildBounce(int spid, int qid) {
		return buildFilename(PFX_BOUNCEFILE, spid, qid);
	}
}
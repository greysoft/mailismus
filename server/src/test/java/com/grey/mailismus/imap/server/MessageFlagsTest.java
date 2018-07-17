/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.mailismus.imap.IMAP4Protocol;
import com.grey.mailismus.ms.maildir.MaildirStore;

public class MessageFlagsTest
{
	private static final String rootdir = com.grey.mailismus.TestSupport.initPaths(MessageFlagsTest.class);

	@org.junit.Test
	public void testDynamic() throws java.io.IOException
	{
		String pthnam = rootdir+"/mapfile_dyn";
		MessageFlags mf = new MessageFlags(pthnam, true);
		org.junit.Assert.assertTrue(mf.permitDynamic());
		int numflags = mf.getNumFlags();
		String rspflags = verify_common(mf);

		String kword1 = "kword1";
		org.junit.Assert.assertEquals(MessageFlags.NOFLAG, mf.getFlagMS(kword1, false));
		org.junit.Assert.assertEquals(numflags, mf.getNumFlags());
		char kword1_ms = mf.getFlagMS(kword1, true);
		org.junit.Assert.assertEquals(numflags+1, mf.getNumFlags());
		org.junit.Assert.assertTrue(kword1_ms != MessageFlags.NOFLAG);
		org.junit.Assert.assertEquals(kword1, mf.getFlagIMAP(kword1_ms));
		rspflags += " kword1";
		org.junit.Assert.assertEquals(rspflags, mf.getResponseFlags());
		org.junit.Assert.assertEquals(rspflags+" \\*", mf.getResponsePermFlags());

		MessageFlags mf2 = new MessageFlags(pthnam, false);
		org.junit.Assert.assertEquals(numflags+1, mf2.getNumFlags());
		char kword1_ms_2 = mf2.getFlagMS(kword1, true);
		org.junit.Assert.assertEquals(kword1_ms, kword1_ms_2);
		org.junit.Assert.assertEquals(kword1, mf2.getFlagIMAP(kword1_ms));
		org.junit.Assert.assertEquals(MessageFlags.NOFLAG, mf2.getFlagMS(kword1+"x", true));
		org.junit.Assert.assertEquals(rspflags, mf.getResponseFlags());
		org.junit.Assert.assertEquals(rspflags+" \\*", mf.getResponsePermFlags());
	}

	@org.junit.Test
	public void testNonDynamic() throws java.io.IOException
	{
		String pthnam = rootdir+"/mapfile_nondyn";
		MessageFlags mf = new MessageFlags(pthnam, false);
		org.junit.Assert.assertFalse(mf.permitDynamic());
		int numflags = mf.getNumFlags();
		String rspflags = verify_common(mf);

		String kword1 = "kword1";
		org.junit.Assert.assertEquals(MessageFlags.NOFLAG, mf.getFlagMS(kword1, false));
		org.junit.Assert.assertEquals(numflags, mf.getNumFlags());
		org.junit.Assert.assertEquals(MessageFlags.NOFLAG, mf.getFlagMS(kword1, true));
		org.junit.Assert.assertEquals(numflags, mf.getNumFlags());
		org.junit.Assert.assertEquals(rspflags, mf.getResponseFlags());
		org.junit.Assert.assertEquals(rspflags, mf.getResponsePermFlags());
	}

	private String verify_common(MessageFlags mf) throws java.io.IOException
	{
		org.junit.Assert.assertEquals(6, mf.getNumFlags());

		org.junit.Assert.assertNull(mf.getFlagIMAP('z'));
		org.junit.Assert.assertNull(mf.getFlagIMAP(MessageFlags.NOFLAG));
		org.junit.Assert.assertEquals(IMAP4Protocol.MSGFLAG_DRAFT, mf.getFlagIMAP(MaildirStore.MSGFLAG_DRAFT));
		org.junit.Assert.assertEquals(IMAP4Protocol.MSGFLAG_FLAGGED, mf.getFlagIMAP(MaildirStore.MSGFLAG_FLAGGED));
		org.junit.Assert.assertEquals(IMAP4Protocol.MSGFLAG_ANSWERED, mf.getFlagIMAP(MaildirStore.MSGFLAG_REPLIED));
		org.junit.Assert.assertEquals(IMAP4Protocol.MSGFLAG_SEEN, mf.getFlagIMAP(MaildirStore.MSGFLAG_SEEN));
		org.junit.Assert.assertEquals(IMAP4Protocol.MSGFLAG_DEL, mf.getFlagIMAP(MaildirStore.MSGFLAG_DEL));
		org.junit.Assert.assertEquals(IMAP4Protocol.MSGFLAG_RECENT, mf.getFlagIMAP(MaildirStore.MSGFLAG_RECENT));
		org.junit.Assert.assertNull(mf.getFlagIMAP(MaildirStore.MSGFLAG_PASSED));

		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_DRAFT, mf.getFlagMS(IMAP4Protocol.MSGFLAG_DRAFT, false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_FLAGGED, mf.getFlagMS(IMAP4Protocol.MSGFLAG_FLAGGED, false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_REPLIED, mf.getFlagMS(IMAP4Protocol.MSGFLAG_ANSWERED, false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_SEEN, mf.getFlagMS(IMAP4Protocol.MSGFLAG_SEEN, false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_DEL, mf.getFlagMS(IMAP4Protocol.MSGFLAG_DEL, false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_RECENT, mf.getFlagMS(IMAP4Protocol.MSGFLAG_RECENT, false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_RECENT, mf.getFlagMS(IMAP4Protocol.MSGFLAG_RECENT.toLowerCase(), false));
		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_RECENT, mf.getFlagMS(IMAP4Protocol.MSGFLAG_RECENT.toUpperCase(), false));

		org.junit.Assert.assertEquals(MaildirStore.MSGFLAG_DRAFT,
				mf.getFlagMS(IMAP4Protocol.MSGFLAG_DRAFT, 0, IMAP4Protocol.MSGFLAG_DRAFT.length(), false));
		org.junit.Assert.assertEquals(MessageFlags.NOFLAG,
				mf.getFlagMS(IMAP4Protocol.MSGFLAG_DRAFT, 0, IMAP4Protocol.MSGFLAG_DRAFT.length()-1, false));
		String rspflags = "\\Draft \\Flagged \\Answered \\Seen \\Deleted";
		org.junit.Assert.assertEquals(rspflags, mf.getResponseFlags());
		String permflags = rspflags + (mf.permitDynamic() ? " \\*" : "");
		org.junit.Assert.assertEquals(permflags, mf.getResponsePermFlags());
		return rspflags;
	}
}
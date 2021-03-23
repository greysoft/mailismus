/*
 * Copyright 2012-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.utils.FileOps;

/*
 * This is a basic access Maildir class which is oriented towards POP3, and operates purely
 * on the drop folder for new incoming messages, ie. INBOX/new
 */
public class InboxSession
	implements com.grey.base.utils.FileOps.LineReader
{
	private final MaildirStore ms;
	private final java.io.File dh_drop;  //INBOX/new
	private java.io.File[] newmessages; //the contents of dropdir

	public int newMessageCount() {return (newmessages == null ? 0 : newmessages.length);}
	public int getMessageSize(int msgid) {return (int)newmessages[msgid].length();}
	public String getUIDL(int msgid) {return newmessages[msgid].getName();}
	public boolean lineModeSend() {return ms.dotstuffing;}
	public void endSession() {}

	public InboxSession(com.grey.mailismus.ms.MessageStore m, String usrnam) {
		ms = (MaildirStore)m;
		dh_drop = ms.getDropDir(usrnam);
		newmessages = dh_drop.listFiles();
	}

	public void deleteMessage(int msgid) throws java.io.IOException
	{
		java.io.File fh = newmessages[msgid];
		if (!fh.delete()) {
			if (fh.exists()) throw new java.io.IOException("Failed to delete message-file - "+fh.getAbsolutePath());
		}
	}

	public void sendMessage(int msgid, int maxlines, com.grey.naf.reactor.IOExecWriter chanwriter) throws java.io.IOException
	{
		java.io.File fh = newmessages[msgid];
		if (ms.dotstuffing || maxlines != 0) {
			StringBuilder msgsb = ms.sharedtmpsb;
			msgsb.setLength(0);
			FileOps.readTextLines(fh, this, ms.msgbufsiz, null, maxlines, chanwriter);
			if (msgsb.length() != 0) transmitMessageChunk(chanwriter, msgsb);  //flush the remaining partial buffer
			return;
		}
		chanwriter.transmit(fh.toPath());
	}

	public void sendMessage(int msgid, int maxlines, java.io.OutputStream ostrm) throws java.io.IOException
	{
		java.io.File fh = newmessages[msgid];
		if (ms.dotstuffing || maxlines != 0 || !(ostrm instanceof java.io.FileOutputStream)) {
			StringBuilder msgsb = ms.sharedtmpsb;
			msgsb.setLength(0);
			java.io.OutputStreamWriter cstrm = new java.io.OutputStreamWriter(ostrm);
			FileOps.readTextLines(fh, this, ms.msgbufsiz, null, maxlines, cstrm);
			if (msgsb.length() != 0) cstrm.write(msgsb.toString());  //flush the remaining partial buffer
			cstrm.close();
		} else {
			java.io.FileInputStream istrm = new java.io.FileInputStream(fh);
			try {
				java.io.FileOutputStream fstrm = (java.io.FileOutputStream)ostrm;
				istrm.getChannel().transferTo(0, fh.length(), fstrm.getChannel());
			} finally {
				istrm.close();
			}
		}
	}

	public final void sendMessage(int msgid, int maxlines, java.io.File fh) throws java.io.IOException
	{
		java.io.OutputStream ostrm = new java.io.FileOutputStream(fh);
		try {
			sendMessage(msgid, maxlines, ostrm);
		} finally {
			ostrm.close();
		}
	}

	public final void sendMessage(int msgid, int maxlines, String pthnam) throws java.io.IOException
	{
		sendMessage(msgid, maxlines, new java.io.File(pthnam));
	}

	@Override
	public boolean processLine(String line, int lno, int mode, Object cbdata) throws java.io.IOException
	{
		StringBuilder msgsb = ms.sharedtmpsb; //has to be set to same object as in the caller
		if (ms.dotstuffing && line.length() != 0 && line.charAt(0) == '.') msgsb.append('.');
		msgsb.append(line).append("\r\n");

		// accumulate the data in msgb until it's big enough to be worth sending
		if (msgsb.length() > ms.msgbufsiz) {
			if (cbdata.getClass() == com.grey.naf.reactor.IOExecWriter.class) {
				transmitMessageChunk((com.grey.naf.reactor.IOExecWriter)cbdata, msgsb);
			} else {
				java.io.OutputStreamWriter ostrm = (java.io.OutputStreamWriter)cbdata;
				ostrm.write(msgsb.toString());
			}
			msgsb.setLength(0);
		}
		return (lno == mode);
	}

	private void transmitMessageChunk(com.grey.naf.reactor.IOExecWriter chanwriter, CharSequence sb) throws java.io.IOException
	{
		ms.tmpniobuf = com.grey.base.utils.NIOBuffers.encode(sb, ms.tmpniobuf, com.grey.naf.BufferGenerator.directniobufs);
		ms.tmpniobuf.position(0);
		chanwriter.transmit(ms.tmpniobuf);
	}
}
/*
 * Copyright 2012-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteChars;
import com.grey.mailismus.TestSupport;
import com.grey.mailismus.directory.DirectoryFactory;
import com.grey.mailismus.ms.MessageStore;
import com.grey.mailismus.ms.MessageStoreFactory;
import com.grey.naf.ApplicationContextNAF;

public class MaildirStoreTest
{
	private static final String workdir = TestSupport.initPaths(MaildirStoreTest.class)+"/work";
	private static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("");
	private static final ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, true);

	private final String mscfgxml = "<message_store>"
			+"<directory>"
				+"<domains>"+workdir+"/conf/domains</domains>"
				+"<users>"+workdir+"/conf/users</users>"
				+"<virtualusers>N</virtualusers>"
			+"</directory>"
			+"<userpath>"+workdir+"/ms</userpath>"
			+"<chmod_tree>java -version</chmod_tree>"  //a harmless no-op that should work on any platform
			+"<filebufsiz>29</filebufsiz>" //this is sized to split bodytxt1 in mid-escape-sequence
			+"<dotstuffing>Y</dotstuffing>"
		+"</message_store>";
	private static final String local_domains = "mydom1.local";
	private static final String local_users = "";

	private static final String msgbody1 = "..Line1\r\n..Line 2\r\nLine 3\r\n\r\n...Line 4\r\n..\r\nA\r\n"
			+"Line .. 5\r\nLine 6..\r\nLine 7  \r\n\n..Line 8\r\n";
	private static final String msgbody2 = "This is the second message - spans 2 reads\r\n";

	private com.grey.naf.reactor.Dispatcher dsptch;

	@org.junit.Test
	public void testInboxSession_DotStuffed() throws java.io.IOException, java.net.URISyntaxException
	{
		java.io.File dh_work = new java.io.File(workdir);
		FileOps.deleteDirectory(dh_work);
		org.junit.Assert.assertFalse(dh_work.exists());
		FileOps.ensureDirExists(dh_work);

		com.grey.base.config.XmlConfig cfg = setup(true, false, true);
		MaildirStore ms = (MaildirStore)createMS(cfg);
		ByteChars username = new ByteChars("anyolduser");  //not a local user, to verify that we don't bother checking

		// make sure directory was correctly loaded, though we're not going to make further use of it
		org.junit.Assert.assertNotNull(ms.directory());
		org.junit.Assert.assertTrue(ms.directory().isLocalDomain(new ByteChars("mydom1.local")));
		org.junit.Assert.assertFalse(ms.directory().isLocalDomain(new ByteChars("mydom2.local")));

		// test session on non-existing mailbox
		InboxSession sess = ms.startInboxSession(username);
		org.junit.Assert.assertEquals(0, sess.newMessageCount());
		sess.endSession();
		String pthnam_inbox = workdir+"/ms/"+username+"/Maildir/new";
		java.io.File dh = new java.io.File(pthnam_inbox);
		java.io.File[] msgfiles = dh.listFiles();
		org.junit.Assert.assertTrue(msgfiles == null || msgfiles.length == 0);

		// deliver a message into Maildir inbox
		java.io.File fh_in = new java.io.File(dh_work, "ms_input");
		FileOps.writeTextFile(fh_in, msgbody1, false);
		String normtxt = msgbody1.substring(1).replace("\n..", "\n.");  //normalise to non-dotstuffed equivalent
		ms.deliver(username, fh_in);
		//... and make sure it ended up where we expect
		msgfiles = dh.listFiles();
		int filecnt = (msgfiles == null ? 0 : msgfiles.length); //obviously null means wrong, but this avoids FindBugs warning
		org.junit.Assert.assertEquals(1, filecnt);
		String txt = FileOps.readAsText(msgfiles[0], null);
		org.junit.Assert.assertEquals(normtxt, txt);
		// ... now test the official MS access methods
		sess = ms.startInboxSession(username);
		String uidl1 = sess.getUIDL(0);
		org.junit.Assert.assertEquals(1, sess.newMessageCount());
		org.junit.Assert.assertEquals(msgfiles[0].getName(), uidl1);
		java.io.File fh_out = new java.io.File(dh_work, "ms_output");
		sess.sendMessage(0, 0, fh_out.getAbsolutePath());
		txt = FileOps.readAsText(fh_out, null);
		String normtxt2 = msgbody1.replace("\n", "\r\n").replace("\r\r\n", "\r\n"); //line endings get normalised
		org.junit.Assert.assertEquals(normtxt2, txt);
		int msgsiz = sess.getMessageSize(0);
		org.junit.Assert.assertEquals(normtxt.length(), msgsiz);
		sess.endSession();

		// deliver another message
		FileOps.writeTextFile(fh_in, msgbody2, false);
		ms.deliver(username, fh_in);
		//... and retrieve it back
		sess = ms.startInboxSession(username);
		org.junit.Assert.assertEquals(2, sess.newMessageCount());
		int msgid1 = 0;
		if (!sess.getUIDL(0).equals(uidl1)) msgid1 = 1;
		int msgid2 = (msgid1 + 1) % 2;
		org.junit.Assert.assertFalse(uidl1.equals(sess.getUIDL(msgid2)));
		sess.sendMessage(msgid1, 0, fh_out.getAbsolutePath());
		org.junit.Assert.assertEquals(normtxt2, FileOps.readAsText(fh_out, null));
		org.junit.Assert.assertEquals(normtxt.length(), sess.getMessageSize(msgid1));
		sess.sendMessage(msgid2, 0, fh_out.getAbsolutePath());
		org.junit.Assert.assertEquals(msgbody2, FileOps.readAsText(fh_out, null));
		org.junit.Assert.assertEquals(msgbody2.length(), sess.getMessageSize(msgid2));
		sess.deleteMessage(msgid2);
		sess.endSession();

		// make sure the delete-message worked
		sess = ms.startInboxSession(username);
		org.junit.Assert.assertEquals(1, sess.newMessageCount());
		org.junit.Assert.assertEquals(sess.getUIDL(0), uidl1);
		sess.endSession();
	}

	@org.junit.Test
	public void testInboxSession_NotStuffed() throws java.io.IOException, java.net.URISyntaxException
	{
		java.io.File dh_work = new java.io.File(workdir);
		FileOps.deleteDirectory(dh_work);
		org.junit.Assert.assertFalse(dh_work.exists());
		FileOps.ensureDirExists(dh_work);

		com.grey.base.config.XmlConfig cfg = setup(true, false, false);
		MaildirStore ms = (MaildirStore)createMS(cfg);
		ByteChars username = new ByteChars("anyolduser");

		// deliver a message into Maildir inbox
		java.io.File fh_in = new java.io.File(dh_work, "ms_input");
		FileOps.writeTextFile(fh_in, msgbody1, false);
		ms.deliver(username, fh_in);
		// ... and retrieve it back
		InboxSession sess = ms.startInboxSession(username);
		String uidl1 = sess.getUIDL(0);
		org.junit.Assert.assertEquals(1, sess.newMessageCount());
		java.io.File fh_out = new java.io.File(dh_work, "ms_output");
		sess.sendMessage(0, 0, fh_out.getAbsolutePath());
		String txt = FileOps.readAsText(fh_out, null);
		org.junit.Assert.assertEquals(msgbody1, txt);
		int msgsiz = sess.getMessageSize(0);
		org.junit.Assert.assertEquals(msgbody1.length(), msgsiz);
		sess.endSession();

		// deliver another message
		FileOps.writeTextFile(fh_in, msgbody2, false);
		ms.deliver(username, fh_in);
		//... and retrieve it back
		sess = ms.startInboxSession(username);
		org.junit.Assert.assertEquals(2, sess.newMessageCount());
		int msgid1 = 0;
		if (!sess.getUIDL(0).equals(uidl1)) msgid1 = 1;
		int msgid2 = (msgid1 + 1) % 2;
		org.junit.Assert.assertFalse(uidl1.equals(sess.getUIDL(msgid2)));
		sess.sendMessage(msgid1, 0, fh_out.getAbsolutePath());
		org.junit.Assert.assertEquals(msgbody1, FileOps.readAsText(fh_out, null));
		org.junit.Assert.assertEquals(msgbody1.length(), sess.getMessageSize(msgid1));
		sess.sendMessage(msgid2, 0, fh_out.getAbsolutePath());
		org.junit.Assert.assertEquals(msgbody2, FileOps.readAsText(fh_out, null));
		org.junit.Assert.assertEquals(msgbody2.length(), sess.getMessageSize(msgid2));
		sess.deleteMessage(msgid1);
		sess.deleteMessage(msgid2);
		sess.endSession();

		// test effect of deleting all messages
		sess = ms.startInboxSession(username);
		org.junit.Assert.assertEquals(0, sess.newMessageCount());
		sess.endSession();
	}

	private com.grey.base.config.XmlConfig setup(boolean withDirectory, boolean disabled, boolean dotstuffed)
			throws java.io.IOException, java.net.URISyntaxException
	{
		java.io.File dh_work = new java.io.File(workdir);
		FileOps.deleteDirectory(dh_work);
		org.junit.Assert.assertFalse(dh_work.exists());
		FileOps.ensureDirExists(dh_work);
		dsptch = com.grey.naf.reactor.Dispatcher.create(appctx, new com.grey.naf.reactor.config.DispatcherConfig.Builder().build(), logger);

		String cfgxml = mscfgxml;
		if (!withDirectory) {
			if (disabled) {
				cfgxml = cfgxml.replace("<directory", "<directory enabled=\"N\"");
			} else {
				cfgxml = cfgxml.replace("directory>", "xdirectory>");
			}
		}
		if (!dotstuffed) cfgxml = cfgxml.replace("dotstuffing>", "xdotstuffing>");
		com.grey.base.config.XmlConfig cfg = com.grey.base.config.XmlConfig.makeSection(cfgxml, "message_store");
		if (!withDirectory) return cfg;
		String pthnam = appctx.getConfig().getPath(cfg, "directory/domains", null, true, null, getClass());
		java.io.File fh = new java.io.File(pthnam);
		FileOps.ensureDirExists(fh.getParentFile());
		FileOps.writeTextFile(fh, local_domains, false);
		pthnam = appctx.getConfig().getPath(cfg, "directory/users", null, true, null, getClass());
		FileOps.writeTextFile(pthnam, local_users);
		return cfg;
	}

	private MessageStore createMS(com.grey.base.config.XmlConfig mscfg)
			throws java.io.IOException, java.net.URISyntaxException
	{
		com.grey.base.config.XmlConfig dcfg = mscfg.getSection("directory");
		com.grey.mailismus.directory.Directory msdir = null;
		if (dcfg.exists()) {
			msdir = new DirectoryFactory().create(dsptch, dcfg);
		}
		return new MessageStoreFactory().create(dsptch, mscfg, msdir);
	}
}
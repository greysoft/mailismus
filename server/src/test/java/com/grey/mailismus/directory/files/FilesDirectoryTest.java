/*
 * Copyright 2012-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.directory.files;

import com.grey.base.utils.FileOps;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.mailismus.TestSupport;
import com.grey.mailismus.directory.Directory;
import com.grey.mailismus.directory.DirectoryFactory;
import com.grey.mailismus.directory.DirectoryImpl;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;

public class FilesDirectoryTest
{
	private static final String workdir = TestSupport.initPaths(FilesDirectoryTest.class)+"/work";
	private static final com.grey.logging.Logger logger = com.grey.logging.Factory.getLoggerNoEx("");

	private static final String cfgxml_directory = "<directory>"
			+"<domains>"+workdir+"/conf/domains</domains>"
			+"<users>"+workdir+"/conf/users</users>"
			+"<aliases>"+workdir+"/conf/aliases</aliases>"
			+"<virtualusers>N</virtualusers>"
			+"<xplainpass>Y</xplainpass>"
		+"</directory>";
	private static final String local_domains = "mydom1.local\n\tmydom2.local \t\n \n #comment \nmydom3.local";
	private static final String local_users_plain = "locuser1:pass1\nlocuser2:pass2\n#comment\nlocuser3:pass3";
	private static final String local_users_hashed = "locuser1:"+hashedPassword("pass1")
			+"\nlocuser2:"+hashedPassword("pass2")+"\n#comment\nlocuser3:"+hashedPassword("pass3");
	private static final String local_aliases = "role1@mydom1.local:locuser1\n"
			+"#comment : x\n"
			+" role1 : locuser2 : somebody@elsewhere.com\n"
			+" role2 : role1 : somebody2@elsewhere.com";

	private final ApplicationContextNAF appctx = TestSupport.createApplicationContext(null, true);
	private com.grey.naf.reactor.Dispatcher dsptch;
	private String pthnam_users;
	private String pthnam_aliases;

	@org.junit.Test
	public void testMembership() throws java.io.IOException
	{
		com.grey.base.config.XmlConfig cfg = setup(true);
		Directory dty = new DirectoryFactory().create(dsptch, cfg);
		org.junit.Assert.assertNotNull(dty);
		org.junit.Assert.assertFalse(dty.virtualUsers());
		verifyMembership(dty);
		dty.reload();
		verifyMembership(dty);

		//make sure blank Aliases file is ok
		FileOps.writeTextFile(pthnam_aliases, "");
		dty.reload();
		org.junit.Assert.assertTrue(dty.isLocalDomain(new ByteChars("mydom1.local")));
		org.junit.Assert.assertTrue(dty.isLocalUser(new ByteChars("locuser1")));
		org.junit.Assert.assertFalse(dty.isLocalUser(new ByteChars("role1")));

		//... but it does have to exist if the config nominated it
		java.io.File fh = new java.io.File(pthnam_aliases);
		fh.delete();
		org.junit.Assert.assertFalse(fh.exists());
		try {
			dty.reload();
			org.junit.Assert.fail("Reload is expected to fail when Aliases file is missing");
		} catch (Throwable ex) {}
		// make sure previous data is still in effect
		org.junit.Assert.assertTrue(dty.isLocalDomain(new ByteChars("mydom1.local")));
		org.junit.Assert.assertTrue(dty.isLocalUser(new ByteChars("locuser1")));
		FileOps.writeTextFile(pthnam_aliases, ""); //restore for next test

		//verify that Users file has to exist
		fh = new java.io.File(pthnam_users);
		fh.delete();
		org.junit.Assert.assertFalse(fh.exists());
		try {
			dty.reload();
			org.junit.Assert.fail("Reload is expected to fail when Users file is missing");
		} catch (Throwable ex) {}
		// make sure previous data is still in effect
		org.junit.Assert.assertTrue(dty.isLocalDomain(new ByteChars("mydom1.local")));
		org.junit.Assert.assertTrue(dty.isLocalUser(new ByteChars("locuser1")));
	}

	@org.junit.Test
	public void testPlainPasswords() throws java.io.IOException
	{
		com.grey.base.config.XmlConfig cfg = setup(false);
		Directory dty = new DirectoryFactory().create(dsptch, cfg);
		org.junit.Assert.assertFalse(dty.virtualUsers());
		verifyMembership(dty);

		ByteChars username = new ByteChars("locuser1");
		org.junit.Assert.assertTrue(dty.passwordVerify(username, new ByteChars("pass1")));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, new ByteChars("badpass")));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, null));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, new ByteChars("")));
		org.junit.Assert.assertFalse(dty.passwordVerify(null, new ByteChars("badpass")));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars(""), new ByteChars("badpass")));
		org.junit.Assert.assertFalse(dty.passwordVerify(null, null));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars("role1@mydom1.local"), new ByteChars("pass1")));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars("role1"), new ByteChars("pass2")));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars("role2"), new ByteChars("pass2")));
		org.junit.Assert.assertTrue(dty.passwordVerify(new ByteChars("locuser2"), new ByteChars("pass2")));

		username = new ByteChars("nosuchuser");
		ByteChars inpass = new ByteChars("badpass");
		org.junit.Assert.assertFalse(dty.passwordVerify(username, inpass));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, null));
		org.junit.Assert.assertNull(dty.passwordLookup(username));
		org.junit.Assert.assertNull(dty.passwordLookup(null));
		org.junit.Assert.assertNull(dty.passwordLookup(new ByteChars("")));
		username = new ByteChars("locuser1");
		org.junit.Assert.assertEquals(new ByteChars("pass1"), dty.passwordLookup(username));
	}

	@org.junit.Test
	public void testHashedPasswords() throws java.io.IOException
	{
		com.grey.base.config.XmlConfig cfg = setup(true);
		Directory dty = new DirectoryFactory().create(dsptch, cfg);
		org.junit.Assert.assertFalse(dty.virtualUsers());
		verifyMembership(dty);

		ByteChars username = new ByteChars("locuser1");
		org.junit.Assert.assertTrue(dty.passwordVerify(username, new ByteChars("pass1")));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, new ByteChars("badpass")));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, null));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, new ByteChars("")));
		org.junit.Assert.assertFalse(dty.passwordVerify(null, new ByteChars("badpass")));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars(""), new ByteChars("badpass")));
		org.junit.Assert.assertFalse(dty.passwordVerify(null, null));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars("role1@mydom1.local"), new ByteChars("pass1")));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars("role1"), new ByteChars("pass2")));
		org.junit.Assert.assertFalse(dty.passwordVerify(new ByteChars("role2"), new ByteChars("pass2")));
		org.junit.Assert.assertTrue(dty.passwordVerify(new ByteChars("locuser2"), new ByteChars("pass2")));

		username = new ByteChars("nosuchuser");
		ByteChars inpass = new ByteChars("badpass");
		org.junit.Assert.assertFalse(dty.passwordVerify(username, inpass));
		org.junit.Assert.assertFalse(dty.passwordVerify(username, null));

		username = new ByteChars("locuser1");
		try {
			dty.passwordLookup(username);
			org.junit.Assert.fail("Password lookup should be unsupported when plainpass attribute is false");
		} catch (UnsupportedOperationException ex) {}

		String sha256digest = "9b8769a4a742959a2d0298c36fb70623f2dfacda8436237df08d8dfd5b37374c";
		String hashed = hashedPassword("pass123");
		org.junit.Assert.assertEquals(sha256digest, hashed);
	}

	private void verifyMembership(Directory dty)
	{
		org.junit.Assert.assertTrue(dty.isLocalDomain(new ByteChars("mydom1.local")));
		org.junit.Assert.assertTrue(dty.isLocalDomain(new ByteChars("mydom2.local")));
		org.junit.Assert.assertTrue(dty.isLocalDomain(new ByteChars("mydom3.local")));
		org.junit.Assert.assertFalse(dty.isLocalDomain(new ByteChars("nonsuch.local")));
		org.junit.Assert.assertFalse(dty.isLocalDomain(new ByteChars("#comment")));
		org.junit.Assert.assertFalse(dty.isLocalDomain(new ByteChars("comment")));
		org.junit.Assert.assertFalse(dty.isLocalDomain(new ByteChars("")));
		org.junit.Assert.assertFalse(dty.isLocalDomain(null));

		org.junit.Assert.assertTrue(dty.isLocalUser(new ByteChars("locuser1")));
		org.junit.Assert.assertTrue(dty.isLocalUser(new ByteChars("locuser2")));
		org.junit.Assert.assertTrue(dty.isLocalUser(new ByteChars("locuser3")));
		org.junit.Assert.assertFalse(dty.isLocalUser(new ByteChars("nonsuch")));
		org.junit.Assert.assertFalse(dty.isLocalUser(new ByteChars("#comment")));
		org.junit.Assert.assertFalse(dty.isLocalUser(new ByteChars("comment")));
		org.junit.Assert.assertFalse(dty.isLocalUser(new ByteChars("")));
		org.junit.Assert.assertFalse(dty.isLocalUser(null));

		EmailAddress emaddr = new EmailAddress("role1@mydom1.local");
		ByteChars aliasname = dty.isLocalAlias(emaddr);
		org.junit.Assert.assertNotNull(aliasname);
		org.junit.Assert.assertEquals(1, dty.expandAlias(aliasname).size());
		emaddr.set("role1");
		aliasname = dty.isLocalAlias(emaddr);
		org.junit.Assert.assertNotNull(aliasname);
		org.junit.Assert.assertEquals(2, dty.expandAlias(aliasname).size());
		emaddr.set("role2");
		aliasname = dty.isLocalAlias(emaddr);
		org.junit.Assert.assertNotNull(aliasname);
		org.junit.Assert.assertEquals(3, dty.expandAlias(aliasname).size());
		EmailAddress emaddr2 = new EmailAddress("role2@mydom1.local");
		org.junit.Assert.assertEquals(emaddr.decompose().mailbox, dty.isLocalAlias(emaddr2));
		emaddr.set("role3@mydom1.local");
		aliasname = dty.isLocalAlias(emaddr);
		org.junit.Assert.assertNull(aliasname);
	}

	private com.grey.base.config.XmlConfig setup(boolean hashed) throws java.io.IOException
	{
		java.io.File dh_work = new java.io.File(workdir);
		FileOps.deleteDirectory(dh_work);
		org.junit.Assert.assertFalse(dh_work.exists());
		FileOps.ensureDirExists(dh_work);
		dsptch = com.grey.naf.reactor.Dispatcher.create(appctx, new com.grey.naf.reactor.config.DispatcherConfig.Builder().build(), logger);

		String cfgxml = cfgxml_directory;
		String local_users = local_users_hashed;
		if (!hashed) {
			cfgxml = cfgxml.replace("xplainpass", "plainpass");
			local_users = local_users_plain;
		}
		NAFConfig nafcfg = appctx.getConfig();
		com.grey.base.config.XmlConfig cfg = com.grey.base.config.XmlConfig.makeSection(cfgxml, "directory");
		String pthnam = nafcfg.getPath(cfg, "domains", null, true, null, getClass());
		java.io.File fh = new java.io.File(pthnam);
		FileOps.ensureDirExists(fh.getParentFile());
		FileOps.writeTextFile(fh, local_domains, false);
		pthnam_users = nafcfg.getPath(cfg, "users", null, true, null, getClass());
		FileOps.writeTextFile(pthnam_users, local_users);
		pthnam_aliases = nafcfg.getPath(cfg, "aliases", null, true, null, getClass());
		FileOps.writeTextFile(pthnam_aliases, local_aliases);
		return cfg;
	}

	private static String hashedPassword(CharSequence plainpass)
	{
		ByteChars bc = new ByteChars(plainpass);
		try {
			char[] hashed = DirectoryImpl.passwordHash(bc);
			return new String(hashed);
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new RuntimeException("Failed to hash password="+plainpass, ex);
		}
	}
}
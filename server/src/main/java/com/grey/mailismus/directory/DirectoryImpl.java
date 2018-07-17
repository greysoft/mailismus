/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.directory;

import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;

public abstract class DirectoryImpl
	implements Directory
{
	private static final String DIGESTPROC = SysProps.get("grey.mailismus.directory.digestproc", com.grey.base.crypto.Defs.ALG_DIGEST_SHA256);

	abstract public int getUserCount();
	abstract public int getDomainCount();

	protected final com.grey.naf.reactor.Dispatcher dsptch;
	private final java.security.MessageDigest digestproc;
	private final boolean virtual_users;
	private final boolean plain_passwords;

	@Override
	public final boolean virtualUsers() {return virtual_users;}
	@Override
	public final boolean supportsPasswordLookup() {return plain_passwords;}

	public DirectoryImpl(com.grey.naf.reactor.Dispatcher d, XmlConfig cfg)
		throws java.security.NoSuchAlgorithmException
	{
		dsptch = d;
		virtual_users = cfg.getBool("virtualusers", true);
		plain_passwords = cfg.getBool("plainpass", false);
		digestproc = java.security.MessageDigest.getInstance(DIGESTPROC);
		dsptch.getLogger().info("Directory: virtual-users="+virtual_users+"; plain-pass="+plain_passwords
				+"; digest="+DIGESTPROC+"/"+digestproc.getClass().getName());
	}

	/**
	 * Returns stored password of specified user in raw form, whether hashed or not.
	 * <br>Returns null if user doesn't exist and blank if they exist but have no password.
	 * <br>Throws UnsupportedOperationException if password lookup is not possible.
	 */
	public com.grey.base.utils.ByteChars getPassword(com.grey.base.utils.ByteChars username)
	{
		throw new UnsupportedOperationException("getPassword() is not supported - username="+username);
	}

	@Override
	public final com.grey.base.utils.ByteChars passwordLookup(com.grey.base.utils.ByteChars username)
	{
		if (username == null || username.size() == 0) return null;
		if (!supportsPasswordLookup() || !plain_passwords) {
			throw new UnsupportedOperationException("Password lookup is not supported - username="+username);
		}
		return getPassword(username);
	}

	// Subclasses that don't provide getPassword() need to override this
	@Override
	public boolean passwordVerify(com.grey.base.utils.ByteChars username, com.grey.base.utils.ByteChars inpass)
	{
		if (username == null || username.size() == 0 || inpass == null) return false;
		com.grey.base.utils.ByteChars actual_passwd = getPassword(username);
		if (!plain_passwords) inpass = new com.grey.base.utils.ByteChars().append(passwordHash(inpass, digestproc));
		return inpass.equals(actual_passwd);
	}

	public static char[] passwordHash(com.grey.base.utils.ByteChars plain, java.security.MessageDigest hashproc)
	{
		return com.grey.base.crypto.Ascii.digest(plain, hashproc);
	}

	public static char[] passwordHash(com.grey.base.utils.ByteChars plain)
			throws java.security.NoSuchAlgorithmException
	{
		java.security.MessageDigest hashproc = java.security.MessageDigest.getInstance(DIGESTPROC);
		return passwordHash(plain, hashproc);
	}
}

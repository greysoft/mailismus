/*
 * Copyright 2012-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.directory;

/**
 * In addition to implementing this interface, Directory classes must also provide a constructor
 * with this signature:<br/>
 * <code>classname(com.grey.naf.reactor.Dispatcher, com.grey.base.config.XmlConfig)</code><br/>
 */
public interface Directory
{
	public static final String SINK_ALIAS = ".";

	public boolean virtualUsers();
	public boolean isLocalDomain(com.grey.base.utils.ByteChars dom);
	public boolean isLocalUser(com.grey.base.utils.ByteChars usrnam);
	public com.grey.base.utils.ByteChars isLocalAlias(com.grey.base.utils.EmailAddress emaddr);
	public java.util.ArrayList<com.grey.base.utils.ByteChars> expandAlias(com.grey.base.utils.ByteChars alias);

	public boolean passwordVerify(com.grey.base.utils.ByteChars usrnam, com.grey.base.utils.ByteChars passwd);
	/**
	 * Returns plaintext password of specified user.
	 * <br>Returns null if user doesn't exist and blank if they exist but have no password.
	 * <br>Throws UnsupportedOperationException if password lookup is not possible.
	 */
	public com.grey.base.utils.ByteChars passwordLookup(com.grey.base.utils.ByteChars usrnam);
	public boolean supportsPasswordLookup();

	public void reload() throws java.io.IOException;
}
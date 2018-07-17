/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.directory.files;

public final class FilesDirectory
	extends com.grey.mailismus.directory.DirectoryImpl
{
	private final CachedFiles cache;

	@Override
	public boolean isLocalDomain(com.grey.base.utils.ByteChars d) {return cache.isLocalDomain(d);}
	@Override
	public boolean isLocalUser(com.grey.base.utils.ByteChars u) {return cache.isLocalUser(u);}
	@Override
	public com.grey.base.utils.ByteChars isLocalAlias(com.grey.base.utils.EmailAddress em) {return cache.isLocalAlias(em);}
	@Override
	public java.util.ArrayList<com.grey.base.utils.ByteChars> expandAlias(com.grey.base.utils.ByteChars a) {return cache.expandAlias(a);}
	@Override
	public com.grey.base.utils.ByteChars getPassword(com.grey.base.utils.ByteChars u) {return cache.getPassword(u);}
	@Override
	public void reload() throws java.io.IOException {cache.reload(this);}
	@Override
	public int getUserCount() {return cache.getUserCount();}
	@Override
	public int getDomainCount() {return cache.getDomainCount();}

	public FilesDirectory(com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg)
		throws java.io.IOException, java.security.NoSuchAlgorithmException
	{
		super(d, cfg);
		cache = CachedFiles.get(d, cfg);
	}
}
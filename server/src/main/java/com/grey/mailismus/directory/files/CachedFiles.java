/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.directory.files;

import com.grey.base.utils.FileOps;
import com.grey.naf.NAFConfig;
import com.grey.base.collections.HashedSet;
import com.grey.base.collections.HashedMap;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;

public final class CachedFiles
	implements com.grey.base.utils.FileOps.LineReader
{
	private static final int MODE_DOMAINS = 1;
	private static final int MODE_USERS = 2;
	private static final int MODE_ALIASES = 3;

	private static final com.grey.base.utils.ByteChars BLANK_PASSWORD = new com.grey.base.utils.ByteChars();

	private final com.grey.naf.reactor.Dispatcher dsptch;
	private final java.io.File fh_domains;
	private final java.io.File fh_users;
	private final java.io.File fh_aliases;
	private HashedSet<ByteChars> local_domains;
	private HashedMap<ByteChars, ByteChars> local_users; //enumerates local users and maps them to password
	private HashedMap<ByteChars, java.util.ArrayList<ByteChars>> local_aliases; //maps aliases to local usernames or remote addresses

	public static synchronized CachedFiles get(com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg)
			throws java.io.IOException
	{
		return d.getApplicationContext().getNamedItem(CachedFiles.class.getName(), (c) -> new CachedFiles(d, cfg));
	}

	private CachedFiles(com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg)
		throws java.io.IOException
	{
		dsptch = d;
		NAFConfig nafcfg = dsptch.getApplicationContext().getConfig();
		String pthnam = nafcfg.getPath(cfg, "users", null, false, null, getClass());
		fh_users = (pthnam == null ? null : new java.io.File(pthnam));
		pthnam = nafcfg.getPath(cfg, "domains", null, false, null, getClass());
		fh_domains = (pthnam == null ? null : new java.io.File(pthnam));
		pthnam = nafcfg.getPath(cfg, "aliases", null, false, null, getClass());
		fh_aliases = (pthnam == null ? null : new java.io.File(pthnam));
		dsptch.getLogger().info("Directory: users = "+(fh_users == null ? "NONE" : fh_users.getAbsolutePath()));
		dsptch.getLogger().info("Directory: domains = "+(fh_domains == null ? "NONE" : fh_domains.getAbsolutePath()));
		dsptch.getLogger().info("Directory: aliases = "+(fh_aliases == null ? "NONE" : fh_aliases.getAbsolutePath()));
		load(null);
	}

	public synchronized boolean isLocalDomain(ByteChars dom)
	{
		return local_domains.contains(dom);
	}

	public synchronized boolean isLocalUser(ByteChars username)
	{
		return local_users.containsKey(username);
	}

	public synchronized ByteChars isLocalAlias(EmailAddress emaddr)
	{
		if (local_aliases.containsKey(emaddr.full)) return emaddr.full;
		emaddr.decompose();
		if (local_aliases.containsKey(emaddr.mailbox)) return emaddr.mailbox;
		return null;
	}

	public synchronized java.util.ArrayList<com.grey.base.utils.ByteChars> expandAlias(com.grey.base.utils.ByteChars alias)
	{
		return local_aliases.get(alias);
	}

	public synchronized ByteChars getPassword(ByteChars username)
	{
		ByteChars passwd = local_users.get(username);
		if (passwd == null) {
			if (local_users.containsKey(username)) passwd = BLANK_PASSWORD;
		}
		return passwd;
	}

	public synchronized int getUserCount()
	{
		return local_users.size();
	}

	public synchronized int getDomainCount()
	{
		return local_domains.size();
	}

	public void reload(com.grey.mailismus.directory.Directory dtory) throws java.io.IOException
	{
		dsptch.getLogger().trace("Reloading Directory files");
		load(dtory);
	}

	private void load(com.grey.mailismus.directory.Directory dtory) throws java.io.IOException
	{
		HashedMap<ByteChars, ByteChars> new_users = new HashedMap<ByteChars, ByteChars>();
		HashedSet<ByteChars> new_domains = new HashedSet<ByteChars>();
		HashedMap<ByteChars, java.util.ArrayList<ByteChars>> new_aliases = new HashedMap<ByteChars, java.util.ArrayList<ByteChars>>();
		int bufsiz = 8192;

		if (fh_users != null) {
			FileOps.readTextLines(fh_users, this, bufsiz, null, MODE_USERS, new_users);
		}
		if (fh_domains != null) {
			FileOps.readTextLines(fh_domains, this, bufsiz, null, MODE_DOMAINS, new_domains);
		}
		if (fh_aliases != null) {
			FileOps.readTextLines(fh_aliases, this, bufsiz, null, MODE_ALIASES, new Object[]{new_aliases, new_domains, new_users});
		}

		synchronized (this) {
			local_domains = new_domains;
			local_users = new_users;
			local_aliases = new_aliases;
		}
		dsptch.getLogger().info("Directory load: Domains="+new_domains.size()+", Users="+new_users.size()+", Aliases="+new_aliases.size());
	}

	@Override
	public boolean processLine(String line, int lno, int mode, Object cbdata)
	{
		line = line.trim().toLowerCase();
		if (line.length() == 0 || line.charAt(0) == '#') return false;

		if (mode == MODE_USERS) {
			@SuppressWarnings("unchecked")
			HashedMap<ByteChars, ByteChars> data = (HashedMap<ByteChars, ByteChars>)cbdata;
			String[] tuple = line.split(":");
			ByteChars bc_username = parseTuple(tuple, 0);
			ByteChars bc_passwd = parseTuple(tuple, 1);
			//silently ignore excess fields, and password is optional too
			if (bc_username == null) {
				throw new IllegalArgumentException("Line "+lno+": Missing Username - "+line);
			}
			ByteChars dup = data.put(bc_username, bc_passwd);

			if (dup != null) {
				if (!dup.equals(bc_passwd)) {
					throw new IllegalArgumentException("Line "+lno+": User="+bc_username+" has multiple definitions");
				}
			}
		} else if (mode == MODE_ALIASES) {
			Object[] args = Object[].class.cast(cbdata);
			@SuppressWarnings("unchecked")
			HashedMap<ByteChars, java.util.ArrayList<ByteChars>> data = HashedMap.class.cast(args[0]);
			@SuppressWarnings("unchecked")
			HashedSet<ByteChars> domains = HashedSet.class.cast(args[1]);
			@SuppressWarnings("unchecked")
			HashedMap<ByteChars, ByteChars> users = HashedMap.class.cast(args[2]);
			String[] tuple = line.split(":");
			ByteChars bc_alias = parseTuple(tuple, 0);
			if (bc_alias == null) {
				throw new IllegalArgumentException("Line "+lno+": Missing Alias - "+line);
			}
			HashedSet<ByteChars> aliasmembers = new HashedSet<ByteChars>();
			for (int idx = 1; idx != tuple.length; idx++) {
				ByteChars bc = parseTuple(tuple, idx);
				if (bc == null) continue;
				java.util.ArrayList<ByteChars> nested_members = data.get(bc);
				if (nested_members != null) {
					//current alias member is itself a nested alias
					aliasmembers.addAll(nested_members);
				} else {
					if (bc.equalsIgnoreCase(com.grey.mailismus.directory.Directory.SINK_ALIAS)) {
						//Sink alias - but has to be the only alias member, to be interpreted as such
						if (tuple.length != 2) {
							throw new IllegalArgumentException("Line "+lno+": Alias="+bc_alias+" - Sink alias must be the sole entry on RHS");
						}
					} else {
						EmailAddress emaddr = new EmailAddress(bc);
						emaddr.decompose();
						if (emaddr.domain.size() == 0 || domains.contains(emaddr.domain)) {
							//current alias member is a local username, so decompose to local-mailbox form
							bc = emaddr.mailbox;
							if (!users.containsKey(bc)) {
								throw new IllegalArgumentException("Line "+lno+": Alias="+bc_alias+" maps to non-existent local User="+bc);
							}
						}
					}
					aliasmembers.add(bc);
				}
			}
			if (aliasmembers.size() != 0) {
				//convert aliasmembers to List - as a Set, it is already free of duplicates
				java.util.ArrayList<ByteChars> lst = new java.util.ArrayList<ByteChars>();
				lst.addAll(aliasmembers);
				lst.trimToSize();
				if (data.put(bc_alias, lst) != null) {
					throw new IllegalArgumentException("Line "+lno+": Alias="+bc_alias+" has multiple definitions");
				}
			}
		} else {
			@SuppressWarnings("unchecked")
			HashedSet<ByteChars> data = (HashedSet<ByteChars>)cbdata;
			ByteChars bc = new ByteChars(line);
			data.add(bc);
		}
		return false;
	}

	private static ByteChars parseTuple(String[] tuple, int idx)
	{
		if (tuple.length <= idx) return null;
		String str = tuple[idx].trim();
		if (str.length() == 0) return null;
		return new ByteChars(str);
	}
}
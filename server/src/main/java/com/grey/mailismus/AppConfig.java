/*
 * Copyright 2018-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
/*
 * This class represents the Mailismus config file as a whole, with a special emphasis on the sections
 * that are not defined within the config block of any one task (eg. Queue, Directory, etc).
 * Although it is possible for naf.xml Naflets to nominate differing config files, we expect all Mailismus
 * Naflets to specify the same application config file.
 */
package com.grey.mailismus;

import com.grey.base.config.XmlConfig;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.errors.NAFConfigException;
import com.grey.naf.reactor.Dispatcher;

public final class AppConfig
{
	public static final String TOKEN_PRODNAME = "%PRODNAME%";

	private static final String TOKEN_HOSTNAME = "%SYSNAME%";
	private static final String XPATH_MTA = "mta"; //relative to configRoot
	private static final String XPATH_MTA_QUEUE = XPATH_MTA+"/queue";
	private static final String XPATH_MTA_RELAYS = XPATH_MTA+"/deliver/relays";

	private final String hostName;
	private final String announceHost;
	private final String productName;
	private final DBHandle.Type dbType;

	private final XmlConfig configRoot;

	public final String getProductName() {return productName;}
	public final String getAnnounceHost() {return announceHost;}
	public final DBHandle.Type getDatabaseType() {return dbType;}

	public static AppConfig get(String cfgpath, Dispatcher d) {
		ApplicationContextNAF appctx = d.getApplicationContext();
		return appctx.getNamedItem(AppConfig.class.getName(), () -> {
			try {
				return new AppConfig(cfgpath, d);
			} catch (Exception ex) {
				throw new NAFConfigException("Failed to create AppConfig with Dispatcher="+d.getName(), ex);
			}
		});
	}

	private AppConfig(String cfgpath, Dispatcher dsptch) throws java.io.IOException
	{
		hostName = java.net.InetAddress.getLocalHost().getCanonicalHostName();
		configRoot = (cfgpath.isEmpty() ? XmlConfig.BLANKCFG : XmlConfig.getSection(cfgpath, "mailserver"));

		XmlConfig cfg = configRoot.getSection("application");
		productName = cfg.getValue("prodname", true, "Mailismus");
		announceHost = getAnnounceHost(cfg, hostName);

		XmlConfig dbcfg = cfg.getSection("database"+XmlConfig.XPATH_ENABLED);
		dbType = (dbcfg.exists() ? new DBHandle.Type(dbcfg, dsptch.getApplicationContext().getConfig(), dsptch.getLogger()) : null);
	}

	public XmlConfig getConfigQueue(String name)
	{
		XmlConfig cfg = null;
		if (name != null) {
			cfg = configRoot.getSection(XPATH_MTA_QUEUE+"_"+name);
			if (!cfg.exists()) cfg = null;
		}
		if (cfg == null) cfg = configRoot.getSection(XPATH_MTA_QUEUE);
		return cfg;
	}

	public XmlConfig getConfigDirectory()
	{
		return configRoot.getSection("directory"+XmlConfig.XPATH_ENABLED);
	}

	public XmlConfig getConfigMS()
	{
		return configRoot.getSection("message_store"+XmlConfig.XPATH_ENABLED);
	}

	public XmlConfig getConfigRelays()
	{
		return configRoot.getSection(XPATH_MTA_RELAYS);
	}

	public String getAnnounceHost(XmlConfig cfg, String dflt)
	{
		return parseHost(cfg, "announcehost", false, dflt);
	}

	public String parseHost(XmlConfig cfg, String xpath, boolean mdty, String dflt)
	{
		String name = (xpath == null ? dflt : cfg.getValue(xpath, mdty, dflt));
		if (name != null) name = name.replace(TOKEN_HOSTNAME, hostName);
		return name;
	}
}
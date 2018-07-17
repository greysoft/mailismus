/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms;

import com.grey.base.config.XmlConfig;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.directory.Directory;
import com.grey.mailismus.directory.DirectoryFactory;
import com.grey.mailismus.ms.maildir.MaildirStore;

public class MessageStoreFactory
{
	private static final Class<? extends MessageStore> DFLTCLASS = MaildirStore.class;

	public MessageStore create(Dispatcher dsptch, XmlConfig cfg, Directory dtory)
	{
		if (!cfg.exists()) return null;
		Object obj = NAFConfig.createEntity(cfg, DFLTCLASS, MessageStore.class, false,
				new Class<?>[]{Dispatcher.class, XmlConfig.class, Directory.class},
				new Object[]{dsptch, cfg, dtory});
		dsptch.getLogger().info("Created MessageStore="+obj.getClass().getName());
		MessageStore ms = MessageStore.class.cast(obj);
		return ms;
	}

	public MessageStore create(Dispatcher dsptch, AppConfig appcfg)
	{
		XmlConfig cfg = appcfg.getConfigMS();
		Directory dtory = createDirectory(dsptch, appcfg);
		return create(dsptch, cfg, dtory);
	}

	private Directory createDirectory(Dispatcher dsptch, AppConfig appcfg) {
		DirectoryFactory factory = new DirectoryFactory();
		return factory.create(dsptch, appcfg.getConfigDirectory());
	}
}

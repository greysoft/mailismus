/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.directory;

import com.grey.base.config.XmlConfig;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.directory.files.FilesDirectory;

public class DirectoryFactory
{
	private static final Class<? extends Directory> DFLTCLASS = FilesDirectory.class;

	public Directory create(Dispatcher dsptch, XmlConfig cfg)
	{
		if (!cfg.exists()) return null;
		Object obj = NAFConfig.createEntity(cfg, DFLTCLASS, Directory.class, false,
				new Class<?>[]{Dispatcher.class, XmlConfig.class},
				new Object[]{dsptch, cfg});
		dsptch.getLogger().info("Created Directory="+obj.getClass().getName());
		Directory dtory = Directory.class.cast(obj);
		return dtory;
	}
}

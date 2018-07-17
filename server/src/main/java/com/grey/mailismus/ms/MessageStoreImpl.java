/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms;

import com.grey.base.config.XmlConfig;

public abstract class MessageStoreImpl
	implements MessageStore
{
	public final com.grey.naf.reactor.Dispatcher dsptch;
	private final com.grey.mailismus.directory.Directory directory;

	@Override
	public com.grey.mailismus.directory.Directory directory() {return directory;}

	public MessageStoreImpl(com.grey.naf.reactor.Dispatcher d, XmlConfig cfg, com.grey.mailismus.directory.Directory dtory)
	{
		dsptch = d;
		directory = dtory;
	}
}
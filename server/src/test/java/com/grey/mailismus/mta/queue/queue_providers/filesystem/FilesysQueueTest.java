/*
 * Copyright 2011-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.filesystem;

public class FilesysQueueTest
	extends com.grey.mailismus.mta.queue.ManagerTest
{
	private static final String cfgxml = "<deferred_maxignore>2s</deferred_maxignore>";

	@Override
	protected Class<?> getQueueClass() {return FilesysQueue.class;}
	@Override
	protected int getBulkMessageCount() {return 1000;}
	@Override
	protected String getQueueConfig() {return cfgxml;}
}
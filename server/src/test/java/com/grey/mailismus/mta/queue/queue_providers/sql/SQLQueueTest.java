/*
 * Copyright 2011-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue.queue_providers.sql;

import com.grey.base.config.SysProps;
import com.grey.mailismus.TestSupport;

public class SQLQueueTest
	extends com.grey.mailismus.mta.queue.ManagerTest
{
	static {
		TestSupport.loadDBDriver(logger);
		if (SysProps.get(SQLQueue.SYSPROP_FORCEFAILS) == null) SysProps.set(SQLQueue.SYSPROP_FORCEFAILS, false);
	}
	@Override
	protected boolean isTestSuiteRunnable() {return TestSupport.HAVE_DBDRIVERS;}
	@Override
	protected Class<?> getQueueClass() {return SQLQueue.class;}
	@Override
	protected boolean hasDatabase() {return true;}
	@Override
	protected boolean supportsHardLinks() {return false;}
}
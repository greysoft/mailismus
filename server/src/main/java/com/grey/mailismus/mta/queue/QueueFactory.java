/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.base.config.XmlConfig;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.mta.queue.queue_providers.filesystem_cluster.ClusteredQueue;

public class QueueFactory
{
	private static final Class<? extends QueueManager> DFLTCLASS = ClusteredQueue.class;

	public QueueManager create(Dispatcher dsptch, XmlConfig qcfg, AppConfig appcfg, String name)
	{
		Object obj = NAFConfig.createEntity(qcfg, DFLTCLASS, QueueManager.class, false,
				new Class<?>[]{Dispatcher.class, XmlConfig.class, AppConfig.class, String.class},
				new Object[]{dsptch, qcfg, appcfg, name});
		dsptch.getLogger().info("MTA: Created QueueManager="+name+" - type="+obj.getClass().getName());
		QueueManager qmgr = QueueManager.class.cast(obj);
		return qmgr;
	}

	public static QueueManager init(QueueFactory factory, Dispatcher dsptch, XmlConfig qcfg, AppConfig appcfg, String name) throws java.io.IOException
	{
		if (qcfg == null) qcfg = appcfg.getConfigQueue(name);
		QueueManager qmgr = factory.create(dsptch, qcfg, appcfg, name);
		if (qmgr != null) qmgr.start();
		return qmgr;
	}

	public static QueueManager init(Dispatcher dsptch, AppConfig appcfg, String name) throws java.io.IOException
	{
		return init(new QueueFactory(), dsptch, null, appcfg, name);
	}
}
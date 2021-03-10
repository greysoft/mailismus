/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit;

import com.grey.base.config.XmlConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.ListenerSet;
import com.grey.naf.reactor.config.ConcurrentListenerConfig;

public final class SubmitTask
	extends com.grey.mailismus.mta.MTA_Task
	implements com.grey.naf.EntityReaper
{
	private final ListenerSet listeners;

	public SubmitTask(String name, Dispatcher dsptch, XmlConfig cfg)
			throws java.io.IOException
	{
		super(name, dsptch, cfg, DFLT_FACT_DTORY, null, DFLT_FACT_QUEUE);
		String grpname = "SubmitTask="+getName();
		ConcurrentListenerConfig[] lcfg = ConcurrentListenerConfig.buildMultiConfig(grpname, dsptch.getApplicationContext().getConfig(), "listeners/listener", taskConfig(),
				com.grey.mailismus.mta.Protocol.TCP_PORT, com.grey.mailismus.mta.Protocol.TCP_SSLPORT, Server.Factory.class, null);
		listeners = new ListenerSet(grpname, dsptch, this, this, lcfg);
		if (listeners.configured() != 0) registerQueueOps(com.grey.mailismus.nafman.Loader.PREF_SHOWQ_SUBMIT);
	}

	@Override
	protected void startTask() throws java.io.IOException
	{
		if (listeners.configured() == 0) {
			nafletStopped();
			return;
		}
		listeners.start();
	}

	@Override
	protected boolean stopNaflet()
	{
		return listeners.stop();
	}

	@Override
	public void entityStopped(Object obj)
	{
		ListenerSet.class.cast(obj); //make sure it's the expected type
		nafletStopped();
	}
}
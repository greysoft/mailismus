/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit;

public final class SubmitTask
	extends com.grey.mailismus.mta.MTA_Task
	implements com.grey.naf.EntityReaper
{
	private final com.grey.naf.reactor.ListenerSet listeners;

	public SubmitTask(String name, com.grey.naf.reactor.Dispatcher dsptch, com.grey.base.config.XmlConfig cfg)
			throws java.io.IOException
	{
		super(name, dsptch, cfg, DFLT_FACT_DTORY, null, DFLT_FACT_QUEUE);
		java.util.Map<String,Object> cfgdflts = new java.util.HashMap<>();
		cfgdflts.put(com.grey.naf.reactor.CM_Listener.CFGMAP_FACTCLASS, Server.Factory.class);
		cfgdflts.put(com.grey.naf.reactor.CM_Listener.CFGMAP_PORT, Integer.valueOf(com.grey.mailismus.mta.Protocol.TCP_PORT));
		cfgdflts.put(com.grey.naf.reactor.CM_Listener.CFGMAP_SSLPORT, Integer.valueOf(com.grey.mailismus.mta.Protocol.TCP_SSLPORT));
		listeners = new com.grey.naf.reactor.ListenerSet("SubmitTask="+naflet_name, dsptch, this, this, "listeners/listener", taskConfig(), cfgdflts);
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
		com.grey.naf.reactor.ListenerSet.class.cast(obj); //make sure it's the expected type
		nafletStopped();
	}
}
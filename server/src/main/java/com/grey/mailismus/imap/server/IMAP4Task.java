/*
 * Copyright 2013-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

public class IMAP4Task
	extends com.grey.mailismus.Task
	implements com.grey.naf.EntityReaper
{
	private final com.grey.naf.reactor.ListenerSet listeners;

	public IMAP4Task(String name, com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg) throws java.io.IOException
	{
		super(name, d, cfg, DFLT_FACT_DTORY, DFLT_FACT_MS);
		java.util.Map<String,Object> cfgdflts = new java.util.HashMap<>();
		cfgdflts.put(com.grey.naf.reactor.CM_Listener.CFGMAP_FACTCLASS, com.grey.mailismus.imap.server.IMAP4Server.Factory.class);
		cfgdflts.put(com.grey.naf.reactor.CM_Listener.CFGMAP_PORT, Integer.valueOf(com.grey.mailismus.imap.IMAP4Protocol.TCP_PORT));
		cfgdflts.put(com.grey.naf.reactor.CM_Listener.CFGMAP_SSLPORT, Integer.valueOf(com.grey.mailismus.imap.IMAP4Protocol.TCP_SSLPORT));
		listeners = new com.grey.naf.reactor.ListenerSet("IMAP4Task="+getName(), getDispatcher(), this, this, "listeners/listener", taskConfig(), cfgdflts);
		if (listeners.configured() != 0) registerDirectoryOps(com.grey.mailismus.nafman.Loader.PREF_DTORY_IMAP4S);
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
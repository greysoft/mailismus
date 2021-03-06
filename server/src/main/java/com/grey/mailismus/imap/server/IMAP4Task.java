/*
 * Copyright 2013-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.base.config.XmlConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.ListenerSet;
import com.grey.naf.reactor.config.ConcurrentListenerConfig;
import com.grey.mailismus.imap.IMAP4Protocol;

public class IMAP4Task
	extends com.grey.mailismus.Task
	implements com.grey.naf.EntityReaper
{
	private final ListenerSet listeners;

	public IMAP4Task(String name, Dispatcher d, XmlConfig cfg) throws java.io.IOException
	{
		super(name, d, cfg, DFLT_FACT_DTORY, DFLT_FACT_MS);
		String grpname = "IMAP4Task="+getName();
		ConcurrentListenerConfig[] lcfg = ConcurrentListenerConfig.buildMultiConfig(grpname, getDispatcher(), "listeners/listener", taskConfig(),
				IMAP4Protocol.TCP_PORT, IMAP4Protocol.TCP_SSLPORT, IMAP4Server.Factory.class, null);
		listeners = new ListenerSet(grpname, getDispatcher(), this, this, lcfg);
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
		ListenerSet.class.cast(obj); //make sure it's the expected type
		nafletStopped();
	}
}
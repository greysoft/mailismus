/*
 * Copyright 2010-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.io.IOException;
import java.security.GeneralSecurityException;

import com.grey.base.config.XmlConfig;
import com.grey.naf.EventListenerNAF;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.mta.MTA_Task;
import com.grey.mailismus.nafman.Loader;

public final class DeliverTask
	extends MTA_Task
	implements EventListenerNAF
{
	private final Forwarder sender;

	public DeliverTask(String name, Dispatcher dsptch, XmlConfig cfg) throws IOException, GeneralSecurityException {
		super(name, dsptch, cfg, null, DFLT_FACT_MS, DFLT_FACT_QUEUE, createResolverDNS(dsptch));
		sender = new Forwarder(dsptch, this, taskConfig(), this, null);
		registerQueueOps(Loader.PREF_SHOWQ_DELIVER);
	}

	@Override
	protected void startTask() {
		sender.start();
	}

	@Override
	protected boolean stopNaflet() {
		return sender.stop();
	}

	// the only entity we launch is the SMTP Sender, so this must be it ... and that means we're now finished as well
	@Override
	public void eventIndication(String eventId, Object evtsrc, Object data) {
		getDispatcher().getLogger().info("DeliverTask="+getName()+" received event="+eventId+"/"+evtsrc.getClass().getName()+"/"+data);
		nafletStopped();
	}
}
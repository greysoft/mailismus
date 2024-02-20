/*
 * Copyright 2010-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import com.grey.base.config.XmlConfig;
import com.grey.naf.EntityReaper;
import com.grey.naf.reactor.Dispatcher;
import com.grey.mailismus.mta.MTA_Task;
import com.grey.mailismus.nafman.Loader;

public final class DeliverTask
	extends MTA_Task
	implements EntityReaper
{
	private final Forwarder sender;

	public DeliverTask(String name, Dispatcher dsptch, XmlConfig cfg) throws java.io.IOException {
		super(name, dsptch, cfg, null, DFLT_FACT_MS, DFLT_FACT_QUEUE, createResolverDNS(dsptch));
		sender = new Forwarder(dsptch, this, taskConfig(), this);
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
	public void entityStopped(Object obj) {
		nafletStopped();
	}
}
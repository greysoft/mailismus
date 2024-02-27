/*
 * Copyright 2012-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.pop3.client;

import com.grey.base.utils.TimeOps;
import com.grey.logging.Logger;
import com.grey.base.config.XmlConfig;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.naf.EventListenerNAF;

public class DownloadTask
	extends com.grey.mailismus.Task
	implements com.grey.naf.reactor.TimerNAF.Handler, EventListenerNAF
{
	private final com.grey.base.collections.HashedSet<DownloadClient> activeClients
									= new com.grey.base.collections.HashedSet<DownloadClient>();
	private final com.grey.base.collections.HashedSet<com.grey.naf.reactor.TimerNAF> activeTimers
									= new com.grey.base.collections.HashedSet<com.grey.naf.reactor.TimerNAF>();
	private final DownloadClient.Common clientDefs;
	private final EventListenerNAF observer;
	private final Logger logger;
	private boolean inShutdown;

	public DownloadTask(String name, com.grey.naf.reactor.Dispatcher d, XmlConfig cfg)
			throws java.io.IOException, java.security.GeneralSecurityException
	{
		this(name, d, cfg, 0, null, null);
	}

	public DownloadTask(String name, com.grey.naf.reactor.Dispatcher d, XmlConfig cfg,
			int srvport, EventListenerNAF evtl, java.util.List<String> clients)
			throws java.io.IOException, java.security.GeneralSecurityException
	{
		super(name, d, cfg, DFLT_FACT_DTORY, DFLT_FACT_MS, null);
		logger = d.getLogger();
		observer = evtl;
		com.grey.base.config.XmlConfig taskcfg = taskConfig();
		XmlConfig[] clientcfg = taskcfg.getSections("clients/client"+XmlConfig.XPATH_ENABLED);
		if (clientcfg == null) {
			logger.warn("POP3-Download: No clients defined");
			clientDefs = null;
			abortOnStartup();
			return;
		}
		long delay = taskcfg.getTime("delay_start", TimeOps.parseMilliTime("1m"));
		clientDefs = new DownloadClient.Common(taskcfg, getDispatcher(), this);
		java.util.Set<String> idset = new com.grey.base.collections.HashedSet<String>();

		for (int idx = 0; idx != clientcfg.length; idx++) {
			String id = clientcfg[idx].getValue("@id", true, null);
			if (clients != null && !clients.contains(id)) continue;
			if (!idset.add(id)) {
				throw new MailismusConfigException("POP3 Download: Duplicate id="+id+" in client-config "+(idx+1)+"/"+clientcfg.length);
			}
			DownloadClient client = new DownloadClient(getDispatcher(), clientDefs, clientcfg[idx], id, srvport, observer != null);
			scheduleClient(client, delay);
		}
		logger.info("POP3-Download: Scheduled clients="+idset.size()+" with delay="+TimeOps.expandMilliTime(delay));
		registerDirectoryOps(com.grey.mailismus.nafman.Loader.PREF_DTORY_POP3C);
	}

	@Override
	protected boolean stopNaflet()
	{
		inShutdown = true;
		boolean done = true;
		java.util.ArrayList<DownloadClient> clients = new java.util.ArrayList<DownloadClient>(activeClients);
		for (int idx = 0; idx != clients.size(); idx++) {
			if (!clients.get(idx).stop()) done = false;
		}
		java.util.ArrayList<com.grey.naf.reactor.TimerNAF> timers = new java.util.ArrayList<com.grey.naf.reactor.TimerNAF>(activeTimers);
		for (int idx = 0; idx != timers.size(); idx++) {
			timers.get(idx).cancel();
		}
		logger.info("POP3-Download: Stopped clients="+clients.size()+", Timers="+timers.size());
		if (done) stopped();
		return done;
	}

	private void stopped()
	{
		if (clientDefs != null) clientDefs.stop(getDispatcher());
		logger.info("POP3-Download: Shutdown completed");
	}

	@Override
	public void eventIndication(Object obj, String eventId)
	{
		DownloadClient client = DownloadClient.class.cast(obj);
		boolean active = activeClients.remove(client);
		if (observer != null) observer.eventIndication(client, eventId);
		if (inShutdown) {
			if (activeClients.size() == 0) stopped();
			return;
		}
		if (!active) return;

		if (client.getRunCount() == client.maxruns) {
			logger.info("POP3-Download: Halting client="+client.client_id+" as it has completed configured runs="+client.maxruns);
			if (activeTimers.size() == 0) {
				//... and we have no remaining scheduled clients, so we're done
				logger.info("POP3-Download: Halting as no scheduled clients remain");
				stopDispatcherRunnable();
			}
			return;
		}
		scheduleClient(client, client.freq);
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d)
			throws java.io.IOException
	{
		if (inShutdown) return;
		activeTimers.remove(tmr);
		DownloadClient client = DownloadClient.class.cast(tmr.getAttachment());
		activeClients.add(client);
		client.start(this);
	}

	private void scheduleClient(DownloadClient client, long delay)
	{
		com.grey.naf.reactor.TimerNAF tmr = getDispatcher().setTimer(delay, 0, this, client);
		activeTimers.add(tmr);
	}

	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex)
	{
		// Nothing to do - error already logged by Dispatcher
	}
}
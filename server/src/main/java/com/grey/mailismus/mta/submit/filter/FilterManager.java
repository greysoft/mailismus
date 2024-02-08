/*
 * Copyright 2013-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.TSAP;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.Producer;
import com.grey.naf.reactor.TimerNAF;
import com.grey.mailismus.mta.submit.Server;
import com.grey.mailismus.mta.submit.filter.api.FilterFactory;
import com.grey.mailismus.mta.submit.filter.api.MessageFilter;

public class FilterManager
	implements Producer.Consumer<FilterExecutor>
{
	private final FilterFactory filter_factory;
	private final ExecutorService threadpool;
	private final Producer<FilterExecutor> resultsChannel;

	public FilterManager(XmlConfig cfg, Dispatcher dsptch) throws java.io.IOException {
		threadpool = dsptch.getApplicationContext().getThreadpool();
		filter_factory = createFilterFactory(cfg, dsptch);
		resultsChannel = new Producer<>("Filter-results", dsptch, this);

		TimerNAF.Handler onStart = new TimerNAF.Handler() {
			@Override
			public void timerIndication(TimerNAF t, Dispatcher d) throws IOException {
				resultsChannel.startDispatcherRunnable(); //need to call this within Dispatcher thread
			}
		};
		dsptch.setTimer(0, 0, onStart);
	}

	public void shutdown() {
		threadpool.shutdownNow();
		resultsChannel.stopDispatcherRunnable();
	}

	public FilterExecutor approveMessage(Server server,
			TSAP remote, ByteChars authuser, ByteChars helo_name,
			ByteChars sender, ArrayList<EmailAddress> recips, java.nio.file.Path msg) {
		MessageFilter filter = filter_factory.create();
		FilterExecutor executor = new FilterExecutor(remote, authuser, helo_name, sender, recips, msg, filter, server, resultsChannel);
		threadpool.execute(executor);
		return executor;
	}

	@Override
	public void producerIndication(Producer<FilterExecutor> p) {
		FilterExecutor executor;
		while ((executor = p.consume()) != null) {
			Server srvr = executor.getServer();
			if (srvr == null) continue; //server must have terminated
			srvr.messageFilterCompleted(executor);
		}
	}

	private static FilterFactory createFilterFactory(XmlConfig cfg, Dispatcher dsptch) {
		Object obj = NAFConfig.createEntity(cfg, null, FilterFactory.class, false,
				new Class<?>[]{XmlConfig.class},
				new Object[]{cfg});
		FilterFactory factory = FilterFactory.class.cast(obj);
		// create (and discard) a filter object, to make sure we can do so
		MessageFilter tmp = factory.create();
		dsptch.getLogger().info("SMTP MessageFilter="+tmp.getClass().getName()+"/"+tmp+" - factory="+factory.getClass().getName()+"/"+factory);
		return factory;
	}
}
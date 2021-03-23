/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.StringOps;
import com.grey.mailismus.directory.DirectoryFactory;
import com.grey.mailismus.ms.MessageStoreFactory;
import com.grey.mailismus.mta.queue.QueueManager;
import com.grey.mailismus.mta.queue.QueueFactory;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.nafman.NafManRegistry;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.nafman.NafManCommand;
import com.grey.mailismus.nafman.Loader;

public class MTA_Task
	extends com.grey.mailismus.Task
{
	public static final QueueFactory DFLT_FACT_QUEUE = new QueueFactory();

	private final QueueManager qmgr;
	private final StringBuilder tmpsb = new StringBuilder(); //pre-allocated merely for efficiency

	public QueueManager getQueue() {return qmgr;}

	public MTA_Task(String name, Dispatcher dsptch, XmlConfig cfg,
			DirectoryFactory df, MessageStoreFactory msf, QueueFactory qf, ResolverDNS dns) throws java.io.IOException
	{
		super(name, dsptch, cfg, df, msf, dns);
		qmgr = (qf == null ? null : QueueFactory.init(qf, dsptch, null, getAppConfig(), name));
	}

	public void registerQueueOps(int pref)
	{
		if (getDispatcher().getNafManAgent() == null) return;
		NafManRegistry reg = getDispatcher().getNafManAgent().getRegistry();
		if (getQueue() != null && getQueue().supportsShow()) {
			reg.registerHandler(Loader.CMD_LISTQ, pref, this, getDispatcher());
		}
		reg.registerHandler(Loader.CMD_COUNTQ, pref, this, getDispatcher());
	}

	@Override
	public CharSequence handleNAFManCommand(NafManCommand cmd) throws java.io.IOException
	{
		tmpsb.setLength(0);
		if (cmd.getCommandDef().code.equals(Loader.CMD_COUNTQ)) {
			String sender = cmd.getArg("sender");
			String recip = cmd.getArg("recip");
			int total = getQueue().qsize(sender, recip, 0);
			if (total == -1) {
				tmpsb.append("Unsupported operation");
			} else {
				int retrycnt = (total == 0 ? 0 : getQueue().qsize(sender, recip, QueueManager.SHOWFLAG_TEMPERR));
				tmpsb.append("Total Messages: ").append(total).append("<br/>");
				tmpsb.append("Retry Messages: ").append(retrycnt);
			}
		} else if (cmd.getCommandDef().code.equals(Loader.CMD_LISTQ)) {
			int maxmessages = 1000;
			int flags =0;
			String sender = cmd.getArg("sender");
			String recip = cmd.getArg("recip");
			if (StringOps.stringAsBool(cmd.getArg("revsort"))) flags |= QueueManager.SHOWFLAG_REVSORT;
			String s = cmd.getArg("max");
			if (s != null) {
				try {
					maxmessages = Integer.parseInt(s);
				} catch (NumberFormatException ex) {
					//don't want a user-supplied param to cause a big ugly stack dump, so handle it
					return tmpsb.append("<summary total=\"Bad max="+s.replace('"', '\'')+"\"/>");
				}
			}
			getQueue().show(sender, recip, maxmessages, flags, tmpsb);
		} else {
			return super.handleNAFManCommand(cmd);
		}
		return tmpsb;
	}
}
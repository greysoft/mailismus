/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.IP;
import com.grey.base.utils.EmailAddress;
import com.grey.naf.nafman.NafManRegistry;
import com.grey.naf.nafman.NafManCommand;
import com.grey.naf.reactor.Dispatcher;
import com.grey.logging.Factory;
import com.grey.logging.Parameters;
import com.grey.logging.Logger;

import com.grey.mailismus.nafman.Loader;
import com.grey.mailismus.mta.queue.MessageRecip;

public final class Audit
	implements NafManCommand.Handler
{
	private final Logger audlog;
	private final Dispatcher dsptch;
	private final StringBuilder sb = new StringBuilder();  //preallocated for efficiency

	public String getPath() {return audlog.getPathTemplate();}
	public String getActivePath() {return audlog.getActivePath();}
	@Override
	public CharSequence nafmanHandlerID() {return "audit-logger="+audlog.getName();}

	public static Audit create(String name, String xpath, Dispatcher dsptch, XmlConfig cfg) throws java.io.IOException
	{
		if (xpath != null) cfg = cfg.getSection(xpath);
		if (!cfg.exists()) return null;
		Parameters params = new Parameters(cfg);
		String pthnam = params.getPathname();
		if (pthnam == null || pthnam.length() == 0) return null;
		return new Audit(name, dsptch, params);
	}

	private Audit(String name, Dispatcher d, Parameters params) throws java.io.IOException
	{
		dsptch = d;
		String id = name;
		name = "audit-"+name;
		if (dsptch != null) name += "-" + dsptch.getName();
		params = new Parameters.Builder(params)
				.withQuietMode(true)
				.withTID(false)
				.withPID(false)
				.build();
		audlog = Factory.getLogger(params, name);
		if (dsptch != null) {
			if (dsptch.getNafManAgent() != null) {
				String rsrc = NafManRegistry.RSRC_PLAINTEXT;
				NafManRegistry reg = dsptch.getNafManAgent().getRegistry();
				reg.registerDynamicCommand(Loader.CMDSTEM_DUMPAUDIT+id, this, dsptch, "Mailismus-Audit", rsrc, true, "Display audit log for "+id);
			}
			dsptch.getFlusher().register(audlog);
			dsptch.getLogger().info("Created Audit-Logger: "+audlog);
		}
	}

	public void close()
	{
		if (dsptch != null) dsptch.getFlusher().unregister(audlog);
		audlog.close();
	}

	public void log(CharSequence msg)
	{
		audlog.log(Logger.LEVEL.ALL, msg);
	}

	//MTA-oriented method
	public void log(String action, MessageRecip recip, boolean withstatus, long systime, CharSequence extspid)
	{
		long transit = systime - recip.recvtime;
		sb.setLength(0);
		sb.append(action).append(" id=").append(extspid).append('-').append(recip.qid).append("; From=");
		if (recip.sender != null) sb.append(recip.sender);

		if (recip.ip_recv == 0) {
			sb.append(" src=MTA");
		} else {
			sb.append(" via ");
			IP.displayDottedIP(recip.ip_recv, sb);
		}
		sb.append("; To=").append(recip.mailbox_to);
		if (recip.domain_to != null) sb.append(EmailAddress.DLM_DOM).append(recip.domain_to);

		if (recip.ip_send != 0) {
			sb.append(" via ");
			IP.displayDottedIP(recip.ip_send, sb);
		}
		if (withstatus) sb.append("; Status=").append(recip.smtp_status);
		sb.append(" - Transit=");
		TimeOps.expandMilliTime(transit, sb, false);
		log(sb);
	}

	@Override
	public CharSequence handleNAFManCommand(NafManCommand cmd) throws java.io.IOException
	{
		String content = FileOps.readAsText(getActivePath(), null);
		if (content == null) content = "File is empty";
		return "Dumping current audit log - "+getActivePath()+"\r\n\r\n"+content;
	}
}
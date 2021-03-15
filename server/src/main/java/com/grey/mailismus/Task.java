/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import java.net.UnknownHostException;
import java.util.List;

import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.NIOBuffers;
import com.grey.logging.Logger;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.nafman.NafManRegistry;
import com.grey.naf.dns.resolver.ResolverConfig;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.nafman.NafManCommand;

import com.grey.mailismus.nafman.Loader;
import com.grey.mailismus.ms.MessageStore;
import com.grey.mailismus.ms.MessageStoreFactory;
import com.grey.mailismus.directory.Directory;
import com.grey.mailismus.directory.DirectoryFactory;
import com.grey.mailismus.errors.MailismusConfigException;

public class Task
	extends com.grey.naf.Naflet
	implements NafManCommand.Handler
{
	public static final DirectoryFactory DFLT_FACT_DTORY = new DirectoryFactory();
	public static final MessageStoreFactory DFLT_FACT_MS = new MessageStoreFactory();

	//needs to be much smaller than the idle timeout - avoids having to constantly reset timer for fast connections
	public static final long MIN_RESET_PERIOD = SysProps.getTime("mailismus.timers.minreset", "2s");

	private static final boolean NIODIRECT_CONSTBUF = SysProps.get("grey.mailismus.niodirect", true);

	private final AppConfig appcfg;
	private final MessageStore ms;
	private final Directory dtory;
	private final ResolverDNS dnsResolver;
	private final StringBuilder tmpsb = new StringBuilder(); //pre-allocated merely for efficiency

	public AppConfig getAppConfig() {return appcfg;}
	public MessageStore getMS() {return ms;}
	public Directory getDirectory() {return dtory;}
	public final ResolverDNS getResolverDNS() {return dnsResolver;}

	protected void startTask() throws java.io.IOException {}

	@Override
	public CharSequence nafmanHandlerID() {return getName();}

	public Task(String name, Dispatcher d, XmlConfig cfg, DirectoryFactory df, MessageStoreFactory msf, ResolverDNS dns) throws java.io.IOException {
		super(name, d, cfg);
		Logger logger = d.getLogger();
		appcfg = AppConfig.get(taskConfigFile(), d);
		Loader.get(d.getApplicationContext()).register(this);
		dnsResolver = (dns == null ? null: dns);

		if (df != null) {
			dtory = df.create(getDispatcher(), appcfg.getConfigDirectory());
			if (dtory == null) logger.info("Directory not configured for "+name+"="+this);
		} else {
			dtory = null;
		}

		if (msf != null) {
			ms = msf.create(getDispatcher(), appcfg.getConfigMS(), dtory);
			if (ms == null) {
				logger.info("Message-Store not configured for "+name+"="+this);
			} else {
				if (ms.directory() != dtory) throw new MailismusConfigException("Message-Store Directory mismatch - "+ms.directory()+" vs "+dtory);
			}
		} else {
			ms = null;
		}
		logger.trace("Naflet="+getName()+": Message-Store="+(ms==null?"N":"Y")+"; Directory="+(dtory==null?"N":"Y")
							+"; niodirect_const="+NIODIRECT_CONSTBUF);
	}

	@Override
	protected void startNaflet() throws java.io.IOException {
		startTask();
	}

	public void registerDirectoryOps(int pref) {
		if (getDirectory() == null || getDispatcher().getNafManAgent() == null) return;
		NafManRegistry reg = getDispatcher().getNafManAgent().getRegistry();
		reg.registerHandler(Loader.CMD_DTORY_RELOAD, pref, this, getDispatcher());
		reg.registerHandler(Loader.CMD_DTORY_ALIAS, pref, this, getDispatcher());
	}

	@Override
	public CharSequence handleNAFManCommand(NafManCommand cmd) throws java.io.IOException {
		tmpsb.setLength(0);
		if (cmd.getCommandDef().code.equals(Loader.CMD_DTORY_RELOAD)) {
			getDirectory().reload();
			tmpsb.append("Directory has been reloaded");
		} else if (cmd.getCommandDef().code.equals(Loader.CMD_DTORY_ALIAS)) {
			String v = cmd.getArg(NafManCommand.ATTR_KEY);
			ByteChars alias = new ByteChars(v);
			List<ByteChars> members = getDirectory().expandAlias(alias);
			tmpsb.append("Expanding alias=").append(alias).append(" - ");
			if (members == null) {
				tmpsb.append("No such alias");
			} else {
				tmpsb.append("Members=").append(members.size()).append(':');
				for (int idx = 0; idx != members.size(); idx++) {
					tmpsb.append("<br/>").append(members.get(idx));
				}
			}
		} else {
			getDispatcher().getLogger().error("Task="+getName()+": Missing case for NAFMAN cmd="+cmd.getCommandDef().code);
			return null;
		}
		return tmpsb;
	}

	public static ResolverDNS createResolverDNS(Dispatcher d) throws UnknownHostException {
		ResolverConfig rcfg = new ResolverConfig.Builder()
				.withXmlConfig(d.getApplicationContext().getConfig().getNode("dnsresolver"))
				.build();
		return ResolverDNS.create(d, rcfg);
	}

	// Buffers we never read from and whose backing array we never access should be able to benefit from being
	// direct, so control that setting independently of the BufferSpec class.
	public static java.nio.ByteBuffer constBuffer(CharSequence data) {
		return NIOBuffers.encode(data, null, NIODIRECT_CONSTBUF).asReadOnlyBuffer();
	}
}

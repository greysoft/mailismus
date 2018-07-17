/*
 * Copyright 2011-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.nafman;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.grey.mailismus.Task;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.nafman.NafManRegistry;

public final class Loader
{
	public static final String CMD_LISTQ = "QLIST";
	public static final String CMD_COUNTQ = "QCOUNT";
	public static final String CMD_SENDQ = "QSEND";
	public static final String CMD_LISTGREY = "GREYLIST";
	public static final String CMD_COUNTERS = "MTACOUNTERS";
	public static final String CMD_DTORY_RELOAD = "DIRECTORY_RELOAD";
	public static final String CMD_DTORY_ALIAS = "DIRECTORY_ALIAS";
	public static final String CMDSTEM_DUMPAUDIT = "DumpAudit-";

	private static final String FAMILY_MTA = "Mailismus-MTA";
	private static final String FAMILY_DIRECTORY = "Mailismus-Directory";

	public static final int PREF_SHOWQ_REPORT = 1;
	public static final int PREF_SHOWQ_DELIVER = 2;
	public static final int PREF_SHOWQ_SUBMIT = 3;

	public static final int PREF_DTORY_POP3C = 1;
	public static final int PREF_DTORY_POP3S = 2;
	public static final int PREF_DTORY_IMAP4S = 3;
	public static final int PREF_DTORY_SMTPS = 4;

	private static final String RSRC_QLIST = "mtaqlist";
	private static final String RSRC_GREYLIST = "greylist";
	private static final String CP = "/"+Loader.class.getPackage().getName().replace('.', '/')+"/";

	private static final NafManRegistry.DefCommand[] cmds = new NafManRegistry.DefCommand[] {
		new NafManRegistry.DefCommand(CMD_LISTQ, FAMILY_MTA, "List queued messages", RSRC_QLIST, true),
		new NafManRegistry.DefCommand(CMD_COUNTQ, FAMILY_MTA, "Show queue size", NafManRegistry.RSRC_CMDSTATUS, true),
		new NafManRegistry.DefCommand(CMD_SENDQ, FAMILY_MTA, "Deliver queue now", NafManRegistry.RSRC_CMDSTATUS, true),
		new NafManRegistry.DefCommand(CMD_LISTGREY, FAMILY_MTA, "Show greylist correspondents", RSRC_GREYLIST, true),
		new NafManRegistry.DefCommand(CMD_COUNTERS, FAMILY_MTA, "Show (and reset) MTA stats counters", NafManRegistry.RSRC_CMDSTATUS, true),
		new NafManRegistry.DefCommand(CMD_DTORY_ALIAS, FAMILY_DIRECTORY, "Show alias expansion in Directory", null, true),
		new NafManRegistry.DefCommand(CMD_DTORY_RELOAD, FAMILY_DIRECTORY, "Reload Directory", NafManRegistry.RSRC_CMDSTATUS, false)
	};

	private static final NafManRegistry.DefResource[] resources = new NafManRegistry.DefResource[] {
		new NafManRegistry.DefResource("mailhome", null, null, new HomePage()), //must be first entry, subsequent order doesn't matter
		new NafManRegistry.DefResource(RSRC_QLIST, CP+"mtaqlist.xsl", null, null),
		new NafManRegistry.DefResource("mtaqcsv", CP+"mtaqcsv.xsl", null, null),
		new NafManRegistry.DefResource(RSRC_GREYLIST, CP+"greylist.xsl", null, null),
		new NafManRegistry.DefResource("greycsv", CP+"greycsv.xsl", null, null)
	};

	public static Loader get(ApplicationContextNAF appctx) {
		NafManRegistry reg = NafManRegistry.get(appctx);
		return appctx.getNamedItem(Loader.class.getName(), (c) -> new Loader(reg));
	}

	private final Map<Class<?>, Task> tasks = new ConcurrentHashMap<>();

	private Loader(NafManRegistry reg) {
		reg.loadCommands(cmds);
		reg.loadResources(resources);
		reg.setHomePage(resources[0].name);
	}

	public void register(Task task) {
		tasks.put(task.getClass(), task);
	}

	public Task getTask(Class<?> clss) {
		return tasks.get(clss);
	}
}
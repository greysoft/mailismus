/*
 * Copyright 2013-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.nafman;

import com.grey.base.utils.StringOps;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.DynLoader;
import com.grey.base.utils.PkgInfo;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.nafman.NafManRegistry;

import com.grey.mailismus.Task;
import com.grey.mailismus.imap.server.IMAP4Task;
import com.grey.mailismus.mta.deliver.DeliverTask;
import com.grey.mailismus.mta.reporting.ReportsTask;
import com.grey.mailismus.mta.submit.SubmitTask;
import com.grey.mailismus.pop3.client.DownloadTask;
import com.grey.mailismus.pop3.server.ServerTask;

public final class HomePage
	implements NafManRegistry.DefResource.DataGenerator
{
	private static final String BEGINTOKEN = "_BEGINTOKEN_";
	private static final String ENDTOKEN = "_ENDTOKEN_";
	private static final String SUBTOKEN = "_SUBTOKEN_";
	private static final String TOKEN_HEADER = "HEADER";
	private static final String TOKEN_MTA = "MTA";
	private static final String TOKEN_MTAQ = "QUEUE";
	private static final String TOKEN_GRYLST = "GREYLIST";
	private static final String TOKEN_DTORY = "DIRECTORY";
	private static final String TOKEN_MTASTATS_SRV = "MTASTATS_SRV";
	private static final String TOKEN_MTASTATS_DLV = "MTASTATS_DLV";
	private static final String TOKEN_MTASTATS_RPT = "MTASTATS_RPT";
	private static final String TOKEN_DUMPAUDIT = "DUMPAUDIT";
	private static final String TOKEN_DUMPAUDIT_CMD = "DUMPAUDIT_CMD";
	private static final String TOKEN_DUMPAUDIT_DESCR = "DUMPAUDIT_DESCR";
	private static final String TOKEN_VERSION = "MAILVER";

	private byte[] rspdata; //cannot be built in constructor, as MTA setup still incomplete

	//this method will only get called in the NAFMAN-Primary thread, so no MT issues
	@Override
	public byte[] generateResourceData(NafManRegistry.DefResource def, Dispatcher d)
		throws java.io.IOException
	{
		if (rspdata != null) return rspdata;
		java.net.URL url = DynLoader.getResource("home.htm", getClass());
		if (url == null) return null;
		NafManRegistry reg = d.getAgent().getRegistry();
		Loader loader = Loader.get(d.getApplicationContext());

		java.io.InputStream strm = url.openStream();
		String filetxt = FileOps.readAsText(strm, null);

		CharSequence mailver = PkgInfo.getVersion(getClass(), null);
		mailver = (mailver == null ? "Unknown" : mailver.toString());

		filetxt = renderHeader(TOKEN_HEADER, loader, filetxt);
		filetxt = renderSection(TOKEN_MTA, Loader.CMD_COUNTERS, reg, filetxt);
		filetxt = renderSection(TOKEN_MTAQ, Loader.CMD_LISTQ, reg, filetxt);
		filetxt = renderSection(TOKEN_GRYLST, Loader.CMD_LISTGREY, reg, filetxt);
		filetxt = renderSection(TOKEN_DTORY, Loader.CMD_DTORY_RELOAD, reg, filetxt);
		filetxt = renderStatsButton(SubmitTask.class, TOKEN_MTASTATS_SRV, loader, filetxt);
		filetxt = renderStatsButton(DeliverTask.class, TOKEN_MTASTATS_DLV, loader, filetxt);
		filetxt = renderStatsButton(ReportsTask.class, TOKEN_MTASTATS_RPT, loader, filetxt);
		filetxt = renderAuditButtons(reg, filetxt);
		filetxt = replaceToken(TOKEN_VERSION, mailver.toString(), filetxt);

		rspdata = filetxt.getBytes(StringOps.DFLT_CHARSET);
		return rspdata;
	}

	private String renderHeader(String token, Loader loader, String filetxt)
	{
		StringBuilder sb = new StringBuilder(256);
		sb.append("The following Mailismus components are configured:<br/>");
		sb.append("<ul>");
		renderHeaderTask(SubmitTask.class, "MTA - SMTP-Server", loader, sb);
		renderHeaderTask(DeliverTask.class, "MTA - SMTP-Client", loader, sb);
		renderHeaderTask(ReportsTask.class, "MTA - Report-Generator", loader, sb);
		renderHeaderTask(IMAP4Task.class, "IMAP Server", loader, sb);
		renderHeaderTask(ServerTask.class, "POP Server", loader, sb);
		renderHeaderTask(DownloadTask.class, "POP Downloader", loader, sb);
		sb.append("</ul>");
		return replaceToken(token, sb.toString(), filetxt);
	}

	private void renderHeaderTask(Class<? extends Task> clss, String txt, Loader loader, StringBuilder sb)
	{
		Task task = loader.getTask(clss);
		if (task == null) return;
		sb.append("<li>").append(txt).append(" (Dispatcher is ").append(task.getDispatcher().name).append(")</li>");
	}

	private String renderStatsButton(Class<? extends Task> clss, String token, Loader loader, String txt)
	{
		Task task = loader.getTask(clss);
		if (task == null) return removeSection(token, txt);
		txt = includeSection(token, txt);
		return replaceToken(token, task.getDispatcher().name, txt);
	}

	private String renderAuditButtons(NafManRegistry reg, String filetxt)
	{
		String template = getSection(TOKEN_DUMPAUDIT, filetxt);
		java.util.List<NafManRegistry.DefCommand> cmds = reg.getMatchingCommands(Loader.CMDSTEM_DUMPAUDIT);
		if (template == null || cmds == null || cmds.size() == 0) return removeSection(TOKEN_DUMPAUDIT, filetxt);
		String section = "";
		for (int idx = 0; idx != cmds.size(); idx++) {
			NafManRegistry.DefCommand cmd = cmds.get(idx);
			String txt = replaceToken(TOKEN_DUMPAUDIT_CMD, cmd.code, template);
			txt = replaceToken(TOKEN_DUMPAUDIT_DESCR, cmd.descr, txt);
			section += txt;
		}
		return insertSection(TOKEN_DUMPAUDIT, section, filetxt);
	}

	private String renderSection(String token, String indicative_cmd, NafManRegistry reg, String txt)
	{
		if (reg.isCommandRegistered(indicative_cmd)) {
			txt = includeSection(token, txt);
		} else {
			txt = removeSection(token, txt);
		}
		return txt;
	}

	private String includeSection(String token, String txt)
	{
		String token_start = BEGINTOKEN+token+"_";
		String token_end = ENDTOKEN+token+"_";
		txt = txt.replace(token_start, "");
		txt = txt.replace(token_end, "");
		return txt;
	}

	private String removeSection(String token, String txt)
	{
		String token_start = BEGINTOKEN+token+"_";
		String token_end = ENDTOKEN+token+"_";
		int pos1 = txt.indexOf(token_start);
		int pos2 = txt.indexOf(token_end);
		if (pos1 == -1 || pos2 == -1) return txt;
		pos2 += token_end.length();
		txt = txt.substring(0, pos1)+txt.substring(pos2);
		return txt;
	}

	private String getSection(String token, String txt)
	{
		String token_start = BEGINTOKEN+token+"_";
		String token_end = ENDTOKEN+token+"_";
		int pos1 = txt.indexOf(token_start);
		int pos2 = txt.indexOf(token_end);
		if (pos1 == -1 || pos2 == -1) return null;
		return txt.substring(pos1+token_start.length(), pos2);
	}

	private String insertSection(String token, String section, String txt)
	{
		String token_start = BEGINTOKEN+token+"_";
		String token_end = ENDTOKEN+token+"_";
		int pos1 = txt.indexOf(token_start);
		int pos2 = txt.indexOf(token_end);
		if (pos1 == -1 || pos2 == -1) return null;
		return txt.substring(0, pos1) + section + txt.substring(pos2+token_end.length());
	}

	private String replaceToken(String token, String val, String txt)
	{
		return txt.replace(SUBTOKEN+token+"_", val);
	}
}
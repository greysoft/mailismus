/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.CommandParser;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.Launcher;
import com.grey.mailismus.directory.DirectoryImpl;

public class MailismusApp
	extends Launcher
{
	private static final String[] opts = new String[]{"digest:"};

	private final OptsHandler options = new OptsHandler();

	public static void main(String[] args) throws Exception {
		MailismusApp app = new MailismusApp(args);
		app.execute("Mailismus");
	}

	public MailismusApp(String[] args) {
		super(args);
		com.grey.base.utils.PkgInfo.announceJAR(getClass(), "Mailismus", null);
		cmdParser.addHandler(options);
	}

	@Override
	protected void appExecute(ApplicationContextNAF appctx, int param1) throws Exception {
		if (options.plaintxt != null) {
			ByteChars plain = new ByteChars(options.plaintxt);
			char[] digest = DirectoryImpl.passwordHash(plain);
			System.out.println("Hashed to ["+new String(digest)+"]");
		} else {
			super.appExecute(appctx, param1);
		}
	}

	@Override
	protected void setupNafMan(ApplicationContextNAF appctx) {
		com.grey.mailismus.nafman.Loader.get(appctx);
	}


	private static class OptsHandler
		extends CommandParser.OptionsHandler
	{
		String plaintxt;

		public OptsHandler() {super(opts, 0, -1);}

		@Override
		public void setOption(String opt, String val) {
			if (opt.equals("digest")) {
				plaintxt = val;
			} else {
				super.setOption(opt);
			}
		}

		@Override
		public String displayUsage() {
			String txt = Launcher.displayUsage();
			txt += "\nMailismus options:";
			txt += "\n\t-digest plaintext";
			return txt;
		}
	}
}
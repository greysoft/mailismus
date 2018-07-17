/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.reporting;

import com.grey.base.config.XmlConfig;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.mta.Protocol;

final class Templates
{
	public static final String TOKEN_BNDRY = "%BNDRY%";
	public static final String TOKEN_MTARPT = "%REPMTA%";
	public static final String TOKEN_ADDRFROM = "%FROMADDR%";
	public static final String TOKEN_RPTSUBJ = "%SUBJ%";

	public String mime_altprologue = "This is a MIME-formatted message";

	public String ndr_basehdrs = "Return-Path: <>" + Protocol.EOL
		+ "From: " + TOKEN_ADDRFROM + Protocol.EOL
		+ "MIME-Version: 1.0" + Protocol.EOL
		+ "Content-Type: multipart/report; report-type=delivery-status;" + Protocol.EOL
		+ "\tboundary=\""+TOKEN_BNDRY+"\"" + Protocol.EOL
		+ "Subject: " + TOKEN_RPTSUBJ + Protocol.EOL
		+ "Date:";

	public String ndr_dsnpart_hdr = "Reporting-MTA: dns; "+TOKEN_MTARPT + Protocol.EOL
		+ "Arrival-Date:";

	public String ndr_textpart = "This is the "+AppConfig.TOKEN_PRODNAME+" mailserver at "+TOKEN_MTARPT+"." + Protocol.EOL
		+ "Your message could not be delivered - the details are attached.";

	public String mime_boundary;

	public Templates(XmlConfig cfg, String mtarep, AppConfig appConfig)
	{
		StringBuilder strbuf = new StringBuilder(java.util.UUID.randomUUID().toString());
		if (strbuf.length() > 35) strbuf.setLength(35);

		for (int idx = 0; idx != strbuf.length(); idx++)
		{
			if (!Character.isLetterOrDigit(strbuf.charAt(idx))) strbuf.setCharAt(idx, (char)('A' + (idx % 26)));
		}
		strbuf.append("/").append(mtarep);
		mime_boundary = strbuf.toString();

		mime_altprologue = cfg.getValue("altmimeprologue", false, mime_altprologue);
		ndr_textpart = cfg.getValue("ndr_textpart", false, ndr_textpart);
		ndr_basehdrs = cfg.getValue("ndr_header", true, ndr_basehdrs);
		ndr_dsnpart_hdr = cfg.getValue("ndr_dsnhdr", true, ndr_dsnpart_hdr);

		if (ndr_textpart != null)
		{
			ndr_textpart = ndr_textpart.replace(TOKEN_MTARPT, mtarep);
			ndr_textpart = ndr_textpart.replace(AppConfig.TOKEN_PRODNAME, appConfig.getProductName());
			ndr_textpart = ndr_textpart + Protocol.EOL;
		}
		if (mime_altprologue != null) mime_altprologue = Protocol.EOL + mime_altprologue + Protocol.EOL;

		ndr_basehdrs = ndr_basehdrs.replace(TOKEN_BNDRY, mime_boundary);
		ndr_basehdrs = ndr_basehdrs.replace(TOKEN_MTARPT, mtarep);
		ndr_basehdrs = ndr_basehdrs.replace(AppConfig.TOKEN_PRODNAME, appConfig.getProductName());
		ndr_basehdrs += " ";  // add a space after Date header (Config class would have stripped it)

		ndr_dsnpart_hdr = ndr_dsnpart_hdr.replace(TOKEN_MTARPT, mtarep);
		ndr_dsnpart_hdr = ndr_dsnpart_hdr.replace(AppConfig.TOKEN_PRODNAME, appConfig.getProductName());
		ndr_dsnpart_hdr += " ";  // add a space after Date header (Config class would have stripped it)
	}
}
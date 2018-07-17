/*
 * Copyright 2010-2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta;

import com.grey.base.utils.ByteChars;
import com.grey.base.sasl.SaslEntity;

/*
 * SMTP: RFC821 (Aug 1982) => RFC-5321 (Oct 2008)
 * SMTP-AUTH: RFC-2554 (Mar 1999) => RFC-4954 (Jul 2007) - defines SASL profile for SMTP.
 * MSA: RFC-2476 (Dec 1998) => RFC-6409 (Nov 2011)
 * STARTTLS: RFC-2487 (Jan 1999) => RFC-3207 (Feb 2002)
 * Transmission Types: RFC-3848 (Jul 2004)
 */
public final class Protocol
{
	public static final int TCP_PORT = 25;
	public static final int TCP_SSLPORT = 465;
	public static final String EOL = "\r\n";
	public static final String EOM = "." + EOL;  //message is obliged to end in CRLF, so we must not add it - end result is CRLF.CRLF
	public static final ByteChars EOL_BC = new ByteChars(EOL);

	public static final ByteChars CMDREQ_HELO = new ByteChars("HELO");
	public static final ByteChars CMDREQ_EHLO = new ByteChars("EHLO");
	public static final ByteChars CMDREQ_MAILFROM = new ByteChars("MAIL FROM:");
	public static final ByteChars CMDREQ_MAILTO = new ByteChars("RCPT TO:");
	public static final ByteChars CMDREQ_DATA = new ByteChars("DATA");
	public static final ByteChars CMDREQ_QUIT = new ByteChars("QUIT");
	public static final ByteChars CMDREQ_RESET = new ByteChars("RSET");
	public static final ByteChars CMDREQ_NOOP = new ByteChars("NOOP");
	public static final ByteChars CMDREQ_STLS = new ByteChars("STARTTLS");
	private static final ByteChars CMDREQ_AUTH = new ByteChars("AUTH ");
	public static final ByteChars CMDREQ_SASL_PLAIN = new ByteChars(CMDREQ_AUTH).append(SaslEntity.MECHNAME_PLAIN);
	public static final ByteChars CMDREQ_SASL_CMD5 = new ByteChars(CMDREQ_AUTH).append(SaslEntity.MECHNAME_CMD5);
	public static final ByteChars CMDREQ_SASL_EXTERNAL = new ByteChars(CMDREQ_AUTH).append(SaslEntity.MECHNAME_EXTERNAL);

	public static final char[] EXT_PIPELINE = "PIPELINING".toCharArray();
	public static final char[] EXT_8BITMIME = "8BITMIME".toCharArray();
	public static final char[] EXT_SIZE = "SIZE".toCharArray();
	public static final char[] EXT_STLS = "STARTTLS".toCharArray();
	public static final char[] EXT_AUTH = "AUTH".toCharArray();
	public static final char[] EXT_AUTH_COMPAT = "AUTH=".toCharArray();  //a broken form of EXT_AUTH used by some older SMTP software

	// Reply has 3-digit status code, followed by continuation column, followed by freestyle text
	public static final int REPLY_CODELEN = 3;
	public static final char REPLY_CONTD = '-';

	public static final short PERMERR_BASE = 500;
	public static final short REPLYCODE_READY = 220;
	public static final short REPLYCODE_AUTH_OK = 235;
	public static final short REPLYCODE_OK = 250;
	public static final short REPLYCODE_RECIPMOVING = 251;
	public static final short REPLYCODE_AUTH_CONTD = 334;
	public static final short REPLYCODE_DATA = 354;
	public static final short REPLYCODE_BYE = 221;
	public static final short REPLYCODE_TMPERR_CONN = 421;
	public static final short REPLYCODE_GREYLIST = 450;
	public static final short REPLYCODE_TMPERR_LOCAL = 451;
	public static final short REPLYCODE_NOSSL = 502;
	public static final short REPLYCODE_PERMERR_ADDR = 550;
	public static final short REPLYCODE_BLACKLIST = 550;
	public static final short REPLYCODE_PERMERR_MISC = 554;

	public static final String AUTH_CHALLENGE = Integer.toString(REPLYCODE_AUTH_CONTD)+" "; //prefixes server challenge
	public static final char AUTH_EMPTY = '='; //denotes zero-length initial SASL response - see RFC-5034 section 4
}
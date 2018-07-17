/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap;

/*
 * IMAP2: RFC-1064 (Jul 1988) => RFC-1176 (Aug 1990)
 * IMAP3: RFC-1203 (Feb 1991) - never adopted, withdrawn within 2 years
 * IMAP4 is RFC-1730 (Dec 1994) => RFC-2060 (Dec 1996) => RFC-3501 (Rev1, Mar 2003)
 * IMAP4 descends from IMAP2 and was initially known as IMAP2bis.
 * RFC-1731 (Dec 1994) defines IMAP SASL profile and hasn't been updated, but 3501 doesn't refer to it.
 * Useful reference: http://www.networksorcery.com/enp/protocol/imap.htm
 */
public class IMAP4Protocol
{
	public static final int TCP_PORT = 143;
	public static final int TCP_SSLPORT = 993;
	public static final String VERSION = "IMAP4rev1";
	public static final String STATUS_OK = "OK";
	public static final String STATUS_REJ = "NO";
	public static final String STATUS_ERR = "BAD";
	public static final String STATUS_BYE = "BYE";
	public static final String STATUS_PREAUTH = "PREAUTH";
	public static final String STATUS_UNTAGGED = "* "; //prefixes untagged response
	public static final String STATUS_CONTD = "+ "; //prefixes continuation response
	public static final char AUTH_EMPTY = '='; //denotes zero-length initial SASL response
	public static final String EOL = "\r\n";
	public static final String ENDRSP = "." + EOL;
	public static final byte WILDCARD_PARTIAL = '%';
	public static final byte WILDCARD_FULL = '*';
	public static final byte LITERALPLUS = '+'; //see RFC-2088

	public static enum AUTHTYPE {LOGIN, SASL_PLAIN, SASL_CRAM_MD5, SASL_EXTERNAL}

	// mandatory IMAP commands (RFC-3501)
	public static final com.grey.base.utils.ByteChars CMDREQ_LOGIN = new com.grey.base.utils.ByteChars("LOGIN");
	public static final com.grey.base.utils.ByteChars CMDREQ_AUTH = new com.grey.base.utils.ByteChars("AUTHENTICATE");
	public static final com.grey.base.utils.ByteChars CMDREQ_STLS = new com.grey.base.utils.ByteChars("STARTTLS");
	public static final com.grey.base.utils.ByteChars CMDREQ_QUIT = new com.grey.base.utils.ByteChars("LOGOUT");
	public static final com.grey.base.utils.ByteChars CMDREQ_CAPA = new com.grey.base.utils.ByteChars("CAPABILITY");
	public static final com.grey.base.utils.ByteChars CMDREQ_NOOP = new com.grey.base.utils.ByteChars("NOOP");
	public static final com.grey.base.utils.ByteChars CMDREQ_LIST = new com.grey.base.utils.ByteChars("LIST");
	public static final com.grey.base.utils.ByteChars CMDREQ_LSUB = new com.grey.base.utils.ByteChars("LSUB");
	public static final com.grey.base.utils.ByteChars CMDREQ_CREATE = new com.grey.base.utils.ByteChars("CREATE");
	public static final com.grey.base.utils.ByteChars CMDREQ_DELETE = new com.grey.base.utils.ByteChars("DELETE");
	public static final com.grey.base.utils.ByteChars CMDREQ_RENAME = new com.grey.base.utils.ByteChars("RENAME");
	public static final com.grey.base.utils.ByteChars CMDREQ_SELECT = new com.grey.base.utils.ByteChars("SELECT");
	public static final com.grey.base.utils.ByteChars CMDREQ_EXAMINE = new com.grey.base.utils.ByteChars("EXAMINE");
	public static final com.grey.base.utils.ByteChars CMDREQ_STATUS = new com.grey.base.utils.ByteChars("STATUS");
	public static final com.grey.base.utils.ByteChars CMDREQ_APPEND = new com.grey.base.utils.ByteChars("APPEND");
	public static final com.grey.base.utils.ByteChars CMDREQ_CLOSE = new com.grey.base.utils.ByteChars("CLOSE");
	public static final com.grey.base.utils.ByteChars CMDREQ_EXPUNGE = new com.grey.base.utils.ByteChars("EXPUNGE");
	public static final com.grey.base.utils.ByteChars CMDREQ_UID = new com.grey.base.utils.ByteChars("UID");
	public static final com.grey.base.utils.ByteChars CMDREQ_STORE = new com.grey.base.utils.ByteChars("STORE");
	public static final com.grey.base.utils.ByteChars CMDREQ_FETCH = new com.grey.base.utils.ByteChars("FETCH");
	public static final com.grey.base.utils.ByteChars CMDREQ_SRCH = new com.grey.base.utils.ByteChars("SEARCH");
	public static final com.grey.base.utils.ByteChars CMDREQ_COPY = new com.grey.base.utils.ByteChars("COPY");
	public static final com.grey.base.utils.ByteChars CMDREQ_CHECK = new com.grey.base.utils.ByteChars("CHECK");
	public static final com.grey.base.utils.ByteChars CMDREQ_SUBSCRIBE = new com.grey.base.utils.ByteChars("SUBSCRIBE");
	public static final com.grey.base.utils.ByteChars CMDREQ_UNSUBSCRIBE = new com.grey.base.utils.ByteChars("UNSUBSCRIBE");
	// optional IMAP commands (extensions)
	public static final com.grey.base.utils.ByteChars CMDREQ_IDLE = new com.grey.base.utils.ByteChars("IDLE"); //RFC-2177 (Jun 1997)
	public static final com.grey.base.utils.ByteChars CMDREQ_NAMSPC = new com.grey.base.utils.ByteChars("NAMESPACE"); //RFC-2342 (May 1998)
	public static final com.grey.base.utils.ByteChars CMDREQ_UNSELECT = new com.grey.base.utils.ByteChars("UNSELECT"); //RFC-3691 (Feb 2004)

	//mailbox flags
	public static final String BOXFLAG_NOSELECT = "\\Noselect";
	public static final String BOXFLAG_MARKED = "\\Marked";
	public static final String BOXFLAG_UNMARKED = "\\Unmarked";
	public static final String BOXFLAG_NEVERCHILD = "\\Noinferiors";
	public static final String BOXFLAG_CHILD = "\\HasChildren"; //RFC-3348 extension
	public static final String BOXFLAG_NOCHILD = "\\HasNoChildren";

	//message flags
	public static final String MSGFLAG_DRAFT = "\\Draft"; //Maildir flag: D
	public static final String MSGFLAG_FLAGGED = "\\Flagged"; //Maildir flag: F
	public static final String MSGFLAG_ANSWERED = "\\Answered";  //Maildir flag: R
	public static final String MSGFLAG_SEEN = "\\Seen"; //Maildir flag: S
	public static final String MSGFLAG_DEL = "\\Deleted"; //Maildir flag: T
	public static final String MSGFLAG_RECENT = "\\Recent";

	public static final com.grey.base.utils.ByteChars CAPA_TAG_CHILDREN = new com.grey.base.utils.ByteChars("CHILDREN"); //RFC-3348 extension
	public static final com.grey.base.utils.ByteChars CAPA_TAG_LITERALPLUS = new com.grey.base.utils.ByteChars("LITERAL+"); //RFC-2088 extension

	public static final String MBXNAME_INBOX = "INBOX";

	public static final String IDLE_DONE = "DONE";
}
/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

final class Defs
{
	static final String TOKEN_HOSTNAME = "%H%";
	static final String RSPMSG_GREET = "Mailismus IMAP Ready";

	static final String DATA_NIL = "NIL";
	static final byte CHAR_QUOTE = '"';
	static final String PARENTH_QUOTE = String.valueOf((char)CHAR_QUOTE)+String.valueOf((char)CHAR_QUOTE);

	/* PROTO_STATE maps to the RFC-3501 protocol states as follows:
	 * - S_AUTH: The Not-Authenticated state (we are in the process of authenticating)
	 * - S_SELECT: The Authenticated state (we are in the process of selecting a mailbox)
	 * - S_MAILBOX: The Selected state (we have selected a mailbox)
	 * - S_DISCON: The Logout state
	 * The other PROTO_STATE values can be seen as sub-states of the RFC ones, eg. S_STLS and S_SASL
	 * occur during authentication.
	 */
	enum PROTO_STATE {S_DISCON, S_AUTH, S_SASL, S_STLS, S_SELECT, S_MAILBOX, S_APPEND, S_IDLE}

	enum PROTO_EVENT {E_CONNECTED, E_DISCONNECT, E_DISCONNECTED, E_QUIT, E_STLS, E_CAPA,
						E_LOGIN, E_AUTHSASL, E_SASLRSP,
						E_LIST, E_NAMSPC, E_SELECT, E_EXAMINE, E_STATUS, E_CREATE, E_DELETE, E_RENAME, E_APPEND,
						E_CLOSE, E_EXPUNGE, E_STORE, E_FETCH, E_SRCH, E_COPY, E_UID, E_CHECK,
						E_LSUB, E_SUBSCRIBE, E_UNSUBSCRIBE, E_UNSELECT,
						E_NOOP, E_REJCMD, E_BADCMD, E_LOCALERROR, E_IDLE}

	static boolean isFlagSet(int f, int t) {return ((f & t) != 0);}


	static final class EnvelopeHeader
	{
		public static final int F_ADDR = 1 << 0; //this field is an email address
		public static final int F_ESC = 1 << 1; //value might need to be escaped
		public static final int F_ANGBRACE = 1 << 2; //take angle-bracketed part only

		public final String hdrname;
		public final String dflt; //another header name, to whose value this one defaults
		private final int flags;

		public EnvelopeHeader(String n, String d, int f) {
			hdrname=n; dflt=d; flags=f;
		}
		public EnvelopeHeader(String n) {this(n, null, 0);}
		public EnvelopeHeader(String n, String d) {this(n, d, 0);}
		public EnvelopeHeader(String n, int f) {this(n, null, f);}

		public boolean trait(int f) {return Defs.isFlagSet(flags, f);}
	}


	static final class FetchOpDef
	{
		public enum OPCODE {DUMMY, UID, SIZE, TIMESTAMP, FLAGS, ENVELOPE, BODYSTRUCTURE, BODY, HEADERS, MIME, TEXT, ALL}
		public static final int F_RDWR = 1 << 0;
		public static final int F_HASFLDS = 1 << 1;
		public static final int F_HASMIME = 1 << 2;
		public static final int F_MDTYMIME = 1 << 3;
		public static final int F_EXCL = 1 << 4; //the specified values are exclusive

		public final OPCODE code;
		public final int flags;
		public final int batchsize;
		public final String parenth; //if non-null, char-0 is opening parenthesis, and char-0 is the closing

		public boolean isSet(int f) {return isFlagSet(flags, f);}

		public FetchOpDef(OPCODE opcode, int opflags, int bs, String parentheses) {
			if ((opflags & F_MDTYMIME) != 0) opflags |= F_HASMIME;
			code = opcode;
			flags = opflags;
			batchsize = bs;
			parenth = parentheses;
		}
	}

	static final class FetchOp {
		public FetchOpDef def;
		public final String replytag;
		public final boolean peek;
		public String[] hdrs;
		public com.grey.base.collections.NumberList mime_coords;
		public int partial_off;
		public int partial_len;

		public FetchOp(FetchOpDef opdef, String rtag, boolean rdonly) {
			def=opdef; replytag=rtag.toUpperCase(); peek=(rdonly || !def.isSet(FetchOpDef.F_RDWR));
		}
	}


	static final class SearchKey
	{
		public enum ARGTYPE {ANY, KWORD, HEADER, NUMBER, DATE}
		public final String token;
		public final boolean ignored;
		public final int argcnt;
		public final char msflag;
		public final boolean excl_flag;
		public final ARGTYPE argtype;
		public final String alias;
		public SearchKey(String t, boolean i, int c, char f, boolean e, ARGTYPE at, String a) {
			token=t; ignored=i; argcnt=c; msflag=f; excl_flag=e; argtype=at; alias=a;
		}
		public SearchKey(String t, boolean i, int c) {this(t, i, c, (char)0, false, ARGTYPE.ANY, null);}
		public SearchKey(String t, char f, boolean e) {this(t, false, 0, f, e, ARGTYPE.ANY, null);}  //for MS flags
		public SearchKey(String t, ARGTYPE a, int c) {this(t, false, c, (char)0, false, a, null);}
		public SearchKey(String t, ARGTYPE a) {this(t, a, 1);}
		public SearchKey(String t, String a) {this(t, false, 0, (char)0, false, ARGTYPE.ANY, a);}
	}
}
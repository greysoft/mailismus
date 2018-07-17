/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.imap.server;

import com.grey.mailismus.imap.server.Defs.PROTO_EVENT;
import com.grey.mailismus.imap.server.Defs.FetchOp;

class BulkCommand
{
	public final PROTO_EVENT cmd;
	public com.grey.base.collections.NumberList seqlst;
	public int batch_off;
	public int batch_siz;
	public boolean report_at_end;

	public BulkCommand(PROTO_EVENT c) {cmd=c;}

	// this is called after performing command, to free resources
	public void clear()
	{
		seqlst = null;
	}

	// this is called before performing the command, to initialise it
	public void reset(com.grey.base.collections.NumberList lst)
	{
		clear();
		seqlst = lst;
		batch_off = 0;
		batch_siz = 0;
		report_at_end = false;
	}

	public final void setNoOp() {batch_siz = 0;}
	public boolean isNoOp() {return (batch_siz == 0);}


	static final class CommandFetch extends BulkCommand
	{
		public final java.util.ArrayList<FetchOp> ops = new java.util.ArrayList<FetchOp>();

		public CommandFetch() {super(PROTO_EVENT.E_FETCH);}

		@Override
		public void clear()
		{
			super.clear();
			ops.clear();
		}

		public void prime(int maxbatch, int maxdiskbatch)
		{
			int siz = maxbatch;
			for (int idx = 0; idx != ops.size(); idx++) {
				siz = Math.min(siz, ops.get(idx).def.batchsize);
			}
			batch_siz = siz;
		}

		public void addOp(FetchOp op)
		{
			ops.add(op);
		}

		//This orders the returned Fetch items according to the OPCODE enum.
		//Thought it might help ImapTest - it doesn't, but leave it in anyway.
		public void addOp_alternative(FetchOp op)
		{
			final int rank = op.def.code.ordinal();
			int idx = ops.size() - 1;
			while (idx != -1) {
				if (ops.get(idx).def.code.ordinal() < rank) break;
				idx--;
			}
			ops.add(idx+1, op);
		}
	}


	static final class CommandStore extends BulkCommand
	{
		public String msflags;
		public int mode;
		public boolean silent;
		public boolean report_uid;

		public CommandStore() {super(PROTO_EVENT.E_STORE);}

		public void reset(com.grey.base.collections.NumberList lst, int bsiz, String flags, int m, boolean s, boolean u)
		{
			super.reset(lst);
			batch_siz = bsiz;
			msflags = flags;
			mode = m;
			silent = s;
			report_uid = u;
			if (mode != 0 && msflags.length() == 0) setNoOp(); //adding/removing nothing is a no-op
		}
	}


	static final class CommandCopy extends BulkCommand
	{
		public String dstmbx;

		public CommandCopy() {super(PROTO_EVENT.E_COPY);}

		public void reset(com.grey.base.collections.NumberList lst, int bsiz, String m)
		{
			super.reset(lst);
			batch_siz = bsiz;
			dstmbx = m;
		}
	}


	static final class CommandSearch extends BulkCommand
	{
		public final com.grey.base.collections.NumberList results = new com.grey.base.collections.NumberList();
		boolean uidmode;
		public String flags_incl;
		public String flags_excl;
		public final java.util.HashMap<String, String> hdrs_incl = new java.util.HashMap<String, String>();
		public final java.util.HashMap<String, String> hdrs_excl = new java.util.HashMap<String, String>();
		public String[] hdrnames;
		public long mintime;
		public long maxtime;
		public int minsize;
		public int maxsize;

		public CommandSearch() {super(PROTO_EVENT.E_SRCH);}

		@Override
		public void clear()
		{
			super.clear();
			results.clear();
			hdrs_incl.clear();
			hdrs_excl.clear();
			hdrnames = null;
		}

		@Override
		public void reset(com.grey.base.collections.NumberList slst)
		{
			super.reset(slst);
			seqlst.clear();
		}

		public void prime(boolean umode, String fi, String fe, long t1, long t2, int s1, int s2, int bs)
		{
			uidmode = umode;
			flags_incl = fi;
			flags_excl = fe;
			mintime = t1;
			maxtime = t2;
			minsize = s1;
			maxsize = s2;
			batch_siz = bs;
			if (hdrs_incl.size() + hdrs_excl.size() != 0) {
				hdrnames = new String[hdrs_incl.size()+hdrs_excl.size()];
				if (hdrs_incl.size() != 0) {
					java.util.Iterator<String> it = hdrs_incl.keySet().iterator();
					for (int idx2 = 0; idx2 != hdrs_incl.size(); idx2++) {
						hdrnames[idx2] = it.next();
					}
				}
				if (hdrs_excl.size() != 0) {
					int off = hdrs_incl.size();
					java.util.Iterator<String> it = hdrs_excl.keySet().iterator();
					for (int idx2 = 0; idx2 != hdrs_excl.size(); idx2++) {
						hdrnames[off+idx2] = it.next();
					}
				}
			}
		}
	}
}
/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit.filter;

import java.util.ArrayList;
import java.nio.file.Path;

import com.grey.base.ExceptionUtils;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.TSAP;
import com.grey.naf.reactor.Producer;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.submit.Server;
import com.grey.mailismus.mta.submit.filter.api.FilterResultsHandler;
import com.grey.mailismus.mta.submit.filter.api.MessageFilter;

public class FilterExecutor
	implements Runnable, FilterResultsHandler
{
	private final TSAP remote;
	private final ByteChars authuser;
	private final ByteChars helo_name;
	private final ByteChars sender;
	private final ArrayList<EmailAddress> recips;
	private final java.nio.file.Path msg;
	private final MessageFilter filter;
	private final Server smtp_server;
	private final long srvr_conntime;
	private final Producer<FilterExecutor> resultChannel;

	private boolean is_cancelled; //note that this is only ever accessed in the main SMTP-server thread
	private String rsp_rejected; //non-null means reject message - must begin with 3-digit SMTP error code

	public String getRejectResponse() {return rsp_rejected;}

	public FilterExecutor(TSAP remote, ByteChars authuser, ByteChars helo_name,
			ByteChars sender, ArrayList<EmailAddress> recips, Path msg,
			MessageFilter filter, Server server, Producer<FilterExecutor> prod) {
		this.remote = remote;
		this.authuser = authuser;
		this.helo_name = helo_name;
		this.sender = sender;
		this.recips = recips;
		this.msg = msg;
		this.filter = filter;
		smtp_server = server;
		srvr_conntime = server.getStartTime();
		resultChannel = prod;
	}

	@Override
	public void run() {
		try {
			filter.approve(remote, authuser, helo_name, sender, recips, msg, this);
		} catch (Throwable ex) {
		    ex.printStackTrace(System.out);
			String rsp = Protocol.REPLYCODE_PERMERR_MISC+" Plugin failure";
	        filterCompleted(rsp);
		}
	}

    @Override
    public void approved() {
        filterCompleted(null);
    }

    @Override
    public void rejected(String rsp) {
        filterCompleted(rsp);
    }

	// Note that this is called by the main SMTP-server thread
	public void cancelMessage() {
		is_cancelled = true;
		filter.cancel();
	}

	// this is called when we are back in the SMTP-server thread, so no MT considerations
	public Server getServer() {
		if (is_cancelled || smtp_server.getStartTime() != srvr_conntime) {
			return null;
		}
		return smtp_server;
	}
	
	private void filterCompleted(String rejectrsp) {
	    rsp_rejected = rejectrsp;
        try {
            resultChannel.produce(this);
        } catch (Throwable ex) {
            System.out.println("FilterExecutor failed to write result="+rsp_rejected+" to Producer - "+ExceptionUtils.summary(ex));
        }
	}
}
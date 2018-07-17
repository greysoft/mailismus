/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit.filter.api;

import java.util.ArrayList;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.TSAP;

/**
 * This represents a user-defined filter for incoming messages in the Mailismus SMTP server.
 * <br>
 * A one-shot instance of this class is created by the user-supplied factory for every message
 * to be processed, and then discarded.
 */
public interface MessageFilter {
	/**
	 * The Mailismus SMTP server calls this to tell the filter to process an incoming message, to
	 * decide whether it should be accepted or rejected.
	 *
	 * @param remote     The remote client's TCP/IP address.
	 * @param authuser   The username under which the client authenticated. Will be null if the
	 *                   client did not perform SMTP-Auth.
	 * @param helo_name  The HELO/EHLO name with which the client responded to our greeting.
	 * @param sender     The message sender's email address
	 * @param recips     The final message recipients, after rejecting invalid ones and exploding
	 *                   alias names, etc.
	 * @param msg        The message spool file - contains the full raw message (headers and body).
	 *                   <br>The filter may modify this file (strip attachments etc) but must not
	 *                   delete or rename it, even if it rejects the message. Mailismus will handle
	 *                   the cleanup of rejected messages.
	 * @rproc            The object to call once message processing is compelete, and this filter
	 *                   wishes to approve or reject the messsage.
	 */
	void approve(TSAP remote, ByteChars authuser, ByteChars helo_name, ByteChars sender,
	        ArrayList<EmailAddress> recips, java.nio.file.Path msg,
	        FilterResultsHandler rproc) throws java.io.IOException;

	/**
	 * The Mailismus SMTP server calls this method to tell the filter to stop processing the message
	 * (typically because the SMTP connection has been closed).
	 * <br>
	 * Note that this is called in a different thread to approve(). The latter is called in a background
	 * thread, whereas this is called in the main server thread and so must not block.
	 * <br>
	 * This is just a courtesy call to enable the filter to abort any unnecessary processing if possible,
	 * but as far as Mailismus is concerned this filter has already been discarded, and any subsequent
	 * return value from approve() will be ignored.
	 */
	void cancel();
}

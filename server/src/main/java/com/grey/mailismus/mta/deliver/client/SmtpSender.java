/*
 * Copyright 2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver.client;

import com.grey.base.utils.TSAP;

public interface SmtpSender {
	/**
	 * This is called after receiving the response to each individual recipient send (SMTP "RCPT TO" command)
	 * recipId is an index into SmtpMessage.getRecipients()
	 * Returns true to indicate that message processing should abort, else false
	 */
	default boolean recipientCompleted(SmtpMessage msg, int msgCount, int recipId, SmtpResponseDescriptor status, TSAP remote, boolean aborted) {
		return false;
	}

	/**
	 * This is called after receiving the response to the message body (SMTP "DATA" command)
	 * recipId is an index into SmtpMessage.getRecipients()
	 * Returns non-null to indicate a new message to be sent on this connection.
	 */
	default SmtpMessage messageCompleted(SmtpMessage msg, int msgCount) {
		return null;
	}

	/**
	 * This is called after the SMTP client disconnects.
	 * Note that if neither this nor onMessage() is set, the sender receives no feedback on the message delivery.
	 */
	default void onDisconnect(SmtpMessage finalMsg, int msgCount) {
	}
}

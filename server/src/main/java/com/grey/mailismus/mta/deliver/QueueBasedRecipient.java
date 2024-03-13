/*
 * Copyright 2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import com.grey.mailismus.mta.deliver.client.SmtpMessage;
import com.grey.mailismus.mta.queue.MessageRecip;

class QueueBasedRecipient implements SmtpMessage.Recipient {
	private final MessageRecip queueRecip;

	public QueueBasedRecipient(MessageRecip queueRecip) {
		this.queueRecip = queueRecip;
	}

	@Override
	public CharSequence getDomain() {
		return getQueueRecip().domain_to;
	}

	@Override
	public CharSequence getMailbox() {
		return getQueueRecip().mailbox_to;
	}

	public MessageRecip getQueueRecip() {
		return queueRecip;
	}

	@Override
	public String toString() {
		return "QueueBasedRecipient["+queueRecip+"]";
	}
}

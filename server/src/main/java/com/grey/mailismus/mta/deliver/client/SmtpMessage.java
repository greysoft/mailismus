/*
 * Copyright 2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver.client;

import java.util.List;
import java.util.function.Supplier;

public interface SmtpMessage {
	interface Recipient {
		CharSequence getDomain();
		CharSequence getMailbox();
	}

	CharSequence getSender();
	List<? extends Recipient> getRecipients();
	Supplier<Object> getData();

	// This is only used for logging, and is something meaningful to the caller. Maybe the SMTP message-ID, but not necessarily.
	CharSequence getMessageId();
}

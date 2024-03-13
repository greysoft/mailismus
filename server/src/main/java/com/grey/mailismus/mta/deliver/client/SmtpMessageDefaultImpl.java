/*
 * Copyright 2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class SmtpMessageDefaultImpl implements SmtpMessage {
	private final String msgId = UUID.randomUUID().toString();
	private final CharSequence sender;
	private final List<Recipient> recipients;
	private final Supplier<Object> data; //data can be Path or byte[]

	public SmtpMessageDefaultImpl(Builder bldr) {
		this.sender = bldr.sender;
		this.recipients = bldr.recipients;
		this.data = bldr.data;
	}

	@Override
	public CharSequence getSender() {
		return sender;
	}

	@Override
	public List<Recipient> getRecipients() {
		return recipients;
	}

	@Override
	public Supplier<Object> getData() {
		return data;
	}

	@Override
	public CharSequence getMessageId() {
		return msgId;
	}


	public static class SmtpRecipientDefaultImpl implements SmtpMessage.Recipient {
		private final CharSequence domain;
		private final CharSequence mailbox;

		public SmtpRecipientDefaultImpl(CharSequence domain, CharSequence mailbox) {
			this.domain = domain;
			this.mailbox = mailbox;
		}

		@Override
		public CharSequence getDomain() {
			return domain;
		}

		@Override
		public CharSequence getMailbox() {
			return mailbox;
		}
	}


	public static class Builder {
		private List<Recipient> recipients = new ArrayList<>();
		private CharSequence sender;
		private Supplier<Object> data;

		public Builder withSender(CharSequence sender) {
			this.sender = sender;
			return this;
		}

		public Builder withRecipient(Recipient recipient) {
			this.recipients.add(recipient);
			return this;
		}

		public Builder withRecipients(List<Recipient> recipients) {
			this.recipients.addAll(recipients);
			return this;
		}

		public Builder withData(Supplier<Object> data) {
			this.data = data;
			return this;
		}

		public SmtpMessageDefaultImpl createSmtpMessage() {
			return new SmtpMessageDefaultImpl(this);
		}
	}
}

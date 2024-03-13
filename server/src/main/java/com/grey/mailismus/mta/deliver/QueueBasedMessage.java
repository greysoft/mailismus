/*
 * Copyright 2015-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.grey.base.utils.ByteChars;
import com.grey.mailismus.mta.deliver.client.SmtpMessage;
import com.grey.mailismus.mta.queue.MessageRecip;
import com.grey.mailismus.mta.queue.QueueManager;

class QueueBasedMessage implements SmtpMessage {
	private final List<QueueBasedRecipient> recips = new ArrayList<>();
	private final QueueManager qmgr;

	private ByteChars sender;
	private ByteChars destdomain;
	private Relay relay;
	private Supplier<Object> data;
	private String spid;
	private Client client;
	private int msgcnt;

	@Override public ByteChars getSender() {return sender;}
	@Override public List<QueueBasedRecipient> getRecipients() {return recips;}
	@Override public Supplier<Object> getData() {return data;}
	@Override public String getMessageId() {return spid;}

	public ByteChars getDestination() {return destdomain;}
	public Relay getRelay() {return relay;}
	public QueueBasedRecipient getRecipient(int idx) {return recips.get(idx);}
	public int recipCount() {return recips.size();}
	public Client getClient() {return client;}
	int messageCount() {return msgcnt;}
	int incrementMessages() {return ++msgcnt;}

	public QueueBasedMessage(QueueManager qmgr) {
		this.qmgr = qmgr;
	}

	public QueueBasedMessage init(Relay rly, ByteChars destdom) {
		clear();
		relay = rly;
		if (relay == null) destdomain = destdom;
		return this;
	}

	public QueueBasedMessage clear() {
		resetMessage();
		sender = null;
		destdomain = null;
		relay = null;
		data = null;
		client = null;
		msgcnt = 0;
		return this;
	}

	QueueBasedMessage resetMessage() {
		recips.clear();
		spid = null;
		return this;
	}

	public void addRecipient(MessageRecip qrecip) {
		if (recips.isEmpty()) {
			// Need to record these params outside 'recips', as list will get cleared. Obviously every 'recips' member
			// will have the same SPID, but the destination domains will vary if in slave-relay or source-routed mode,
			// so destdomain may not be meaningful.
			// So long as callers are aware of that, it's useful to record destdomain anyway for logging purposes, as
			// many messages will only have one recipient.
			sender = qrecip.sender;
			spid = qmgr.externalSPID(qrecip.spid).toString();
			data = () -> qmgr.getMessage(qrecip.spid, qrecip.qid);

		}
		QueueBasedRecipient recip = new QueueBasedRecipient(qrecip);
		recips.add(recip);
	}

	public void setClient(Client client) {
		this.client = client;
	}

	@Override
	public String toString() {
		return "QueueBasedMessage[msg="+msgcnt+": sender="+sender+" => "+destdomain+"/"+ recips+ " - relay="+relay+", spid="+spid+"]";
	}
}

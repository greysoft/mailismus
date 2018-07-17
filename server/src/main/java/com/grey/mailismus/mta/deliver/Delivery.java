/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

public interface Delivery
{
	public interface Controller
	{
		void messageCompleted(MessageSender sender);
		void senderCompleted(MessageSender sender);
		com.grey.mailismus.AppConfig getAppConfig();
		com.grey.naf.reactor.Dispatcher getDispatcher();
		com.grey.mailismus.mta.queue.Manager getQueue();
		Routing getRouting();
	}


	public interface MessageSender
	{
		void start(Controller ctl) throws java.io.IOException;
		boolean stop();
		MessageParams getMessageParams();
		short getDomainError();
		String getLogID();
	}


	static final class MessageParams
	{
		private final java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip> recips = new java.util.ArrayList<com.grey.mailismus.mta.queue.MessageRecip>();
		private com.grey.base.utils.ByteChars sender;
		private com.grey.base.utils.ByteChars destdomain;
		private Relay relay;
		private int spid;
		private int msgcnt;

		public int getSPID() {return spid;}
		public com.grey.base.utils.ByteChars getSender() {return sender;}
		public com.grey.base.utils.ByteChars getDestination(){return destdomain;}
		public Relay getRelay() {return relay;}
		public com.grey.mailismus.mta.queue.MessageRecip getRecipient(int idx) {return recips.get(idx);}
		public int recipCount() {return recips.size();}
		int messageCount() {return msgcnt;}
		int incrementMessages() {return ++msgcnt;}

		MessageParams init(Relay rly, com.grey.base.utils.ByteChars destdom) {
			clear();
			relay = rly;
			if (relay == null) destdomain = destdom;
			return this;
		}

		MessageParams clear() {
			resetMessage();
			sender = null;
			destdomain = null;
			relay = null;
			msgcnt = 0;
			return this;
		}

		// This clears per-message state only
		MessageParams resetMessage() {
			recips.clear();
			spid = 0;
			return this;
		}

		void addRecipient(com.grey.mailismus.mta.queue.MessageRecip recip) {
			if (recips.size() == 0) {
				// Need to record these params outside 'recips', as list will get cleared. Obviously every 'recips' member
				// will have the same SPID, but the destination domains will vary if in slave-relay or source-routed mode,
				// so destdomain may not be meaningful.
				// So long as callers are aware of that, it's useful to record destdomain anyway for logging purposes, as
				// many messages will only have one recipient.
				sender = recip.sender;
				spid = recip.spid;
			}
			recips.add(recip);
		}
	}


	// Stats accumulator - can be used to record batch stats, or some other interval
	public static final class Stats
	{
		public int conncnt; //number of SMTP connections
		public int sendermsgcnt; //number of SMTP messages - always >=conncnt, depending on whether senders were refilled
		public int remotecnt; //number of remote (SMTP) recipients handled (ie. no. of MessageRecips assigned to a MessageSender)
		public int remotefailcnt; //number of remote recipients who failed - this is a subset of remotecnt
		public int localcnt; //number of local recipients handled (ie. no. of MessageRecips deliver into the MS)
		public int localfailcnt; //number of local recipients who failed - this is a subset of localcnt
		public long start;
		public Stats() {reset(null);}
		public Stats reset(com.grey.naf.reactor.TimerNAF.TimeProvider t) {
			start = (t == null ? System.currentTimeMillis() : t.getSystemTime());
			conncnt = sendermsgcnt = remotecnt = remotefailcnt = localcnt = localfailcnt = 0;
			return this;}
		@Override
		public String toString() {
			String txt = "DeliveryStats: Conns="+conncnt+", remote-msgs="+sendermsgcnt;
			if (localcnt != 0) txt += ", localrecips="+(localcnt-localfailcnt)+"/"+localcnt;
			if (remotecnt != 0) txt += ", remoterecips="+(remotecnt-remotefailcnt)+"/"+remotecnt;
			return txt+" - time="+(System.currentTimeMillis()-start)+"ms";
		}
	}
}

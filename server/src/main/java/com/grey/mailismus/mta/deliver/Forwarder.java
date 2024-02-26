/*
 * Copyright 2010-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.io.IOException;

import com.grey.base.collections.GenericFactory;
import com.grey.base.collections.HashedMapIntValue;
import com.grey.base.collections.HashedSet;
import com.grey.base.collections.ObjectWell;
import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.logging.Logger;
import com.grey.logging.Logger.LEVEL;
import com.grey.naf.EntityReaper;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.TimerNAF;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.nafman.NafManCommand;
import com.grey.naf.nafman.NafManRegistry;

import com.grey.mailismus.AppConfig;
import com.grey.mailismus.Audit;
import com.grey.mailismus.Transcript;
import com.grey.mailismus.mta.MTA_Task;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.queue.Cache;
import com.grey.mailismus.mta.queue.MessageRecip;
import com.grey.mailismus.mta.queue.QueueManager;
import com.grey.mailismus.ms.MessageStore;
import com.grey.mailismus.nafman.Loader;

public class Forwarder
	implements Delivery.Controller,
		TimerNAF.Handler,
		NafManCommand.Handler
{
	public interface BatchCallback {
		void batchCompleted(int qsize, Delivery.Stats stats);
	}

	private static class SenderReaper implements EntityReaper {
		private final Delivery.Controller ctl;
		public SenderReaper(Delivery.Controller ctl) {
			this.ctl = ctl;
		}
		@Override
		public void entityStopped(Object obj) {
			ctl.senderCompleted((Delivery.MessageSender)obj);
		}
	}

	private static class ClientFactory implements GenericFactory<Delivery.MessageSender> {
		private final SharedFields shared;
		public ClientFactory(SharedFields shared) {
			this.shared = shared;
		}
		@Override
		public Client factory_create() {
			// NB: can't set reaper here, as it gets cleared on ChannelMonitor.disconnect()
			return new Client(shared);
		}
	}

	private static final boolean CHECK_OFFLINE = SysProps.get("grey.mta.smtpclient.offlinecheck", false);
	private static final int TMRTYPE_QPOLL = 1;
	private static final int TMRTYPE_KILLSENDERS = 2;

	// The rule of thumb here is that we should not spend more than 2 minutes on each batch of cached messages we load from the
	// queue, before flushing the results back to the queue.
	// Have observed the times to send 1 message to Trout vary from 0.5 secs to 90 secs under heavy load, while Demon has a
	// 20-second delay before issuing a greeting, but then accepts a message in 250 milliseconds.
	// Working on an assumption of 1 msg/sec and ignoring fixed delays (like Demon's greet pause), that gives us 100 messages
	// per connection, so let's try that. The remote server can always reject with a temp-fail if we exceed its configure limit,
	// but there's no RFC limit.
	// NB: For all these 'max' settings, zero means no limit.
	private final int max_msgrecips;	//max recips we will batch up into a single SMTP message
	private final int max_connmsgs;		//max number of messages we will batch into a single SMTP connection
	private final int max_simulconns;	//max simultaneous outgoing connections
	private final int max_serverconns;	//max simultaneous connections to any one recipient domain or source relay
	private final long max_conntime;	//max lifetime of an SMTP connection - soft limit, as we only intervene at end-of-message
	private final long max_senderlife;	//this is a hard version of max_conntime, used to stop all remaining senders
	private final long delay_start;
	private final long interval_low;
	private final long interval_high;
	private final long interval_err;

	private final Dispatcher dsptch;
	private final QueueManager qmgr;
	private final MessageStore ms;
	private final Routing routing;
	private final SharedFields sharedFields;
	private final Audit audit;
	private final EntityReaper reaper;
	private final EntityReaper sendersReaper;
	private final BatchCallback batchCallback;
	private final Cache qcache;
	private final ObjectWell<Delivery.MessageSender> sparesenders;
	private final HashedSet<Delivery.MessageSender> activesenders = new HashedSet<>();

	// This maps connection targets (ie. SMTP servers) to the number of simultaneous connections we currently have to them.
	// The map values can be of type ByteChars (destination domain) or Relay.
	// Of course multiple connections to one destination domain might actually be spread amongst multiple servers, but we
	// treat it as as one target, and limit the total connections to it.
	private final HashedMapIntValue<Object> active_serverconns;

	private TimerNAF tmr_qpoll;
	private TimerNAF tmr_killsenders;
	private boolean has_stopped;
	private boolean inShutdown;
	private boolean inScan;
	private boolean sendDeferred;

	// batchStats is logged and reset at the end of each batch, while openStats is accumulated for an open-ended period,
	// until retrieved and reset by the NAFMAN COUNTERS command (and unlike the running totals below, it is only updated
	// at the end of each batch)
	private final Delivery.Stats batchStats;
	private final Delivery.Stats openStats;
	private int sendercnt; //number of MessageSenders launched for current batch
	private int pending_recips; //number of entries in current batch which have not yet been handled (qstatus==READY)

	// Stats - running totals across all batches
	private int batchcnt; //not incremented for null batches (ie. nothing in queue)
	private int total_conncnt; //SMTP connections
	private int total_sendermsgcnt; //SMTP messages (>= total_conncnt and excludes local delivery)
	private int total_remotecnt; //SMTP recipients (>= total_sendermsgcnt)
	private int total_localcnt; //Local recipients (delivered to Message-Store)
	private long total_qtime; //time spent in queue (get and flush messages)
	private long total_launchtime; //time spent on initial cache scan - so this includes local delivery
	private long total_sendtime; //batch processing time, excl qtime and launchtime (so more or less the SMTP time)

	//pre-allocated merely for efficiency
	private final EmailAddress tmpemaddr = new EmailAddress();
	private final StringBuilder tmpsb = new StringBuilder();

	@Override public Dispatcher getDispatcher() {return dsptch;}
	@Override public QueueManager getQueue() {return qmgr;}
	@Override public Routing getRouting() {return routing;}
	@Override public void senderCompleted(Delivery.MessageSender sender) {senderCompleted(sender, false);}
	@Override public CharSequence nafmanHandlerID() {return "SMTP-Forwarder";}

	// these counts should give the same result
	public int activeSendersCount() {return activesenders.size();}
	public int activeConnectionsCount() {return sharedFields.getActiveServerConnections();}

	public Forwarder(Dispatcher d, MTA_Task task, XmlConfig cfg, EntityReaper rpr, BatchCallback bcb) throws IOException, GeneralSecurityException {
		this(d, cfg, task.getAppConfig(), task.getQueue(), task.getMS(), rpr, null, bcb, task.getResolverDNS());
	}

	public Forwarder(Dispatcher d, XmlConfig cfg, AppConfig appConfig,
			QueueManager qm, MessageStore mstore,
			EntityReaper rpr, GenericFactory<Delivery.MessageSender> senderFactory,
			BatchCallback bcb, ResolverDNS dnsResolver) throws IOException, GeneralSecurityException
	{
		dsptch = d;
		ms = mstore;
		qmgr = qm;
		reaper = rpr;
		batchCallback = bcb;
		Logger log = dsptch.getLogger();
		audit = Audit.create("MTA-Delivery", "audit", dsptch, cfg);
		XmlConfig relaycfg = cfg.getSection("relays");
		routing = new Routing(relaycfg, dsptch.getApplicationContext().getConfig(), log);

		int cap_qcache = 2500;
		int _max_simulconns = cap_qcache;
		int _max_serverconns = 20; //Postfix and MS-Exchange seem to use 20
		int _max_connmsgs = 100;
		interval_low = cfg.getTime("interval_low", TimeOps.parseMilliTime("100"));
		interval_high = Math.max(interval_low, cfg.getTime("interval_high", TimeOps.parseMilliTime("10s")));
		interval_err = Math.max(interval_high, cfg.getTime("interval_error", TimeOps.parseMilliTime("3m")));
		delay_start = cfg.getTime("delay_start", interval_high);
		max_conntime = cfg.getTime("maxconntime", TimeOps.parseMilliTime("2m"));
		max_senderlife = cfg.getTime("maxsenderlife", TimeOps.parseMilliTime("4m"));

		if (routing.modeSlaveRelay()) {
			// We will only ever connect to a single destination, so "maxconnections" and "maxserverconnections" collapse into the same quantity.
			// Track the limit via max_simulconns only.
			_max_simulconns = 500;
			_max_serverconns = 0;
			_max_connmsgs *= 2;
		} else {
			_max_serverconns = cfg.getInt("maxserverconnections", false, _max_serverconns);
		}
		max_msgrecips = cfg.getInt("maxrecips", false, 50); //well within RFC-5321 server-requirement of 100
		max_connmsgs = cfg.getInt("maxmessages", false, _max_connmsgs);
		max_simulconns = cfg.getInt("maxconnections", false, _max_simulconns);
		max_serverconns = Math.min(max_simulconns, _max_serverconns);

		// Some msgs will have multiple recips, and some domains will have multiple messages that can be batched into one connection,
		// so the cache size should be a multiple of the max connections. We also need to beware of loading a huge cache which we end up
		// under-utilising though, as the unprocessed messages may still create some work when we flush the cache back to the queue.
		if (routing.modeSlaveRelay()) {
			cap_qcache = max_simulconns * max_connmsgs;
			if (cap_qcache > 5000) cap_qcache = 5000;
			if (cap_qcache < 2500) cap_qcache = 2500;
		}
		cap_qcache = (int)cfg.getSize("queuecache", cap_qcache);
		qcache = qmgr.initCache(cap_qcache);
		batchStats = new Delivery.Stats(dsptch);
		openStats = new Delivery.Stats(dsptch);

		sendersReaper = new SenderReaper(this);
		if (senderFactory == null) {
			XmlConfig smtpcfg = cfg.getSection("client");
			sharedFields = ClientConfiguration.createSharedFields(smtpcfg, this, dnsResolver, appConfig, max_serverconns);
			senderFactory = new ClientFactory(sharedFields);
		} else {
			// sender-factory is only supplied in some test modes, never in production mode
			sharedFields = null;
		}
		sparesenders = new ObjectWell<>(senderFactory, "SmtpFwd");
		active_serverconns = (max_serverconns == 0 ? null : new HashedMapIntValue<>());

		log.info("SMTP-Delivery: slave-relay mode="+routing.modeSlaveRelay());
		log.info("SMTP-Delivery: queue-cache="+qcache.capacity()+"/"+cap_qcache);
		log.info("SMTP-Delivery: maxconns="+max_simulconns+"; maxconns-per-server="+max_serverconns);
		log.info("SMTP-Delivery: maxmessages-per-conn="+max_connmsgs+"; maxrecips-per-msg="+max_msgrecips
				+"; maxconntime="+TimeOps.expandMilliTime(max_conntime));
		log.info("SMTP-Delivery Intervals: Low="+TimeOps.expandMilliTime(interval_low)
				+", High="+TimeOps.expandMilliTime(interval_high)
				+", Error="+TimeOps.expandMilliTime(interval_err)
				+" - Start-Delay="+TimeOps.expandMilliTime(delay_start));

		if (dsptch.getNafManAgent() != null) {
			NafManRegistry reg = dsptch.getNafManAgent().getRegistry();
			reg.registerHandler(Loader.CMD_COUNTERS, 0, this, dsptch);
			reg.registerHandler(Loader.CMD_SENDQ, 0, this, dsptch);
		}
	}

	public void start()
	{
		dsptch.getLogger().info("SMTP-Delivery: Starting");
		tmr_qpoll = dsptch.setTimer(delay_start, TMRTYPE_QPOLL, this);
	}

	public boolean stop()
	{
		dsptch.getLogger().info("SMTP-Delivery: Received shutdown request - connections="+activeSendersCount()
				+", pending="+pending_recips+", inscan="+inScan);
		inShutdown = true;
		boolean done = false;

		if (tmr_qpoll != null) {
			tmr_qpoll.cancel();
			tmr_qpoll = null;
		}
		if (tmr_killsenders != null) {
			tmr_killsenders.cancel();
			tmr_killsenders = null;
		}
		stopSenders();

		if (activeSendersCount() == 0) {
			stopped(false);
			done = true;
		}
		return done;
	}

	private void stopSenders()
	{
		// loop on copy of set to avoid ConcurrentModification from callbacks
		List<Delivery.MessageSender> lst = new ArrayList<>(activesenders);
		for (int idx = 0; idx != lst.size(); idx++) {
			Delivery.MessageSender sender = lst.get(idx);
			if (sender.stop()) senderCompleted(sender, true);
		}
	}

	private void stopped(boolean notify)
	{
		if (has_stopped) return;
		dsptch.getLogger().info("SMTP-Delivery: Shutdown - notify="+notify);
		Transcript transcript = (sharedFields == null ? null : sharedFields.getTranscript());
		if (transcript != null) transcript.close(dsptch.getSystemTime());
		qmgr.stop();
		if (audit != null) audit.close();
		if (active_serverconns != null) active_serverconns.clear();
		qcache.clear();
		has_stopped = true;
		if (notify && reaper != null) reaper.entityStopped(this);
	}

	@Override
	public void timerIndication(TimerNAF tmr, Dispatcher d)
	{
		switch (tmr.getType())
		{
		case TMRTYPE_QPOLL:
			tmr_qpoll = null;
			long interval = 0;
			try {
				if (!processQueue()) interval = interval_high; //no pending recips
			} catch (Throwable ex) {
				interval = interval_err;
				dsptch.getLogger().log(LEVEL.INFO, ex, true, "SMTP-Delivery: Failed to process queue");
				if (ex instanceof NullPointerException) throw (NullPointerException)ex; //aids testing
			}
			if (tmr_qpoll == null && !inShutdown && interval != 0) {
				tmr_qpoll = dsptch.setTimer(interval, TMRTYPE_QPOLL, this); //reschedule ourself
			}
			break;

		case TMRTYPE_KILLSENDERS:
			tmr_killsenders = null;
			dsptch.getLogger().info("SMTP-Delivery: Killing apparently hung connections="+activeSendersCount());
			stopSenders();
			break;

		default:
			dsptch.getLogger().error("SMTP-Delivery: Unexpected timer-type - "+tmr);
			break;
		}
	}

	// Not much we can do - very unlikely error however, as our NAF entry point (timerEventIndication) doesn't even throw.
	@Override
	public void eventError(TimerNAF tmr, Dispatcher d, Throwable ex)
	{
		dsptch.getLogger().error("SMTP-Delivery has NAF error: cache="+qcache.size()+", conns="+activeSendersCount()
			+", shutdown="+inShutdown+"/scanning="+inScan);
	}

	private boolean processQueue() throws IOException
	{
		if (CHECK_OFFLINE) {
			// abort if no interfaces up, so that we don't clock up spurious retry failures when offline
			if (IP.countLocalIPs(IP.FLAG_IFUP | IP.FLAG_IFREAL | IP.FLAG_IFIP4) == 0) {
				dsptch.getLogger().log(LEVEL.TRC2, "SMTP-Delivery: Offline ... skipping");
				return false;
			}
		}

		// load pending messages from queue
		batchStats.reset();
		if (active_serverconns != null) active_serverconns.clear();
		qcache.clear();
		qmgr.getMessages(qcache, sendDeferred);
		sendDeferred = false;
		pending_recips = qcache.size();
		sendercnt = 0;

		if (pending_recips == 0) {
			if (batchCallback != null) batchCallback.batchCompleted(0, null);
			return false;
		}
		batchcnt++;
		long time1 = dsptch.getRealTime();
		long qtime = time1 - batchStats.start;
		total_qtime += qtime;
		LEVEL lvl = LEVEL.TRC;

		if (dsptch.getLogger().isActive(lvl)) {
			tmpsb.setLength(0);
			tmpsb.append("SMTP-Delivery: Loaded queued recipients=").append(pending_recips);
			tmpsb.append(" (qtime=").append(qtime).append("ms)");
			dsptch.getLogger().log(lvl, tmpsb);
		}

		// scan the cache to load its entries into the Senders, and then initiate them
		qcache.sort();
		try {
			inScan = true;
			processCache();
		} finally {
			inScan = false;
		}
		long time2 = dsptch.getRealTime();
		long launchtime = time2 - time1;
		total_launchtime += launchtime;
		total_sendtime -= (time2 - batchStats.start); //because we will later add the time from batchStats.start onwards

		if (activeSendersCount() == 0) {
			cacheProcessed();
		} else {
			if (dsptch.getLogger().isActive(lvl)) {
				tmpsb.setLength(0);
				tmpsb.append("SMTP-Delivery: Launched senders=").append(sendercnt);
				if (activeSendersCount() != sendercnt) tmpsb.append("/active=").append(activeSendersCount());
				tmpsb.append(" - pending-recips=").append(pending_recips);
				if (ms != null) tmpsb.append(", local=").append(batchStats.localcnt);
				if (batchStats.localfailcnt != 0) tmpsb.append(" (fail=").append(batchStats.localfailcnt).append(')');
				tmpsb.append(" (launchtime=").append(launchtime).append("ms)");
				dsptch.getLogger().log(lvl, tmpsb);
			}
			tmr_killsenders = dsptch.setTimer(max_senderlife, TMRTYPE_KILLSENDERS, this);
		}
		return true;
	}

	private void processCache()
	{
		boolean local_done = (ms == null);
		int qlimit = qcache.size();

		for (int qslot = 0; qslot != qlimit; qslot++) {
			if (pending_recips == 0) break;
			if (!local_done) {
				MessageRecip recip = qcache.get(qslot);
				if (recip.domain_to == null) {
					// local recipient
					if (recip.qstatus != MessageRecip.STATUS_READY) continue; //probably a redundant check
					Path fh = qmgr.getMessage(recip.spid, recip.qid);
					try {
						ms.deliver(recip.mailbox_to, fh.toFile());
						if (audit != null) audit.log("Delivered", recip, false, dsptch.getSystemTime(), qmgr.externalSPID(recip.spid));
						recip.smtp_status = Protocol.REPLYCODE_OK;
					} catch (Exception ex) {
						dsptch.getLogger().log(LEVEL.TRC, ex, false, "SMTP-Delivery: Bouncing message for user="+recip.mailbox_to);
						recip.smtp_status = Protocol.REPLYCODE_PERMERR_MISC;
						batchStats.localfailcnt++;
					}
					recip.qstatus = MessageRecip.STATUS_DONE;
					pending_recips--;
					batchStats.localcnt++;
					total_localcnt++;
					continue;
				}
			}
			// Current message is for a remote recipient.
			// Local recipients (null domain) are sorted to the top of the cache, so we've already seen them all.
			local_done = true;
			if (max_simulconns != 0 && activeSendersCount() == max_simulconns) break; //no more connections allowed
			Delivery.MessageSender sender = populateSender(null, qslot, qlimit);
			if (sender == null) break;
			startSender(sender);
		}
	}

	// If dest_domain is passed in, then we're only interested in cache entries that match that.
	private Delivery.MessageSender populateSender(Delivery.MessageSender sender, int qslot, int limit)
	{
		Delivery.MessageParams msgparams = null;
		Relay sender_relay = null;
		ByteChars dest_domain = null;
		if (sender != null) {
			msgparams = sender.getMessageParams();
			sender_relay = msgparams.getRelay();
			dest_domain = msgparams.getDestination();
		}

		int spid = 0;
		while (qslot != limit) {
			if (pending_recips == 0) break;
			MessageRecip recip = qcache.get(qslot++);
			if (spid != 0 && recip.spid != spid) break; //no entries left for this message
			if (recip.qstatus != MessageRecip.STATUS_READY) continue;
			Relay recip_relay = getRoute(recip);
			if (sender != null) {
				//these conditions will be met in slave-relay mode, as sender_relay non-null and recip_relay is same
				if (sender_relay != recip_relay) continue;
				if (sender_relay == null) {
					if (!dest_domain.equals(recip.domain_to)) continue; //dest_domain is guaranteed non-null here
				}
			}

			//current MessageRecip matches the criteria so allocate to sender - might have to allocate sender too
			if (sender == null) {
				if (max_serverconns != 0) { //recall that this is zero in slave-relay mode (but not only in that mode)
					Object key = (recip_relay == null ? recip.domain_to : recip_relay);
					int cnt = active_serverconns.get(key);
					if (cnt == max_serverconns) continue; //no more connections allowed to this target
					active_serverconns.put(key, cnt+1);
				}
				//null dest_domain means grab every entry for this SPID, else we're tied to initial recipient domain
				sender = sparesenders.extract(); //extract() won't return null because this ObjectWell is uncapped
				activesenders.add(sender);
				sendercnt++;
				msgparams = sender.getMessageParams().init(recip_relay, recip.domain_to);
				sender_relay = msgparams.getRelay();
				dest_domain = msgparams.getDestination();
			}
			spid = recip.spid;
			msgparams.addRecipient(recip);
			recip.qstatus = MessageRecip.STATUS_BUSY;
			pending_recips--;
			if (max_msgrecips != 0 && msgparams.recipCount() == max_msgrecips) break;
		}
		return sender;
	}

	private void startSender(Delivery.MessageSender sender)
	{
		try {
			sender.setReaper(sendersReaper);
			sender.start(this);
		} catch (Throwable ex) {
			dsptch.getLogger().log(LEVEL.TRC, ex, true, "SMTP-Delivery/batch="+batchcnt+": Failed to start Sender="+sender.getLogID()+"/"+sender);
			senderCompleted(sender, true);
		}
	}

	// This method clears the existing recipient set, and then attempt to repopulate it with another msg for same destination
	// domain (might be same msg, if it has more recips waiting).
	// Caller should check for non-empty recipients on return, to determine if it has anything to send.
	@Override
	public void messageCompleted(Delivery.MessageSender sender)
	{
		long time1 = dsptch.getRealTime();
		boolean active = recordMessageResult(sender);
		Delivery.MessageParams msgparams = sender.getMessageParams();
		msgparams.resetMessage();
		if (!active || inScan || inShutdown || sender.getDomainError() != 0 || msgparams.messageCount() == max_connmsgs) return;

		if (time1 - batchStats.start > max_conntime) {
			//don't refill this Sender
			dsptch.getLogger().info("SMTP-Delivery/batch="+batchcnt+": Stopping slow Sender at messages="+msgparams.messageCount()
					+" - remote="+getPeerText(msgparams)+" - "+sender);
		} else {
			populateSender(sender, 0, qcache.size());
		}
		long span = dsptch.getRealTime() - time1;
		total_launchtime += span;
		total_sendtime -= span; //because we will later add the time from batchStats.start onwards
	}

	// The sender's recipient list will be empty if it completed successfully, as it would have called messageCompleted()
	// which clears it. So a non-empty recipient list probably means we have an error condition to report.	
	public void senderCompleted(Delivery.MessageSender sender, boolean aborted)
	{
		Delivery.MessageParams msgparams = sender.getMessageParams();
		if (msgparams.recipCount() != 0) recordMessageResult(sender);
		activesenders.remove(sender);

		if (active_serverconns != null) {
			Object key = msgparams.getRelay();
			if (key == null) key = msgparams.getDestination();
			int cnt = active_serverconns.get(key);
			if (--cnt == 0) {
				active_serverconns.remove(key);
			} else {
				active_serverconns.put(key, cnt);
			}
		}

		LEVEL lvl = LEVEL.TRC2;
		if (dsptch.getLogger().isActive(lvl)) {
			tmpsb.setLength(0);
			tmpsb.append("SMTP-Delivery/batch=").append(batchcnt).append(": Sender=").append(sender.getLogID());
			tmpsb.append(" has ").append(aborted?"aborted":"completed");
			if (msgparams.messageCount() != 1) tmpsb.append(" with msgcnt=").append(msgparams.messageCount());
			tmpsb.append(" - active-conns=").append(activeSendersCount()).append(", pending-recips=").append(pending_recips);
			if (inScan) tmpsb.append("/scanning");
			dsptch.getLogger().log(lvl, tmpsb);
		}
		msgparams.clear();
		sparesenders.store(sender);
		if (inScan) return; //take no further action if within a synchronous callback

		if (activeSendersCount() == 0) {
			cacheProcessed();
		}
	}

	private boolean recordMessageResult(Delivery.MessageSender sender)
	{
		Delivery.MessageParams msgparams = sender.getMessageParams();
		int recipcnt = msgparams.recipCount();
		int processed_cnt = 0;

		for (int idx = 0; idx != recipcnt; idx++) {
			MessageRecip recip = msgparams.getRecipient(idx);
			if (recip.qstatus == MessageRecip.STATUS_DONE) {
				processed_cnt++;
			} else {
				//a failure or cancellation must have happened - mark this recip as undone
				recip.qstatus = MessageRecip.STATUS_READY;
				recip.smtp_status = 0;
				pending_recips++;
			}
		}
		if (processed_cnt == 0) return false;

		batchStats.remotecnt += processed_cnt;
		total_remotecnt += processed_cnt;
		batchStats.sendermsgcnt++;
		total_sendermsgcnt++;
		int sender_msgcnt = msgparams.incrementMessages();
		if (sender_msgcnt == 1) {
			//count connection after first msg (rather than having to decrement after abort, if we incremented prematurely)
			batchStats.conncnt++;
			total_conncnt++;
		}
		CharSequence extspid = qmgr.externalSPID(msgparams.getSPID());
		short domain_error = sender.getDomainError();
		LEVEL lvl = LEVEL.TRC2;
		boolean log = dsptch.getLogger().isActive(lvl);

		if (log) {
			tmpsb.setLength(0);
			tmpsb.append("SMTP-Delivery/batch=").append(batchcnt).append(": Sender=").append(sender.getLogID());
			tmpsb.append(" has processed msg #").append(sender_msgcnt).append('/').append(total_sendermsgcnt);
			tmpsb.append(" - SPID=").append(extspid).append(" from ").append(msgparams.getSender());
			tmpsb.append(" for recips=").append(processed_cnt).append('/').append(recipcnt).append('/').append(total_remotecnt);
			tmpsb.append(" at remote=").append(getPeerText(msgparams));
			if (domain_error != 0) tmpsb.append("/error=").append(domain_error);
			tmpsb.append(" - active-conns=").append(activeSendersCount()).append('/').append(activeConnectionsCount())
				.append('/').append(active_serverconns == null ? 0 : active_serverconns.size());
			tmpsb.append(", pending-recips=").append(pending_recips);
		}

		for (int idx = 0; idx != recipcnt; idx++) {
			MessageRecip recip = msgparams.getRecipient(idx);
			if (log) {
				tmpsb.append('\n').append(lvl).append(" - Recip=").append(idx+1).append(": ");
				recip.toString(tmpsb);
			}
			if (recip.qstatus == MessageRecip.STATUS_READY) continue;
			if (recip.smtp_status == Protocol.REPLYCODE_OK) {
				if (audit != null) audit.log("Relayed", recip, false, dsptch.getSystemTime(), extspid);
			} else {
				batchStats.remotefailcnt++;
			}
		}
		dsptch.getLogger().log(lvl, tmpsb);

		if (domain_error != 0 && !routing.modeSlaveRelay()) {
			// Domain-wide error, so apply it to all cache entries for this domain.
			ByteChars destdomain = msgparams.getDestination(); //will be null if getRelay() non-null
			int qsize = qcache.size();
			int orig_pending = pending_recips;
			for (int qslot = 0; qslot != qsize; qslot++) {
				MessageRecip recip = qcache.get(qslot);
				if (recip.qstatus != MessageRecip.STATUS_READY) continue;
				if (getRoute(recip) != msgparams.getRelay()) continue;
				if (destdomain == null || !destdomain.equals(recip.domain_to)) continue;
				recip.qstatus = MessageRecip.STATUS_DONE;
				recip.smtp_status = domain_error;
				pending_recips--;
			}
			lvl = LEVEL.TRC;
			if ((pending_recips != orig_pending) && dsptch.getLogger().isActive(lvl)) {
				tmpsb.setLength(0);
				tmpsb.append("SMTP-Delivery/batch=").append(batchcnt).append(": Applied error=").append(domain_error);
				tmpsb.append(" to pending recips=").append(orig_pending - pending_recips).append(" for domain=").append(destdomain);
				dsptch.getLogger().log(lvl, tmpsb);
			}
		}
		return true;
	}

	private void cacheProcessed()
	{
		if (tmr_killsenders != null) {
			tmr_killsenders.cancel();
			tmr_killsenders = null;
		}
		if (active_serverconns != null) active_serverconns.clear();
		int qsize = qcache.size();
		long interval = interval_low;
		long time1 = dsptch.getRealTime();
		try {
			qmgr.messagesProcessed(qcache);
		} catch (Throwable ex) {
			dsptch.getLogger().log(LEVEL.ERR, ex, true, "SMTP-Delivery/batch="+batchcnt+": Queue-Flush failed");
			interval = interval_err;
		}
		long time2 = dsptch.getRealTime();
		long qtime = time2 - time1;
		long btime = time2 - batchStats.start;
		total_qtime += qtime;
		total_sendtime += btime - qtime;
		tmpsb.setLength(0);
		tmpsb.append("SMTP-Delivery: Completed batch #").append(batchcnt).append(" (size=").append(qsize).append(", time=");
		TimeOps.expandMilliTime(btime, tmpsb, false);
		tmpsb.append(", qtime=").append(qtime).append("ms)");
		tmpsb.append(" with SMTP recips=").append(batchStats.remotecnt);
		if (batchStats.remotefailcnt != 0) tmpsb.append(" (fail=").append(batchStats.remotefailcnt).append(')');
		if (pending_recips != 0) tmpsb.append(", leftover=").append(pending_recips);
		if (batchStats.conncnt != 0) {
			tmpsb.append(", connections=").append(batchStats.conncnt);
			tmpsb.append(", messages=").append(batchStats.sendermsgcnt);
		}
		tmpsb.append(" - Totals: recips=").append(total_localcnt).append('/').append(total_remotecnt);
		if (total_conncnt != 0) tmpsb.append(", conns=").append(total_conncnt).append('/').append(total_sendermsgcnt);
		tmpsb.append(", sendtime=");
		TimeOps.expandMilliTime(total_sendtime, tmpsb, false).append(", qtime=");
		TimeOps.expandMilliTime(total_qtime, tmpsb, false).append(", launchtime=");
		TimeOps.expandMilliTime(total_launchtime, tmpsb, false);
		dsptch.getLogger().info(tmpsb);

		openStats.localcnt += batchStats.localcnt;
		openStats.localfailcnt += batchStats.localfailcnt;
		openStats.remotecnt += batchStats.remotecnt;
		openStats.remotefailcnt += batchStats.remotefailcnt;
		openStats.conncnt += batchStats.conncnt;
		openStats.sendermsgcnt += batchStats.sendermsgcnt;
		if (batchCallback != null) batchCallback.batchCompleted(qsize, batchStats);

		if (inShutdown) {
			stopped(true);
			return;
		}
		tmr_qpoll = dsptch.setTimer(interval, TMRTYPE_QPOLL, this);
	}

	private Relay getRoute(MessageRecip mr)
	{
		Relay rt = null;
		if (mr.sender != null && !routing.modeSlaveRelay()) {
			tmpemaddr.set(mr.sender).decompose();
			rt = routing.getSourceRoute(tmpemaddr, mr.ip_recv);
		}
		if (rt == null) rt = routing.getRoute(mr.domain_to);
		return rt;
	}

	private String getPeerText(Delivery.MessageParams msgparams)
	{
		Object peer = (routing.modeSlaveRelay() ? "smarthost" : null);
		if (peer == null) peer = (msgparams.getRelay()==null?null:msgparams.getRelay().display());
		if (peer == null) peer = msgparams.getDestination();
		return peer.toString();
	}

	@Override
	public CharSequence handleNAFManCommand(NafManCommand cmd)
	{
		tmpsb.setLength(0);
		if (cmd.getCommandDef().code.equals(Loader.CMD_COUNTERS)) {
			tmpsb.append("Delivery stats since ");
			TimeOps.makeTimeLogger(openStats.start, tmpsb, true, true);
			tmpsb.append(" - Period=");
			TimeOps.expandMilliTime(dsptch.getSystemTime() - openStats.start, tmpsb, false);
			tmpsb.append("<br/>SMTP Connections: ").append(openStats.conncnt);
			tmpsb.append("<br/>SMTP Messages: ").append(openStats.sendermsgcnt);
			tmpsb.append("<br/>SMTP Recipients: OK=").append(openStats.remotecnt-openStats.remotefailcnt).append("; Fail=").append(openStats.remotefailcnt);
			tmpsb.append("<br/>Local Recipients: OK=").append(openStats.localcnt-openStats.localfailcnt).append("; Fail=").append(openStats.localfailcnt);
			tmpsb.append("<br/>Current SMTP Connections: ").append(activeConnectionsCount());
			if (active_serverconns != null) tmpsb.append(" (Peers=").append(activeSendersCount())
				.append('/').append(active_serverconns == null ? 0 : active_serverconns.size()).append(')');
			if (StringOps.stringAsBool(cmd.getArg(NafManCommand.ATTR_RESET))) openStats.reset();
		} else if (cmd.getCommandDef().code.equals(Loader.CMD_SENDQ)) {
			if (tmr_qpoll != null) tmr_qpoll.reset(0);
			sendDeferred = true;
		} else {
			dsptch.getLogger().error("SMTP-Delivery: Missing case for NAFMAN cmd="+cmd.getCommandDef().code);
			return null;
		}
		return tmpsb;
	}
}

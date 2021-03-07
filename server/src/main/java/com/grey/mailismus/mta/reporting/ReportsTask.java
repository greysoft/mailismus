/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.reporting;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.StringOps;
import com.grey.base.utils.TimeOps;
import com.grey.logging.Logger.LEVEL;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.errors.MailismusException;

/*
 * See RFC-3462 for the Multipart/Report MIME type, RFC-3464 for the DSN format and RFC-3463 for the DSN status codes.
 * See RFC-2034 for the mechanism by which an SMTP server can return enhanced status codes (obviously nothing to do with reports).
 */
public final class ReportsTask
	extends com.grey.mailismus.mta.MTA_Task
	implements com.grey.naf.reactor.TimerNAF.Handler
{
	private static final byte MSGID_DLM = '-';

	private static final int TMRTYPE_POLLQUEUE = 1;
	private static final int TMRTYPE_HOUSEKEEP = 2;

	private final boolean generate_ndr;
	private final int cachesize;
	private final int attachmsg_max;
	private final long delay_start;
	private final long interval_low;
	private final long interval_high;
	private final long interval_err;
	private final long interval_housekeep;
	private final short statuszero; //map expired smtp_status=0 to this
	private final com.grey.base.utils.ByteChars mtarep; //Reporting MTA - that's us!
	private final boolean redirect_sink;
	private final com.grey.base.utils.ByteChars redirectRecip;
	private final java.util.ArrayList<com.grey.base.utils.EmailAddress> additionalRecipients
			= new java.util.ArrayList<com.grey.base.utils.EmailAddress>(); //list of recipients to whom duplicates of NDRs should be emailed
	private final String archiveDirectory; //a directory in which to store copies of all NDRs
	private final boolean one_shot;

	private final com.grey.base.utils.ByteChars ndr_status;
	private final com.grey.base.utils.ByteChars ndr_headers;
	private final com.grey.base.utils.ByteChars ndr_dsn_msg;
	private final com.grey.base.utils.ByteChars ndr_textpart;
	private final com.grey.base.utils.ByteChars mime_bndry;
	private final com.grey.base.utils.ByteChars mime_altprologue;
	private final byte[] msgfilebuf;

	private final com.grey.mailismus.mta.queue.Manager qmgr;
	private final com.grey.mailismus.mta.queue.Cache qcache;
	private final com.grey.mailismus.Audit audit;

	private com.grey.naf.reactor.TimerNAF tmr_qpoll;
	private com.grey.naf.reactor.TimerNAF tmr_housekeep;

	// these are the stats counters that are retrieved and reset by the NAFMAN COUNTERS command
	private long stats_start = System.currentTimeMillis();
	private int stats_bounces; //total bounced messages processed
	private int stats_ndrbounces; //bounced NDRs process - a subset of stats_bounces

	// pre-allocated for efficiency
	private final java.util.Calendar dtcal = TimeOps.getCalendar(null);
	private final java.util.ArrayList<com.grey.base.utils.EmailAddress> reportrecips = new java.util.ArrayList<com.grey.base.utils.EmailAddress>();
	private final com.grey.base.utils.EmailAddress reportrecip = new com.grey.base.utils.EmailAddress();
	private final com.grey.base.utils.ByteChars time_now = new com.grey.base.utils.ByteChars();
	private final com.grey.base.utils.ByteChars time_recv = new com.grey.base.utils.ByteChars();
	private final com.grey.base.utils.ByteChars ndr_content = new com.grey.base.utils.ByteChars();
	private final com.grey.base.utils.ByteChars ndr_msgid = new com.grey.base.utils.ByteChars();
	private final com.grey.base.utils.ByteChars tmprecip = new com.grey.base.utils.ByteChars();
	private final java.util.ArrayList<com.grey.base.utils.EmailAddress> tmpbclist = new java.util.ArrayList<com.grey.base.utils.EmailAddress>();
	private final StringBuilder extspidbuf = new StringBuilder();
	private final StringBuilder tmpsb = new StringBuilder();
	private ByteChars tmpbuf_failmsg = new ByteChars();

	// not much we can do here - our only Dispatcher entry point (Timer handler) doesn't even throw
	@Override
	public void eventError(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d, Throwable ex) {}

	public ReportsTask(String name, com.grey.naf.reactor.Dispatcher dsptch, com.grey.base.config.XmlConfig cfg)
			throws java.io.IOException
	{
		this(name, dsptch, cfg, false);
	}

	public ReportsTask(String name, com.grey.naf.reactor.Dispatcher d, com.grey.base.config.XmlConfig cfg, boolean once)
			throws java.io.IOException
	{
		super(name, d, cfg, null, null, DFLT_FACT_QUEUE);
		qmgr = getQueue();
		one_shot = once;
		com.grey.base.config.XmlConfig taskcfg = taskConfig();
		registerQueueOps(com.grey.mailismus.nafman.Loader.PREF_SHOWQ_REPORT);
		String subj = "Delivery Failure Report";
		String ndrstatus = "5.0.0";

		String addrfrom = getAppConfig().getAnnounceHost();
		int pos = addrfrom.indexOf('.');
		if (pos != -1) addrfrom = addrfrom.substring(pos + 1);
		addrfrom = "postmaster"+com.grey.base.utils.EmailAddress.DLM_DOM+addrfrom;

		boolean ndrgen = taskcfg.getBool("generate_ndr", true);
		cachesize = (int)taskcfg.getSize("queuecache", 100);
		String mtarep_str = taskcfg.getValue("announcemta", true, getAppConfig().getAnnounceHost());
		ndrstatus = taskcfg.getValue("ndr_status", true, ndrstatus);
		subj = taskcfg.getValue("ndr_subject", true, subj);
		addrfrom = taskcfg.getValue("ndr_from", true, addrfrom);
		attachmsg_max = (int)taskcfg.getSize("attachmsg", 4 * 1024);
		final int attachmsg_bufsiz = (int)taskcfg.getSize("filebufsiz", Math.min(attachmsg_max, 64*1024));
		interval_low = taskcfg.getTime("interval_low", TimeOps.parseMilliTime("5s"));
		interval_high = Math.max(interval_low, taskcfg.getTime("interval_high", TimeOps.parseMilliTime("1m")));
		interval_err = Math.max(interval_high, taskcfg.getTime("interval_error", TimeOps.parseMilliTime("3m")));
		interval_housekeep = taskcfg.getTime("interval_housekeeping", TimeOps.parseMilliTime("24h"));
		delay_start = taskcfg.getTime("delay_start", TimeOps.parseMilliTime("20s"));
		statuszero = (short)taskcfg.getInt("statuszero", false, 0);
		archiveDirectory = getDispatcher().getApplicationContext().getConfig().getPath(taskcfg, "ndr_copies_folder", null, false, null, null);
		String ndr_recip = taskcfg.getValue("ndr_recip_redirect", false, null);
		String[] ndr_bcc = taskcfg.getTuple("ndr_recips_additional", "|", false, null);

		if (ndr_bcc != null) {
			for (int idx = 0; idx != ndr_bcc.length; idx++) {
				com.grey.base.utils.EmailAddress emaddr = new com.grey.base.utils.EmailAddress(ndr_bcc[idx]);
				if (!qmgr.verifyAddress(emaddr.full)) {
					throw new MailismusConfigException("ReportsTask Config: Invalid ndr_recips_additional address - "+emaddr.full);
				}
				additionalRecipients.add(emaddr);
			}
		}

		if (ndr_recip != null) {
			redirect_sink = ndr_recip.equals(".");
			redirectRecip = (redirect_sink ? null : new com.grey.base.utils.ByteChars(ndr_recip));

			if (redirectRecip != null && !qmgr.verifyAddress(redirectRecip)) {
				throw new MailismusConfigException("ReportsTask Config: Invalid Redirect address - "+redirectRecip);
			}
			if (redirect_sink && archiveDirectory == null && additionalRecipients.size() == 0) {
				// this combination of settings disables NDRs, whatever else the config says
				if (ndrgen) {
					getLogger().warn("NDR-Generation is implicitly disabled - No recipients or archive-folder specified");
					ndrgen = false;
				}
			}
		} else {
			redirectRecip = null;
			redirect_sink = false;
		}
		generate_ndr = ndrgen;
		audit = com.grey.mailismus.Audit.create("MTA-Reports", "audit", getDispatcher(), taskcfg);
		qcache = qmgr.initCache(cachesize);
		Templates templates = new Templates(taskcfg, mtarep_str, getAppConfig());

		String str = templates.ndr_basehdrs.replace(Templates.TOKEN_RPTSUBJ, subj);
		str = str.replace(Templates.TOKEN_ADDRFROM, addrfrom);
		ndr_headers = new com.grey.base.utils.ByteChars(str);

		mime_bndry = new com.grey.base.utils.ByteChars(Protocol.EOL+"--"+templates.mime_boundary+Protocol.EOL);
		mime_altprologue = new com.grey.base.utils.ByteChars(templates.mime_altprologue);
		mtarep = new com.grey.base.utils.ByteChars(mtarep_str);
		ndr_status = new com.grey.base.utils.ByteChars(ndrstatus);
		ndr_textpart = new com.grey.base.utils.ByteChars(templates.ndr_textpart);
		ndr_dsn_msg = new com.grey.base.utils.ByteChars(templates.ndr_dsnpart_hdr);
		msgfilebuf = new byte[attachmsg_bufsiz];

		getLogger().info("ReportsTask: generateNDRS="+generate_ndr+", redirect="+(redirect_sink?"SINK":redirectRecip)
				+", attachmsg="+attachmsg_max+"/"+attachmsg_bufsiz);
		if (archiveDirectory != null) getLogger().info("ReportsTask: Archive folder="+archiveDirectory);
		if (additionalRecipients.size() != 0) getLogger().info("ReportsTask: Additional recips="+additionalRecipients.size()+" - "+additionalRecipients);
		getLogger().info("ReportsTask: announce="+mtarep_str+", From="+addrfrom+", Subject: "+subj);
		getLogger().info("ReportsTask: queuecache="+qcache.capacity()+"/"+cachesize);
		getLogger().info("ReportsTask Intervals: Low="+TimeOps.expandMilliTime(interval_low)
				+", High="+TimeOps.expandMilliTime(interval_high)
				+", Error="+TimeOps.expandMilliTime(interval_err));

		if (getDispatcher().getNafManAgent() != null) {
			com.grey.naf.nafman.NafManRegistry reg = getDispatcher().getNafManAgent().getRegistry();
			reg.registerHandler(com.grey.mailismus.nafman.Loader.CMD_COUNTERS, 0, this, getDispatcher());
		}
	}

	@Override
	protected void startTask()
	{
		if (one_shot) {
			try {
				processQueue();
			} catch (Exception ex) {
				throw new MailismusException("ReportsTask="+getName()+" failed to process Queue", ex);
			}
			stop(true);
			return;
		}
		tmr_qpoll = getDispatcher().setTimer(delay_start, TMRTYPE_POLLQUEUE, this);
		if (interval_housekeep != 0) tmr_housekeep = getDispatcher().setTimer(interval_housekeep, TMRTYPE_HOUSEKEEP, this);
	}

	@Override
	protected boolean stopNaflet()
	{
		return stop(false);
	}

	private boolean stop(boolean notify)
	{
		if (tmr_qpoll != null) tmr_qpoll.cancel();
		if (tmr_housekeep != null) tmr_housekeep.cancel();
		tmr_qpoll = null;
		tmr_housekeep = null;
		qcache.clear();
		qmgr.stop();
		if (audit != null) audit.close();
		if (notify) nafletStopped();
		return true;
	}

	@Override
	public void timerIndication(com.grey.naf.reactor.TimerNAF tmr, com.grey.naf.reactor.Dispatcher d)
	{
		long interval = 0;

		switch (tmr.getType())
		{
		case TMRTYPE_POLLQUEUE:
			tmr_qpoll = null;
			interval = pollQueue();
			if (interval != 0) tmr_qpoll = getDispatcher().setTimer(interval, TMRTYPE_POLLQUEUE, this);
			break;

		case TMRTYPE_HOUSEKEEP:
			tmr_housekeep = null;
			try {
				qmgr.housekeep();
			} catch (Exception ex) {
				getLogger().warn("ReportsTask: Queue housekeeping failed - "+ex);
			}
			tmr_housekeep = getDispatcher().setTimer(interval_housekeep, TMRTYPE_HOUSEKEEP, this);
			break;

		default:
			throw new IllegalStateException("ReportsTask: Missing case for timer-type="+tmr.getType());
		}
	}

	private long pollQueue()
	{
		long interval = interval_low;

		try {
			if (!processQueue()) {
				// didn't find any pending recipients
				interval = interval_high;
			}
		} catch (Throwable ex) {
			interval = interval_err;
			getLogger().log(LEVEL.ERR, ex, true, "ReportsTask: Failed to process queue");
		}
		return interval;
	}

	private boolean processQueue() throws java.io.IOException
	{
		qcache.clear();
		qmgr.getBounces(qcache);
		int bouncecnt = qcache.size();
		if (bouncecnt == 0) return false;
		qcache.sort();
		getLogger().trace("ReportsTask: QMGR Loaded Bounces="+bouncecnt);

		dtcal.setTimeInMillis(getDispatcher().getSystemTime());
		tmpsb.setLength(0);
		TimeOps.makeTimeRFC822(dtcal, tmpsb);
		time_now.populate(tmpsb);

		int qslot = 0;
		int slot1 = -1;
		int reportscnt = 0;
		boolean endOfReport = false;

		while (qslot != bouncecnt) {
			com.grey.mailismus.mta.queue.MessageRecip recip = qcache.get(qslot);
			recip.qstatus = com.grey.mailismus.mta.queue.MessageRecip.STATUS_DONE;
			if (recip.smtp_status == 0) recip.smtp_status = statuszero;
			String action = null;
			int next_slot1 = -1;
			stats_bounces++;

			if (recip.sender == null) {
				action = "Discarding bounced NDR";
				stats_ndrbounces++;
			} else if (!generate_ndr) {
				action = "Discarding bounced Msg";
				java.nio.file.Path fh = qmgr.getDiagnosticFile(recip.spid, recip.qid);
				Exception ex = FileOps.deleteFile(fh);
				if (ex != null) getLogger().info("ReportsTask: Failed to discard failure-reason="+fh.getFileName()+" for "+recip+" - "+ex);
			} else {
				// accumulate entries with same spool message
				if (slot1 == -1) {
					slot1 = qslot;
				} else {
					endOfReport = (recip.spid != qcache.get(slot1).spid);
					next_slot1 = qslot; //this will only become the case if accumulate is now false
				}
			}

			if (endOfReport) {
				issueReport(slot1, qslot - 1);
				reportscnt++;
				endOfReport = false;
				slot1 = next_slot1;
			}
			if (action != null && audit != null) audit.log(action, recip, true, getDispatcher().getSystemTime(), qmgr.externalSPID(recip.spid));
			qslot++;
		}

		if (slot1 != -1) {
			//process the final qcache entries
			issueReport(slot1, bouncecnt - 1);
			reportscnt++;
		}
		getLogger().info("ReportsTask: Issued reports="+reportscnt);

		// flush the queue cache
		qmgr.bouncesProcessed(qcache);
		return true;
	}

	private void issueReport(int slot1, int slotlast)
	{
		String action = "Bounced Msg";
		int bounced_spid = qcache.get(slot1).spid;
		int qid1 = qcache.get(slot1).qid;
		extspidbuf.setLength(0);
		extspidbuf.append(qmgr.externalSPID(bounced_spid));
		com.grey.mailismus.mta.queue.SubmitHandle msgh;

		try {
			msgh = generateNDR(bounced_spid, extspidbuf, slot1, slotlast);
		} catch (Throwable ex) {
			getLogger().log(LEVEL.ERR, ex, true, "ReportsTask: Failed to submit NDR for SPID="+Integer.toHexString(bounced_spid)+" to recips="+(slotlast-slot1+1));
			return;
		}
		int spid = msgh.spid;
		boolean submitted = qmgr.endSubmit(msgh, redirect_sink);

		if (submitted) {
			// Before committing the NDR, issue any duplicates that have been specified in various forms (file, or more emails).
			// The failure of any duplicates must not affect the original NDR.
			for (int idx = 0; idx != additionalRecipients.size(); idx++) {
				// Reports (which are distinguished by a null sender) are not allowed to have multiple recipients, so submit multiple times
				tmpbclist.clear();
				tmpbclist.add(additionalRecipients.get(idx));
				qmgr.submitCopy(spid, qid1, null, tmpbclist);
			}

			if (archiveDirectory != null) {
				try {
					qmgr.exportMessage(spid, qid1, archiveDirectory);
				} catch (Throwable ex) {
					getLogger().log(LEVEL.ERR, ex, true, "ReportsTask: Failed to export NDR="+qmgr.externalSPID(msgh.spid)+" to "+archiveDirectory);
				}
			}
		} else {
			action = "Discarding Msg (NDR failed)";
		}

		if (audit != null) {
			for (int idx = slot1; idx <= slotlast; idx++) {
				audit.log(action, qcache.get(idx), true, getDispatcher().getSystemTime(), extspidbuf);
			}
		}
	}

	private com.grey.mailismus.mta.queue.SubmitHandle generateNDR(int bounced_spid, CharSequence extspidfmt, int slot1, int slotlast)
			throws java.io.IOException
	{
		com.grey.mailismus.mta.queue.SubmitHandle msgh = null;
		com.grey.mailismus.mta.queue.MessageRecip recip = qcache.get(slot1);

		// Since the sender may get back multiple NDRs for his one original outgoing message, embed the QID of the first bounced recip
		// in each NDR in the NDR's Message-ID header, to make all the NDRs unique.
		ndr_msgid.populate(extspidfmt).append(MSGID_DLM).append(recip.qid, tmpsb);

		// All queue slots in the current range will have the same recvtime and sender, so use first slot's values for those too.
		dtcal.setTimeInMillis(recip.recvtime);
		tmpsb.setLength(0);
		TimeOps.makeTimeRFC822(dtcal, tmpsb);
		time_recv.populate(tmpsb);

		// Original message's sender becomes the recipient of this non-delivery report
		com.grey.base.utils.ByteChars officialrecip = recip.sender;
		int pos_rt = officialrecip.lastIndexOf((byte)com.grey.base.utils.EmailAddress.DLM_RT);
		if (pos_rt != -1) {
			// extract source-routed local part and convert to an FQDN address
			officialrecip = tmprecip.populate(officialrecip);
			int pos_dom = officialrecip.lastIndexOf((byte)com.grey.base.utils.EmailAddress.DLM_DOM);
			if (pos_dom != -1) officialrecip.setSize(pos_dom); //strip right-most domain
			officialrecip.setByte(pos_rt, com.grey.base.utils.EmailAddress.DLM_DOM);
		}
		com.grey.base.utils.ByteChars actualrecip = (redirectRecip == null ? officialrecip : redirectRecip);
		reportrecip.set(actualrecip);
		reportrecips.clear();
		reportrecips.add(reportrecip); //NB: if redirect_sink, this list isn't going to get used anywhere 
		msgh = qmgr.startSubmit(null, reportrecips, null, 0);
		boolean ok = false;

		try {
			msgh.write(ndr_headers);

			// Invariant NDR-headers string extends up to the name of the Date header, so continue with its value and a few other variable headers
			ndr_content.populate(time_now).append(Protocol.EOL_BC);
			ndr_content.append("Message-Id: ndr.").append(ndr_msgid).append(com.grey.base.utils.EmailAddress.DLM_DOM).append(mtarep).append(Protocol.EOL_BC);
			ndr_content.append("To: ").append(officialrecip).append(Protocol.EOL_BC);
			if (officialrecip != recip.sender) ndr_content.append("X-Bounced-Sender: ").append(recip.sender).append(Protocol.EOL_BC);

			// append the free-style descriptive bodypart
			addMimePart(1, "Non-Delivery Notification", "text/plain", ndr_content);
			ndr_content.append(ndr_textpart);

			// append the formal DSN bodypart - start with the per-message fields
			addMimePart(2, "Non-Delivery Report", "message/delivery-status", ndr_content);
			ndr_content.append(ndr_dsn_msg).append(time_recv).append(Protocol.EOL_BC);
			ndr_content.append(Protocol.EOL_BC);

			// now the per-recipient DSN fields
			for (int idx = slot1; idx <= slotlast; idx++) {
				if (idx != slot1) {
					recip = qcache.get(idx);
					ndr_msgid.populate(extspidfmt).append(MSGID_DLM).append(recip.qid, tmpsb);
					ndr_content.append(Protocol.EOL_BC);
				}
				ndr_content.append("Final-Recipient: RFC822; ").append(recip.mailbox_to);
				if (recip.domain_to != null) ndr_content.append(com.grey.base.utils.EmailAddress.DLM_DOM).append(recip.domain_to);
				ndr_content.append(Protocol.EOL_BC);
				ndr_content.append("Action: failed").append(Protocol.EOL_BC);
				ndr_content.append("Status: ").append(ndr_status).append(Protocol.EOL_BC);
				ndr_content.append("Final-Log-ID: ").append(ndr_msgid).append(Protocol.EOL_BC);
				setFailureReason(recip);
			}
			msgh.write(ndr_content);

			// now attach the body of the bounced message
			if (attachmsg_max != 0) {
				java.nio.file.Path fh = qmgr.getMessage(bounced_spid, recip.qid);
				attachMessage(msgh, fh);
			}

			// make sure that the generated spoolfile is properly terminated with CRLF
			msgh.write(Protocol.EOL_BC);
			ok = true;
		} finally {
			if (!ok) qmgr.endSubmit(msgh, true);
		}
		return msgh;
	}

	private void addMimePart(int num, String mimedesc, String mimetype, com.grey.base.utils.ByteChars buf)
	{
		if (num == 1) buf.append(mime_altprologue);
		buf.append(mime_bndry);

		if (mimedesc != null) buf.append("Content-Description: ").append(mimedesc).append(Protocol.EOL_BC);
		buf.append("Content-Type: ").append(mimetype).append(Protocol.EOL_BC);
		buf.append(Protocol.EOL_BC);
	}

	private void attachMessage(com.grey.mailismus.mta.queue.SubmitHandle msgh, java.nio.file.Path msgfile)
		throws java.io.IOException
	{
		java.io.InputStream msgstrm = null;

		try {
			msgstrm = java.nio.file.Files.newInputStream(msgfile, FileOps.OPENOPTS_NONE);
		} catch (Exception ex) {
			getLogger().log(LEVEL.TRC, ex, false, "ReportsTask: Failed to open spool file="+msgfile);
			return;
		}

		try {
			ndr_content.clear();
			addMimePart(3, "Bounced Message", "message/rfc822", ndr_content);
			msgh.write(ndr_content);
			int totalbytes = 0;

			while (totalbytes < attachmsg_max) {
				int nbytes = msgstrm.read(msgfilebuf);
				if (nbytes == -1) break;
				msgh.write(msgfilebuf, 0, nbytes);
				totalbytes += nbytes;
			}
		} finally {
			msgstrm.close();
		}
	}

	private void setFailureReason(com.grey.mailismus.mta.queue.MessageRecip recip)
	{
		ndr_content.append("Diagnostic-Code: SMTP; ");
		final java.nio.file.Path fh = qmgr.getDiagnosticFile(recip.spid, recip.qid);
		tmpbuf_failmsg.clear();
		int ip = 0;

		if (java.nio.file.Files.exists(fh, FileOps.LINKOPTS_NONE)) {
			try {
				FileOps.read(fh.toFile(), -1, tmpbuf_failmsg);
			} catch (Exception ex) {
				getLogger().log(LEVEL.WARN, ex, true, "ReportsTask: Failed to process failure-reason="+fh.getFileName()+" for "+recip);
				tmpbuf_failmsg.clear();
			} finally {
				try {
					java.nio.file.Files.delete(fh);
				} catch (Exception ex) {
					getLogger().log(LEVEL.WARN, ex, false, "ReportsTask: Failed to discard failure-reason="+fh.getFileName()+" for "+recip);
				}
			}
		}

		if (tmpbuf_failmsg.size() != 0) {
			int len = tmpbuf_failmsg.size();
			int off = 0;
			if (tmpbuf_failmsg.byteAt(off) == 1) {
				//special marker used by SMTP-Client to introduce IP address
				off++;
				ip = com.grey.base.utils.IP.net2ip(tmpbuf_failmsg.buffer(), tmpbuf_failmsg.offset(off));
				off += com.grey.base.utils.IP.IPADDR_OCTETS;
				len -= off;
			}
			ndr_content.append(tmpbuf_failmsg, off, len).append(Protocol.EOL_BC);
		} else {
			//complete the Diagnostic-Code field
			ndr_content.append(recip.smtp_status, tmpsb).append(Protocol.EOL_BC);
		}

		if (ip != 0) {
			// Existence of this field means Diagnostic-Code msg came from remote MTA - else it's our msg
			// See RFC-3464 2.3.6
			tmpsb.setLength(0);
			com.grey.base.utils.IP.displayDottedIP(ip, tmpsb);
			ndr_content.append("Remote-MTA: dns; [").append(tmpsb);
			ndr_content.append(']').append(Protocol.EOL_BC);
		}
	}

	@Override
	public CharSequence handleNAFManCommand(com.grey.naf.nafman.NafManCommand cmd) throws java.io.IOException
	{
		tmpsb.setLength(0);
		if (cmd.getCommandDef().code.equals(com.grey.mailismus.nafman.Loader.CMD_COUNTERS)) {
			tmpsb.append("Stats since ");
			TimeOps.makeTimeLogger(stats_start, tmpsb, true, true);
			tmpsb.append(" - Period=");
			TimeOps.expandMilliTime(getDispatcher().getSystemTime() - stats_start, tmpsb, false);
			tmpsb.append("<br/>Bounced Messages: ").append(stats_bounces);
			tmpsb.append("<br/>Bounced NDRs: ").append(stats_ndrbounces);
			if (StringOps.stringAsBool(cmd.getArg(com.grey.naf.nafman.NafManCommand.ATTR_RESET))) {
				stats_start = getDispatcher().getSystemTime();
				stats_bounces = 0;
				stats_ndrbounces = 0;
			}
		} else {
			return super.handleNAFManCommand(cmd);
		}
		return tmpsb;
	}
}
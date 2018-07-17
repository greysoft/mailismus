/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.ScheduledTime;

public final class Transcript
	implements java.io.Flushable
{
	private static final String DIR_IN = " << ";
	private static final String DIR_OUT = " >> ";

	private static final byte[] eolbuf = com.grey.base.utils.ByteOps.getBytes8(com.grey.base.config.SysProps.EOL);
	private static final byte[] evtlabelbuf = com.grey.base.utils.ByteOps.getBytes8(" Event: ");

	private final com.grey.naf.reactor.Dispatcher dsptch;
	private final com.grey.logging.Parameters logparams;
	private final com.grey.logging.Logger errorlogger;

	private java.io.File fh_active;
	private java.io.OutputStream strm;
	private long systime_rot;

	// these are merely pre-allocated for efficiency
	private final java.util.Calendar dtcal;
	private final StringBuilder sbmsg = new StringBuilder();
	private byte[] wrbuf = new byte[1024];  //will grow as necessary

	public String templatePath() {return logparams.pthnam;}
	public String getActivePath() {return fh_active == null ? null : fh_active.getAbsolutePath();}

	public static Transcript create(com.grey.naf.reactor.Dispatcher dsptch, com.grey.base.config.XmlConfig cfg, String xpath)
	{
		if (xpath != null) cfg = cfg.getSection(xpath+com.grey.base.config.XmlConfig.XPATH_ENABLED);
		if (!cfg.exists()) return null;
		com.grey.logging.Parameters params = new com.grey.logging.Parameters(cfg);
		params.reconcile();
		if (params.pthnam == null || params.pthnam.length() == 0) return null;
		return new Transcript(dsptch, params);
	}

	private Transcript(com.grey.naf.reactor.Dispatcher d, com.grey.logging.Parameters p)
	{
		dsptch = d;
		logparams = p;
		errorlogger = (dsptch == null ? null : dsptch.getLogger());
		dtcal = TimeOps.getCalendar(null);
		if (dsptch != null) {
			dsptch.getFlusher().register(this);
			dsptch.getLogger().info("Created "+dsptch.name+" Transcript-Logger: "+logparams);
		}
	}

	// NB: The dtcal clock-time is guaranteed to be set on entry
	private void open(long systime) throws java.io.IOException
	{
		String oldpath = getActivePath();
		String newpath = ScheduledTime.embedTimestamp(logparams.rotfreq, dtcal, logparams.pthnam, null);
		if (newpath.equals(oldpath)) {
			//can't rotate till pathname advances to next state
			return;
		}
		// Infrequent event, so don't worry about this memory churn.
		// Can't use sbmsg because it contains a pending message while we rotate.
		java.util.Date dtnow = new java.util.Date(systime);
		StringBuilder sb = new StringBuilder(128);
		if (strm != null) {
			sb.append("\nRollover to ").append(newpath).append(" at ").append(dtnow).append("\n");
			write(sb);
		}
		close(0);

		if (logparams.rotfreq != ScheduledTime.FREQ.NEVER) {
			systime_rot = ScheduledTime.getNextTime(logparams.rotfreq, dtcal);	
		}
		fh_active = new java.io.File(newpath);
		java.io.File dirh = fh_active.getParentFile();
		if (dirh != null) FileOps.ensureDirExists(dirh);
		java.io.FileOutputStream fstrm = new java.io.FileOutputStream(fh_active, true);
		strm = new java.io.BufferedOutputStream(fstrm, logparams.bufsiz);

		sb.setLength(0);
		if (oldpath != null) {
			sb.append("Rollover from ").append(oldpath);
		} else {
			sb.append("Opening transcript - ").append(logparams.pthnam);
		}
		sb.append(" at ").append(dtnow).append("\n\n");
		write(sb);
	}

	public void close(long systime)
	{
		if (dsptch != null) dsptch.getFlusher().unregister(this);
		if (strm == null) return;

		if (systime != 0) {
			java.util.Date dtnow = new java.util.Date(systime);
			write("Closing transcript at "+dtnow+com.grey.base.config.SysProps.EOL);
		}

		try {
			strm.close();
		} catch (Exception ex) {
			warning("Transcript Close failed on "+templatePath()+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
		strm = null;
	}

	public void connection_in(String entity_id, CharSequence ip_from, int port_from, CharSequence ip_to, int port_to, long systime, boolean ssl)
	{
		logconnection(entity_id, ip_from, port_from, ip_to, port_to, null, systime, DIR_IN, ssl);
	}

	public void connection_out(String entity_id, CharSequence ip_from, int port_from, CharSequence ip_to, int port_to, long systime,
			CharSequence remote, boolean ssl)
	{
		logconnection(entity_id, ip_from, port_from, ip_to, port_to, remote, systime, DIR_OUT, ssl);
	}

	public void data_in(String entity_id, java.nio.ByteBuffer data, int off, long systime)
	{
		logdata(entity_id, systime, data, off, DIR_IN);
	}
	
	public void data_in(String entity_id, ByteArrayRef data, long systime)
	{
		logdata(entity_id, systime, data, DIR_IN);
	}

	public void data_out(String entity_id, java.nio.ByteBuffer data, int off, long systime)
	{
		logdata(entity_id, systime, data, off, DIR_OUT);
	}

	public void data_out(String entity_id, ByteArrayRef data, long systime)
	{
		logdata(entity_id, systime, data, DIR_OUT);
	}

	public void event(String entity_id, CharSequence msg, long systime)
	{
		prefix(entity_id, systime, null);
		write(evtlabelbuf);
		write(msg);
		if (msg.charAt(msg.length() - 1) != '\n') write(eolbuf);
	}

	private void logdata(String entity_id, long systime, java.nio.ByteBuffer data, int off, String direction)
	{
		int blen = data.limit() - off;
		if (blen == 0) return;
		prefix(entity_id, systime, direction);
		byte[] barr;
		int boff;

		if (data.hasArray()) {
			barr = data.array();
			boff = off + data.arrayOffset();
		} else {
			// need to copy via intermediate primitive buffer, to avail of bulk get/put
			if (wrbuf.length < blen + eolbuf.length) wrbuf = new byte[blen + eolbuf.length];
			data.position(off);
			data.get(wrbuf, 0, blen);
			barr = wrbuf;
			boff = 0;
		}
		write(barr, boff, blen);
		if (barr[boff + blen - 1] != '\n') write(eolbuf);
	}

	private void logdata(String entity_id, long systime, ByteArrayRef data, String direction)
	{
		if (data.size() == 0) return;
		prefix(entity_id, systime, direction);
		write(data.buffer(), data.offset(), data.size());
		if (data.byteAt(data.size() - 1) != '\n') write(eolbuf);
	}

	// Sample: E2 ===== 2010-07-11 01:47:05.819 << Connection from 192.168.101.13:51209 to 192.168.101.132:25
	private void logconnection(String entity_id, CharSequence ip_from, int port_from, CharSequence ip_to, int port_to,
			CharSequence remote, long systime, String direction, boolean ssl)
	{
		initEntry(systime);
		sbmsg.setLength(0);
		sbmsg.append(entity_id).append(" ===== ");
		TimeOps.makeTimeLogger(dtcal, sbmsg, true, true);
		sbmsg.append(direction);

		if (ip_from == null){
			sbmsg.append("Connection failed");
		} else {
			if (ssl) sbmsg.append("SSL ");
			sbmsg.append("Connection from ").append(ip_from).append(':').append(port_from);
		}
		sbmsg.append(" to ").append(ip_to).append(':').append(port_to);
		if (remote != null) sbmsg.append(" for remote=").append(remote);
		sbmsg.append(com.grey.base.config.SysProps.EOL);
		write(sbmsg);
	}

	// Sample: E1 17:28:56.271 >> HELO
	private void prefix(String entity_id, long systime, String direction)
	{
		initEntry(systime);
		sbmsg.setLength(0);
		sbmsg.append(entity_id).append(' ');
		TimeOps.makeTimeLogger(dtcal, sbmsg, false, true);
		if (direction != null) sbmsg.append(direction);
		write(sbmsg);
	}

	private void initEntry(long systime)
	{
		if (systime == 0) systime = System.currentTimeMillis();
		dtcal.setTimeInMillis(systime);
		try {
			if (strm == null
					|| (systime_rot != 0 && systime > systime_rot)
					|| (fh_active != null && logparams.maxsize != 0 && fh_active.length() >= logparams.maxsize)) {
				open(systime);
			}
		} catch (Throwable ex) {
			warning("Transcript Open failed on "+templatePath()+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
	}

	private void write(CharSequence data)
	{
		int len = data.length();
		if (wrbuf.length < len) wrbuf = new byte[len];

		for (int idx = 0; idx != len; idx++) {
			wrbuf[idx] = (byte)data.charAt(idx);
		}
		write(wrbuf, 0, len);
	}

	private void write(byte[] arr, int off, int len)
	{
		try {
			strm.write(arr, off, len);
			if (logparams.flush_interval == 0) strm.flush();
		} catch (Throwable ex) {
			warning("Transcript Write failed on "+templatePath()+" - "+com.grey.base.ExceptionUtils.summary(ex));
		}
	}

	private void write(byte[] arr)
	{
		write(arr, 0, arr.length);
	}

	@Override
	public void flush() throws java.io.IOException
	{
		if (strm != null) strm.flush();
	}

	private void warning(String msg)
	{
		if (errorlogger == null) {
			System.out.println(msg);
		} else {
			errorlogger.warn(msg);
		}
	}
}
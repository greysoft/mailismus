/*
 * Copyright 2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver.client;

import com.grey.base.utils.ByteArrayRef;

public class SmtpResponseDescriptor {
	private static final int STATUSCODE_DIGITS = 3;

	private final short smtp_status; //xxx convert to int and make the Protocol constants ints too
	private final String enhanced_status;
	private final String msg;

	public static SmtpResponseDescriptor parse(ByteArrayRef rspdata, boolean withEnhanced) {
		StringBuilder sb = null;
		String enhanced = null;
		String msg = "";

		int rsplen = rspdata.size();
		while (rsplen != 0 && rspdata.buffer()[rspdata.offset() + rsplen  - 1] <= ' ') {
			rsplen--;
		}

		if (rsplen < STATUSCODE_DIGITS)
			return null;

		int off = rspdata.offset();
		short statuscode = (short)parseDecimal(STATUSCODE_DIGITS, rspdata.buffer(), off);
		off += STATUSCODE_DIGITS;

		off++; //skip space
		//xxx could also be hyphen (see Google EHLO response) and return null if neither - caller must handle null
		if (off >= rsplen)
			return new SmtpResponseDescriptor(statuscode, enhanced, msg);

		if (withEnhanced) {
			int off2 = off;
			while (rspdata.buffer()[off2] != ' ') {
				off2++;
				if (off2 == rsplen) break;
			}
			sb = (StringBuilder)rspdata.toString(sb, off, off2 - off);
			enhanced = sb.toString();
			off = off2 + 1;
			sb.setLength(0);
		}

		if (off <= rsplen) {
			msg = rspdata.toString(sb, off, rsplen - off).toString();
		}
		return new SmtpResponseDescriptor(statuscode, enhanced, msg);
	}

	public SmtpResponseDescriptor(short smtp_status, String enhanced_status, String msg) {
		this.smtp_status = smtp_status;
		this.enhanced_status = enhanced_status;
		this.msg = msg;
	}

	public short smtpStatus() {
		return smtp_status;
	}

	public String enhancedStatus() {
		return enhanced_status;
	}

	public String message() {
		return msg;
	}

	@Override
	public String toString() {
		return "SmtpResponse [smtp_status="+smtp_status+", enhanced="+enhanced_status+ ", msg="+msg+"]";
	}

	private static int parseDecimal(int numDigits, byte[] buf, int off) {
		int idx = off + numDigits - 1;
		int factor = 1;
		int val = 0;

		for (int loop = 0; loop != numDigits; loop++) {
			val += (buf[idx--] - '0') * factor;
			factor *= 10;
		}
		return val;
	}
}
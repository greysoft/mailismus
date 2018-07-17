/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.errors;

import com.grey.naf.errors.NAFException;

public class MailismusException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public MailismusException(String message) {
		super(message);
	}

	public MailismusException(Throwable cause) {
		super(cause);
	}

	public MailismusException(String message, Throwable cause) {
		super(message, cause);
	}

	public static boolean isError(Throwable ex) {
		if (ex instanceof MailismusException) return false;
		return NAFException.isError(ex);
	}
}

/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.errors;

public class MailismusConfigException extends MailismusException {
	private static final long serialVersionUID = 1L;

	public MailismusConfigException(String message) {
		super(message);
	}

	public MailismusConfigException(Throwable cause) {
		super(cause);
	}

	public MailismusConfigException(String message, Throwable cause) {
		super(message, cause);
	}
}

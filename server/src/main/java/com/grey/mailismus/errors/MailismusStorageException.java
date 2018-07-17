/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.errors;

public class MailismusStorageException extends MailismusException {
	private static final long serialVersionUID = 1L;

	public MailismusStorageException(String message) {
		super(message);
	}

	public MailismusStorageException(Throwable cause) {
		super(cause);
	}

	public MailismusStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}

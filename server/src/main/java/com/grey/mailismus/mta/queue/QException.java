/*
 * Copyright 2012-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.queue;

import com.grey.mailismus.errors.MailismusException;

public class QException
	extends MailismusException
{
	private static final long serialVersionUID = 1L;

	public QException(String msg) {
		super(msg);
	}

	public QException(String msg, Throwable ex) {
		super(msg, ex);
	}
}
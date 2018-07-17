/*
 * Copyright 2012-2015 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms;

/**
 * In addition to implementing this interface, Message-Store classes must also provide a constructor
 * with this signature:<br/>
 * <code>classname(com.grey.naf.reactor.Dispatcher, com.grey.base.config.XmlConfig, com.grey.mailismus.directory.Directory dir)</code><br/>
 */
public interface MessageStore
{
	public com.grey.mailismus.directory.Directory directory();
	public void deliver(CharSequence username, java.io.File msg) throws java.io.IOException;
}
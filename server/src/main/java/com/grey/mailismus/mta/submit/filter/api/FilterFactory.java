/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit.filter.api;

/**
 * This represents a user-defined message-filter factory.
 * <br>
 * A single instance of this class is instantiated, during Mailismus startup, so it can do any
 * expensive setup required.
 * <br>
 * This is the class that must be specified in the Mailismus config (not the actual MessageFilter
 * class) and it is created via reflection.
 * <br>
 * In addition to implementing the required interface methods, classes of this type must also
 * implement a constructor which takes a single argument of type com.grey.base.config.XmlConfig.
 * This allows it to read the Mailismus config file (if it's interested).
 */
public interface FilterFactory {
	/**
	 * Create a filter instance. This is expected to be an inexpensive op, as it will be
	 * called for every incoming message.
	 */
	MessageFilter create();
}

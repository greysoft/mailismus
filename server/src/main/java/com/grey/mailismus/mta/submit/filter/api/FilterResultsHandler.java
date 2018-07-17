/*
 * Copyright 2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit.filter.api;

/**
 * This represents the object which gets notified by MessageFilter with its final verdict.
 */
public interface FilterResultsHandler {
    /**
     * The filter calls this method to conclude the message scan, and tell Mailismus that it has
     * accepted the message.
     * <br>
     * After calling this method, this filter instance is redundant and should be discarded.
     * At the very least, it should not interact with Mailismus again.
     */
    void approved();

    /**
     * The filter calls this method to conclude the message scan, and tell Mailismus that it has
     * rejected the message.
     * <br>
     * After calling this method, this filter instance is redundant and should be discarded.
     * At the very least, it should not interact with Mailismus again.
     *
     * @param      rsp     Represents the SMTP reject response to send to the remote client.
     *                     Must be the full response, beginning with the 3-digit SMTP error code.
     *                     Example: 550 Message rejected by filter
     */
    void rejected(String rsp);
}

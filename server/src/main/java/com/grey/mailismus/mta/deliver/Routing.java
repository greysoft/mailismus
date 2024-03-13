/*
 * Copyright 2015-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.config.XmlConfig;
import com.grey.naf.NAFConfig;
import com.grey.logging.Logger;
import com.grey.mailismus.errors.MailismusConfigException;

public class Routing
{
	private final Map<ByteChars,Relay> byDestDomain = new HashMap<>();
	private final Map<ByteChars,Relay> bySrcDomain = new HashMap<>();
	private final Map<ByteChars,Relay> bySrcAddress = new HashMap<>();
	private final Relay dflt; //default relay-server - possibly the only one (ie. if in slave-relay mode)
	private final Relay interceptor;

	public boolean haveDefaultRelay() {return (dflt != null);}
	public boolean modeSlaveRelay() {return (haveDefaultRelay() && byDestDomain.size()+bySrcDomain.size()+bySrcAddress.size() == 0);}
	public boolean hasRoute(ByteChars destdomain) {return (byDestDomain.containsKey(destdomain));}
	public Relay getInterceptor() {return interceptor;}

	public Routing(XmlConfig relaycfg, NAFConfig nafcfg, Logger logger) throws IOException
	{
		Relay rlysrvr = null;
		XmlConfig[] cfgnodes = relaycfg.getSections("relay");
		if (cfgnodes != null) {
			for (int idx = 0; idx != cfgnodes.length; idx++) {
				Relay relay = Relay.create("relay-"+idx, cfgnodes[idx], false, nafcfg);
				if (relay.destDomains == null && relay.senders == null) {
					if (rlysrvr != null) throw new MailismusConfigException("Duplicate general relay: "+relay);
					rlysrvr = relay;
				} else {
					if (relay.destDomains != null) {
						for (ByteChars destdom : relay.destDomains) {
							if (byDestDomain.put(destdom, relay) != null) {
								throw new MailismusConfigException("Duplicate route for "+destdom+": "+relay);
							}
						}
					}
					if (relay.senders != null) {
						for (EmailAddress emaddr : relay.senders) {
							if (emaddr.mailbox.size() == 0) {
								if (bySrcDomain.put(emaddr.domain, relay) != null) {
									throw new MailismusConfigException("Duplicate source-domain route for "+emaddr.domain+": "+relay);
								}
							} else {
								if (bySrcAddress.put(emaddr.full, relay) != null) {
									throw new MailismusConfigException("Duplicate source-address route for "+emaddr.full+": "+relay);
								}
							}
						}
					}
				}
			}
		}
		dflt = rlysrvr;

		cfgnodes = relaycfg.getSections("intercept"); //undocumented back door
		Relay irly = null;
		if (cfgnodes != null) {
			if (cfgnodes.length != 1) throw new MailismusConfigException("Only one interceptor can be defined - found relays/intercept="+cfgnodes.length);
			irly = Relay.create("interceptor", cfgnodes[0], true, nafcfg);
		}
		interceptor = irly;

		if (logger != null) {
			logger.info("SMTP-Routing: Default Relay="+dflt);
			logger.info("SMTP-Routing: Destination Relays="+byDestDomain.size()+"/"+byDestDomain.keySet());
			logger.info("SMTP-Routing: Sender-Address Relays="+bySrcAddress.size()+"/"+bySrcAddress.keySet());
			logger.info("SMTP-Routing: Sender-Domain Relays="+bySrcDomain.size()+"/"+bySrcDomain.keySet());
		}
	}

	public Relay getRoute(ByteChars destdomain)
	{
		Relay relay = byDestDomain.get(destdomain);
		if (relay == null) relay = dflt;
		return relay;
	}

	public Relay getSourceRoute(EmailAddress sender, int ip_sender)
	{
		Relay relay = bySrcAddress.get(sender.full);
		if (relay == null) relay = bySrcDomain.get(sender.domain);
		if (relay == null || relay.senderIpNets == null || ip_sender == 0) return relay;
		for (IP.Subnet net : relay.senderIpNets) {
			if (net.isMember(ip_sender)) return relay;
		}
		return null;
	}
}
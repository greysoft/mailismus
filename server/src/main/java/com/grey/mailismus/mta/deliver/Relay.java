/*
 * Copyright 2012-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.utils.TSAP;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.naf.SSLConfig;

/*
 * This class specifies the connection details for a remote SMTP server we use as a relay for specific domains.
 */
public final class Relay
{
	public final TSAP tsap;
	public final com.grey.naf.SSLConfig sslconfig;
	public final boolean auth_enabled;
	public final boolean auth_initrsp;
	public final boolean auth_compat; //handle Protocol.EXT_AUTH_COMPAT responses from this server
	public final com.grey.base.sasl.SaslEntity.MECH auth_override;
	public final String usrnam;
	public final ByteChars passwd;
	public final boolean dns_only; //only relevant for interceptor mode - true means don't intercept statically configured servers
	final ByteChars[] destdomains;
	final EmailAddress[] senders;
	final IP.Subnet[] sender_ipnets; //if present, sender connect from one of these IPs, to match a source-Relay
	private final String relay_string;
	private final String display_txt;

	@Override public String toString() {return relay_string;}
	public String display() {return display_txt;}

	public Relay(com.grey.base.config.XmlConfig cfg, boolean interceptor, com.grey.naf.NAFConfig nafcfg, com.grey.logging.Logger log)
		throws java.io.IOException
	{
		java.util.ArrayList<ByteChars> lst_destdoms = new java.util.ArrayList<ByteChars>();
		java.util.ArrayList<EmailAddress> lst_senders = new java.util.ArrayList<EmailAddress>();
		java.util.ArrayList<IP.Subnet> lst_subnets = new java.util.ArrayList<IP.Subnet>();
		if (interceptor) {
			dns_only = cfg.getBool("@dns", false);
		} else {
			dns_only = false;
			String s = cfg.getValue("@destdomains", false, null);
			if (s != null) {
				String[] arr = s.split(",");
				for (int idx = 0; idx != arr.length; idx++) {
					s = arr[idx].trim();
					if (s.length() == 0) continue;
					if (s.indexOf(EmailAddress.DLM_DOM) != -1) throw new MailismusConfigException("Relay destination must be domain name - "+s);
					lst_destdoms.add(new ByteChars(s.toLowerCase()));
				}
			}

			s = cfg.getValue("@senders", false, null);
			if (s != null) {
				java.util.ArrayList<ByteChars> doms = new java.util.ArrayList<ByteChars>();
				String[] arr = s.split(",");
				for (int idx = 0; idx != arr.length; idx++) {
					s = arr[idx].trim();
					if (s.length() == 0) continue;
					EmailAddress emaddr = new EmailAddress(s.toLowerCase());
					emaddr.decompose(true);
					if (lst_senders.contains(emaddr)) continue;
					lst_senders.add(emaddr);
					if (emaddr.mailbox.size() == 0) doms.add(emaddr.domain);
				}
				for (int idx = lst_senders.size() - 1; idx >= 0; idx--) {
					EmailAddress emaddr = lst_senders.get(idx);
					if (emaddr.mailbox.size() != 0 && doms.contains(lst_senders.get(idx).domain)) lst_senders.remove(idx);
				}
			}
		}
		destdomains = (lst_destdoms.size() == 0 ? null : lst_destdoms.toArray(new ByteChars[lst_destdoms.size()]));
		senders = (lst_senders.size() == 0 ? null : lst_senders.toArray(new EmailAddress[lst_senders.size()]));

		String ipspec = cfg.getValue("@address", true, null);
		tsap = TSAP.build(ipspec, com.grey.mailismus.mta.Protocol.TCP_PORT, true);

		if (senders != null) {
			String s = cfg.getValue("@sendernets", false, null);
			if (s != null) {
				String[] arr = s.split(",");
				for (int idx = 0; idx != arr.length; idx++) {
					s = arr[idx].trim();
					if (s.length() == 0) continue;
					IP.Subnet ipnet = IP.parseSubnet(s);
					lst_subnets.add(ipnet);
				}
			}
		}
		sender_ipnets = (lst_subnets.size() == 0 ? null : lst_subnets.toArray(new IP.Subnet[lst_subnets.size()]));

		auth_enabled = cfg.getBool("auth/@enabled", false);
		auth_initrsp = cfg.getBool("auth/@initrsp", false);
		auth_compat = cfg.getBool("auth/@compat", false);
		if (!auth_enabled) {
			usrnam = null;
			passwd = null;
			auth_override = null;
		} else {
			usrnam = cfg.getValue("auth/username", false, null);
			passwd = new ByteChars(cfg.getValue("auth/password", false, null));
			String val = cfg.getValue("auth/@override", false, null);
			auth_override = (val == null ? null : com.grey.base.sasl.SaslEntity.MECH.valueOf(val.toUpperCase()));
		}

		com.grey.base.config.XmlConfig sslcfg = cfg.getSection("ssl");
		if (sslcfg == null || !sslcfg.exists()) {
			sslconfig = null;
		} else {
			sslconfig = new SSLConfig.Builder()
					.withPeerCertName(ipspec)
					.withIsClient(true)
					.withXmlConfig(sslcfg, nafcfg)
					.build();
		}

		String txt = "SMTP-"+(interceptor ? "Interceptor"+(dns_only?"/DNS":"") : "Relay")+"=";
		if (usrnam != null) txt += usrnam+"@";
		txt += tsap;
		if (sslconfig != null) txt += "/SSL";
		display_txt = txt;
		if (destdomains != null) txt += "=>"+(destdomains.length==1?destdomains[0]:destdomains.length+"/"+lst_destdoms);
		if (senders != null) txt += "; Senders="+(senders.length==1?senders[0]:senders.length+"/"+lst_senders);
		if (sender_ipnets != null) txt += "; SenderNets="+sender_ipnets.length+"/"+lst_subnets;
		relay_string = txt;

		if (log != null) {
			log.info(relay_string);
			String indent = new String(new char[5]).replace('\0', ' ');
			if (auth_enabled) {
				log.info(indent+"Authenticate with override="+auth_override+"/initrsp="+auth_initrsp+(auth_compat?"/compat="+true:""));
			}
			if (sslconfig != null) log.info(indent+sslconfig);
		}
	}
}
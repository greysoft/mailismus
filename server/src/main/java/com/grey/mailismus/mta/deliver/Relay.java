/*
 * Copyright 2012-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.grey.base.config.XmlConfig;
import com.grey.base.sasl.SaslEntity;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.base.utils.TSAP;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.mta.Protocol;
import com.grey.mailismus.mta.deliver.client.SmtpRelay;
import com.grey.naf.NAFConfig;
import com.grey.naf.reactor.config.SSLConfig;

/*
 * This class specifies the connection details for a remote SMTP server we use as a relay for specific domains.
 */
public class Relay extends SmtpRelay
{
	public final ByteChars[] destDomains;
	public final EmailAddress[] senders;
	public final IP.Subnet[] senderIpNets; //if present, sender connect from one of these IPs, to match a source-Relay
	public final boolean dnsOnly; //only relevant for interceptor mode - true means don't intercept statically configured servers
	private final String relayString;

	public static Relay create(String name, XmlConfig cfg, boolean isInterceptor, NAFConfig nafcfg) throws IOException
	{
		String usrnam = null;
		CharSequence passwd = null;
		SaslEntity.MECH auth_override = null;
		SSLConfig sslconfig = null;
		boolean dns_only = false;

		List<ByteChars> lst_destdoms = new ArrayList<>();
		List<EmailAddress> lst_senders = new ArrayList<>();
		List<IP.Subnet> lst_subnets = new ArrayList<>();

		if (isInterceptor) {
			dns_only = cfg.getBool("@dns", false);
		} else {
			String s = cfg.getValue("@destdomains", false, null);
			if (s != null) {
				String[] arr = s.split(",");
				for (int idx = 0; idx != arr.length; idx++) {
					s = arr[idx].trim();
					if (s.isEmpty()) continue;
					if (s.indexOf(EmailAddress.DLM_DOM) != -1) throw new MailismusConfigException("Relay destination must be domain name - "+s);
					lst_destdoms.add(new ByteChars(s.toLowerCase()));
				}
			}

			s = cfg.getValue("@senders", false, null);
			if (s != null) {
				List<ByteChars> doms = new ArrayList<>();
				String[] arr = s.split(",");
				for (int idx = 0; idx != arr.length; idx++) {
					s = arr[idx].trim();
					if (s.isEmpty()) continue;
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
		ByteChars[] destdomains = (lst_destdoms.isEmpty() ? null : lst_destdoms.toArray(new ByteChars[lst_destdoms.size()]));
		EmailAddress[] senders = (lst_senders.isEmpty() ? null : lst_senders.toArray(new EmailAddress[lst_senders.size()]));

		name = cfg.getValue("@name", true, name);
		String ipspec = cfg.getValue("@address", true, null);
		TSAP tsap = TSAP.build(ipspec, Protocol.TCP_PORT, true);

		if (senders != null) {
			String s = cfg.getValue("@sendernets", false, null);
			if (s != null) {
				String[] arr = s.split(",");
				for (int idx = 0; idx != arr.length; idx++) {
					s = arr[idx].trim();
					if (s.isEmpty()) continue;
					IP.Subnet ipnet = IP.parseSubnet(s);
					lst_subnets.add(ipnet);
				}
			}
		}
		IP.Subnet[] sender_ipnets = (lst_subnets.isEmpty() ? null : lst_subnets.toArray(new IP.Subnet[lst_subnets.size()]));

		boolean auth_enabled = cfg.getBool("auth/@enabled", false);
		boolean auth_initrsp = cfg.getBool("auth/@initrsp", false);
		boolean auth_compat = cfg.getBool("auth/@compat", false);
		if (auth_enabled) {
			usrnam = cfg.getValue("auth/username", false, null);
			passwd = new ByteChars(cfg.getValue("auth/password", false, null));
			String val = cfg.getValue("auth/@override", false, null);
			auth_override = (val == null ? null : SaslEntity.MECH.valueOf(val.toUpperCase()));
		}

		XmlConfig sslcfg = cfg.getSection("ssl");
		if (sslcfg != null && sslcfg.exists()) {
			sslconfig = new SSLConfig.Builder()
					.withPeerCertName(ipspec)
					.withIsClient(true)
					.withXmlConfig(sslcfg, nafcfg)
					.build();
		}

		return builder()
				.withName(name)
				.withAddress(tsap)
				.withSslConfig(sslconfig)
				.withAuthRequired(auth_enabled)
				.withAuthOverride(auth_override)
				.withAuthCompat(auth_compat)
				.withAuthInitialResponse(auth_initrsp)
				.withUsername(usrnam)
				.withPassword(passwd)
				.withDestDomains(destdomains)
				.withSenders(senders)
				.withSenderIpNets(sender_ipnets)
				.withDnsOnly(dns_only)
				.withInterceptor(isInterceptor)
				.build();
	}

	public Relay(Builder<?> bldr) {
		super(bldr);
		this.dnsOnly = bldr.dnsOnly;
		this.destDomains = bldr.destDomains;
		this.senders = bldr.senders;
		this.senderIpNets = bldr.senderIpNets;

		relayString = super.toString()+"=>ForwarderRelay["
				+(bldr.isInterceptor ? "Interceptor"+(dnsOnly?"/DNS":"")+", " : "")
				+"Senders="+(senders==null?"":senders.length+"/"+Arrays.asList(senders))
				+", SenderNets="+(senderIpNets==null?"":senderIpNets.length+"/"+Arrays.asList(senderIpNets))
				+", DestDomain="+(destDomains==null?"":destDomains.length+"/"+Arrays.asList(destDomains))
				+"]";
	}

	@Override public String toString() {
		return relayString;
	}

	public static Builder<?> builder() {
		return new Builder<>();
	}


	public static class Builder<T extends Builder<T>> extends SmtpRelay.Builder<T> {
		private boolean dnsOnly;
		private ByteChars[] destDomains;
		private EmailAddress[] senders;
		private IP.Subnet[] senderIpNets;
		private boolean isInterceptor;

		private Builder() {}

		public T withDnsOnly(boolean dnsOnly) {
			this.dnsOnly = dnsOnly;
			return self();
		}

		public T withDestDomains(ByteChars[] destDomains) {
			this.destDomains = destDomains;
			return self();
		}

		public T withSenders(EmailAddress[] senders) {
			this.senders = senders;
			return self();
		}

		public T withSenderIpNets(IP.Subnet[] senderIpNets) {
			this.senderIpNets = senderIpNets;
			return self();
		}

		public T withInterceptor(boolean val) {
			this.isInterceptor = val;
			return self();
		}

		@Override
		public Relay build() {
			return new Relay(this);
		}
	}
}
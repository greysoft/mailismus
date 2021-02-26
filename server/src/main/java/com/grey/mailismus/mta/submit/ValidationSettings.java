/*
 * Copyright 2010-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.submit;

import com.grey.base.config.XmlConfig;
import com.grey.mailismus.errors.MailismusConfigException;

class ValidationSettings
{
	public enum HOSTDIR {BACKWARD, FORWARD, BOTH}  // direction in which hostnames should be validated - BACKWARD requires IP to begin with

	public final boolean validate;
	public final boolean syntaxOnly;  //lets us reject obvious garbage, without overhead of DNS lookups
	public final boolean allowTimeout;  //accept as valid if we overwhelm DNS servers - will at least know syntax is valid
	public final HOSTDIR direction;
	public final int dnsflags;

	private final boolean allowDottedIP;  //allow hostname to be a dotted IP - incompatible with PTR validation
	private final boolean fqdn;  //FQDN required? We don't actually check for FQDN as such, it's more of a must-have-dots requirement
	private final boolean isMX;

	public ValidationSettings(XmlConfig cfg, String item, ValidationSettings dflts, boolean is_mx)
	{
		isMX = is_mx;
		validate = cfg.getBool(item, dflts==null? true : dflts.validate);
		syntaxOnly = cfg.getBool(item+"/@syntaxonly", dflts==null? false : dflts.syntaxOnly);
		fqdn = cfg.getBool(item+"/@fqdn", dflts==null? false : dflts.fqdn);
		allowTimeout = cfg.getBool(item+"/@allowtimeout", dflts==null? false : dflts.allowTimeout);

		boolean allow_dottedIP = cfg.getBool(item+"/@dotted", dflts==null? true : dflts.allowDottedIP);
		String dirspec = cfg.getValue(item+"/@direction", false, null);
		if (dirspec == null) {
			//direction is irrelevant for MX validation, as is allowDotted - but setting FORWARD aligns dnsflags for matches()
			dirspec = (dflts == null || isMX ? HOSTDIR.FORWARD.toString() : dflts.direction.toString());
		} else {
			if (isMX) {
				throw new MailismusConfigException("Invalid config: "+item+"/direction="+dirspec +" - 'direction' only applies to HELO");
			}
		}
		if (syntaxOnly && !dirspec.equals(HOSTDIR.FORWARD.toString())) {
			throw new MailismusConfigException("Invalid config: "+item+"/direction="+dirspec +" - incompatible with syntaxonly");
		}

		direction = HOSTDIR.valueOf(dirspec.toUpperCase());
		allowDottedIP = (direction == HOSTDIR.FORWARD ? allow_dottedIP : false);

		int flags = 0;
		if (syntaxOnly) flags |= com.grey.naf.dns.resolver.ResolverDNS.FLAG_SYNTAXONLY;
		if (fqdn) flags |= com.grey.naf.dns.resolver.ResolverDNS.FLAG_MUSTHAVEDOTS;
		if (!allowDottedIP) flags |= com.grey.naf.dns.resolver.ResolverDNS.FLAG_NODOTTEDIP;
		dnsflags = flags;
	}

	public boolean matches(ValidationSettings obj2)
	{
		if (obj2.validate ^ validate) return false;
		if (!validate) return true;  // validation is off in both cases, so rest doesn't matter
		if (obj2.isMX ^ isMX) return false;
		if (obj2.allowTimeout ^ allowTimeout) return false;
		if (obj2.direction != direction) return false;
		return (obj2.dnsflags == dnsflags);
	}

	public String toString(String name, ValidationSettings common)
	{
		StringBuilder sb = new StringBuilder(128);
		sb.append(name).append('=');

		if (common == this) {
			sb.append("common");
			return sb.toString();
		}
		sb.append(validate);

		if (validate) {
			sb.append(", syntax-only=").append(syntaxOnly);
			sb.append(", allow-timeout=").append(allowTimeout);
			if (!isMX) sb.append(", direction=").append(direction).append(", dottedIP=").append(allowDottedIP);
			sb.append(", fqdn=").append(fqdn);
			sb.append(" - DNS=0x").append(Integer.toHexString(dnsflags));
		}
		return sb.toString();
	}
}
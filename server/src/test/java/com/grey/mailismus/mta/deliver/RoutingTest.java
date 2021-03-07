/*
 * Copyright 2015-2018 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.EmailAddress;
import com.grey.base.utils.IP;
import com.grey.naf.NAFConfig;
import com.grey.mailismus.mta.Protocol;

public class RoutingTest
{
	private static final String cfgxml_norelays = "<relays></relays>";
	private static final String cfgxml_smarthost = "<relays>"
			+"<relay address=\"10.0.0.1\"/>"
			+"</relays>";
	private static final String cfgxml_dfltroute = "<relays>"
			+"<relay address=\"10.0.0.1:25001\"/>"
			+"<relay address=\"10.0.0.2:1025\" destdomains=\"destdomainX , destDomain1\">"
				+"<auth>"
					+"<username>user1</username>"
				+"</auth>"
			+"</relay></relays>";
	private static final String cfgxml_nodflt = "<relays>"
			+"<relay address=\"10.0.0.2\" destdomains=\"destDomain1\">"
				+"<auth enabled=\"Y\">"
					+"<username>user1</username>"
					+"<password>pass1</password>"
				+"</auth>"
			+"</relay></relays>";
	private static final String cfgxml_interceptor = "<relays>"
			+"<relay address=\"10.0.0.1\"/>"
			+"<relay address=\"10.0.0.2\" destdomains=\"Destdomain1\"/>"
			+"<intercept dns=\"Y\" address=\"localhost:2001\"/>"
			+"</relays>";
	private static final String cfgxml_interceptor_withslave = "<relays>"
			+"<relay address=\"10.0.0.1\"/>"
			+"<intercept address=\"localhost\">"
				+"<ssl/>"
			+"</intercept>"
			+"</relays>";
	private static final String cfgxml_srcroute = "<relays>"
			+"<relay address=\"10.0.0.2\" senders=\"Sender1@srcdomain1,sender2@srcdomAin2,srcdomain1 , sender2@srcdomain2\"/>"
			+"<relay address=\"10.0.0.3\" senders=\"sender1@srcdomain1\"/>"
			+"<relay address=\"10.0.0.4\" senders=\"sender@Srcdomain3\" destdomains=\"deStdomain1\"/>"
			+"</relays>";
	private static final String cfgxml_srcroute_iprules = "<relays>"
			+"<relay address=\"10.0.0.1\"/>"
			+"<relay address=\"10.0.0.5\" senders=\"sender1@srcDomain1\" sendernets=\"192.168/16, localhost\"/>"
			+"</relays>";

	@org.junit.Test
	public void testDestinationRoutes() throws java.io.IOException
	{
		Routing rt = parseRouting(cfgxml_norelays, false, false, false);
		verifyDestinationRoute(rt, "anydomain", false, null, 0);

		rt = parseRouting(cfgxml_smarthost, true, true, false);
		verifyDestinationRoute(rt, "anydomain", false, "10.0.0.1", Protocol.TCP_PORT);

		rt = parseRouting(cfgxml_dfltroute, false, true, false);
		verifyDestinationRoute(rt, "anydomain", false, "10.0.0.1", 25001);
		verifyDestinationRoute(rt, "destdomain1", true, "10.0.0.2", 1025);
		Relay rly = rt.getRoute(new ByteChars("destdomain1"));
		org.junit.Assert.assertFalse(rly.auth_enabled);
		org.junit.Assert.assertNull(rly.usrnam);
		org.junit.Assert.assertNull(rly.senders);

		rt = parseRouting(cfgxml_nodflt, false, false, false);
		verifyDestinationRoute(rt, "anydomain", false, null, 0);
		verifyDestinationRoute(rt, "destdomain1", true, "10.0.0.2", Protocol.TCP_PORT);
		rly = rt.getRoute(new ByteChars("destdomain1"));
		org.junit.Assert.assertTrue(rly.auth_enabled);
		org.junit.Assert.assertEquals("user1", rly.usrnam);
		org.junit.Assert.assertEquals("pass1", rly.passwd.toString());
		org.junit.Assert.assertNull(rly.sslconfig);

		rt = parseRouting(cfgxml_interceptor, false, true, true);
		verifyDestinationRoute(rt, "anydomain", false, "10.0.0.1", Protocol.TCP_PORT);
		verifyDestinationRoute(rt, "destdomain1", true, "10.0.0.2", Protocol.TCP_PORT);
		rly = rt.getInterceptor();
		org.junit.Assert.assertTrue(rly.dns_only);
		org.junit.Assert.assertEquals(IP.IP_LOCALHOST, rly.tsap.ip);
		org.junit.Assert.assertEquals(2001, rly.tsap.port);
		org.junit.Assert.assertNull(rly.senders);

		rt = parseRouting(cfgxml_interceptor_withslave, true, true, true);
		verifyDestinationRoute(rt, "anydomain", false, "10.0.0.1", Protocol.TCP_PORT);
		rly = rt.getInterceptor();
		org.junit.Assert.assertFalse(rly.dns_only);
		org.junit.Assert.assertEquals(IP.IP_LOCALHOST, rly.tsap.ip);
		org.junit.Assert.assertEquals(Protocol.TCP_PORT, rly.tsap.port);
		org.junit.Assert.assertNotNull(rly.sslconfig);
		org.junit.Assert.assertFalse(rly.sslconfig.isLatent());
		org.junit.Assert.assertFalse(rly.sslconfig.isMandatory());
		org.junit.Assert.assertNull(rly.senders);
	}

	@org.junit.Test
	public void testSourceRoutes() throws java.io.IOException
	{
		Routing rt = parseRouting(cfgxml_srcroute, false, false, false);
		// source-address route - only the specific address should match
		EmailAddress emaddr = new EmailAddress("sender2@srcdomain2").decompose();
		Relay rly = rt.getSourceRoute(emaddr, 0);
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.2"), rly.tsap.ip);
		emaddr = new EmailAddress("sender3@srcomain2").decompose();
		rly = rt.getSourceRoute(emaddr, 0);
		org.junit.Assert.assertNull(rly);
		// source-domain route
		emaddr = new EmailAddress("sender9@srcdomain1").decompose();
		rly = rt.getSourceRoute(emaddr, 999);
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.2"), rly.tsap.ip);
		// source-address route that overlaps with a source-domain route takes precedence
		emaddr = new EmailAddress("sender1@srcdomain1").decompose();
		rly = rt.getSourceRoute(emaddr, 0);
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.3"), rly.tsap.ip);
		// test a completely unknown source domain
		emaddr = new EmailAddress("anymailbox@anydomain").decompose();
		rly = rt.getSourceRoute(emaddr, 0);
		org.junit.Assert.assertNull(rly);

		//now test co-existence with a destination route
		emaddr = new EmailAddress("sender@srcdomain3").decompose();
		rly = rt.getSourceRoute(emaddr, 0);
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.4"), rly.tsap.ip);
		Relay rly2 = rt.getRoute(new ByteChars("destdomain1"));
		org.junit.Assert.assertTrue(rly2 == rly);
	}

	@org.junit.Test
	public void testSourceRoutesWithIP() throws java.io.IOException
	{
		Routing rt = parseRouting(cfgxml_srcroute_iprules, false, true, false);
		EmailAddress emaddr = new EmailAddress("sender1@srcdomain1").decompose();
		//control test - make sure source route matches without an IP restriction
		Relay rly = rt.getSourceRoute(emaddr, 0);
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.5"), rly.tsap.ip);

		rly = rt.getSourceRoute(emaddr, IP.convertDottedIP("192.168.100.1"));
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.5"), rly.tsap.ip);
		rly = rt.getSourceRoute(emaddr, IP.convertDottedIP("192.168.0.0"));
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.5"), rly.tsap.ip);
		rly = rt.getSourceRoute(emaddr, IP.convertDottedIP("192.167.255.255"));
		org.junit.Assert.assertNull(rly);

		rly = rt.getSourceRoute(emaddr, IP.IP_LOCALHOST);
		org.junit.Assert.assertEquals(IP.convertDottedIP("10.0.0.5"), rly.tsap.ip);
		rly = rt.getSourceRoute(emaddr, IP.convertDottedIP("127.0.0.0"));
		org.junit.Assert.assertNull(rly);
	}

	private static Routing parseRouting(String cfgxml, boolean slaverelay, boolean havedflt, boolean intercept) throws java.io.IOException {
		NAFConfig nafcfg = NAFConfig.load(new NAFConfig.Defs(NAFConfig.RSVPORT_ANON));
		XmlConfig cfg = XmlConfig.makeSection(cfgxml, "relays");
		Routing rt = new Routing(cfg, nafcfg, null);
		org.junit.Assert.assertEquals(slaverelay, rt.modeSlaveRelay());
		org.junit.Assert.assertEquals(havedflt, rt.haveDefaultRelay());
		org.junit.Assert.assertEquals(intercept, rt.getInterceptor() != null);
		return rt;
	}

	private void verifyDestinationRoute(Routing rt, String destdom, boolean exp_rt, String exp_ip, int exp_port)
	{
		ByteChars destdom_bc = new ByteChars(destdom);
		boolean havert = rt.hasRoute(destdom_bc);
		Relay rly = rt.getRoute(destdom_bc);
		org.junit.Assert.assertEquals(exp_rt, havert);
		if (exp_ip == null) {
			org.junit.Assert.assertNull(rly);
		} else {
			org.junit.Assert.assertEquals(IP.convertDottedIP(exp_ip), rly.tsap.ip);
			org.junit.Assert.assertEquals(exp_port, rly.tsap.port);
			org.junit.Assert.assertNull(rly.senders);
		}
	}
}
/*
 * Copyright 2015-2021 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.smtp;

import com.grey.base.utils.ByteChars;
import com.grey.base.utils.TimeOps;
import com.grey.base.utils.IP;
import com.grey.base.collections.HashedMap;
import com.grey.base.collections.HashedMapIntKey;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.dns.ResolverDNS;
import com.grey.naf.dns.ResourceData;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.dns.PacketDNS;

public class MockServerDNS
	implements com.grey.naf.dns.server.ServerDNS.DNSQuestionResolver
{
	public static final String MXQUERY = "maildomain.net";

	private final HashedMapIntKey<HashedMap<String,ResourceData[][]>> answers = new HashedMapIntKey<>();
	private final com.grey.naf.dns.server.ServerDNS srvr;

	public int getPort() {return srvr.getLocalPort();}
	@Override public boolean dnsRecursionAvailable() {return false;}

	public MockServerDNS(ApplicationContextNAF appctx) throws java.io.IOException {
		populateAnswers();
		com.grey.logging.Logger logger = com.grey.logging.Factory.getLogger("no-such-logger");
		com.grey.naf.DispatcherDef def = new com.grey.naf.DispatcherDef.Builder()
				.withName("Mock-DNS-Server")
				.withSurviveHandlers(false)
				.build();
		Dispatcher dsptch = Dispatcher.create(appctx, def, logger); //pass in null logger to get logging output
		srvr = new com.grey.naf.dns.server.ServerDNS(this, dsptch, "127.0.0.1", 0);
	}

	public void start() throws java.io.IOException {
		srvr.start();
	}

	public void stop() {
		srvr.stop();
		Dispatcher.STOPSTATUS stopsts = srvr.getDispatcher().waitStopped(TimeOps.MSECS_PER_SECOND*10L, true);
		if (stopsts != Dispatcher.STOPSTATUS.STOPPED) throw new IllegalStateException("Failed to stop Server thread - "+stopsts);
	}

	@Override
	public void dnsResolveQuestion(int qid, byte qtype, ByteChars qn, boolean recursion_desired,
		java.net.InetSocketAddress remote_addr, Object cbparam) throws java.io.IOException
	{
		ResourceData[][] answer = null;
		HashedMap<String,ResourceData[][]> map = answers.get(qtype);
		if (map != null) answer = map.get(qn.toString());

		if (answer == null) {
			srvr.sendResponse(qid, qtype, qn, PacketDNS.RCODE_NXDOM, true, recursion_desired, null, null, null,
					remote_addr, cbparam);
		} else {
			ResourceData[] auth = (answer.length > 1 ? answer[1] : null);
			ResourceData[] info = (answer.length > 2 ? answer[2] : null);
			srvr.sendResponse(qid, qtype, qn, PacketDNS.RCODE_OK, true, recursion_desired,
					answer[0], auth, info, remote_addr, cbparam);
		}
	}

	private void store(int rrtype, String name, ResourceData[][] data) {
		HashedMap<String,ResourceData[][]> map = answers.get(rrtype);
		if (map == null) {
			map = new HashedMap<String,ResourceData[][]>();
			answers.put(rrtype, map);
		}
		map.put(name, data);
	}

	private void storeMX(String name, ResourceData[][] data) {store(ResolverDNS.QTYPE_MX, name, data);}

	private static ResourceData rrCreateA(String hostname, String ip, int ttlsecs) {
		return new ResourceData.RR_A(new ByteChars(hostname), ip==null?0:IP.convertDottedIP(ip), ttl2expiry(ttlsecs));
	}

	private static ResourceData rrCreateMX(String domain, String relay, int pref, int ttlsecs) {
		return new ResourceData.RR_MX(new ByteChars(domain), new ByteChars(relay), pref, ttl2expiry(ttlsecs));
	}

	private static long ttl2expiry(int secs) {
		return System.currentTimeMillis()+(secs * 1000L);
	}

	private void populateAnswers()
	{
		storeMX(MXQUERY, new ResourceData[][]{
			{rrCreateMX(MXQUERY, "mailserver1."+MXQUERY, 10, 250),
				rrCreateMX(MXQUERY, "mailserver2."+MXQUERY, 10, 250),
				rrCreateMX(MXQUERY, "mailserver3."+MXQUERY, 10, 250)},
			null,
			{rrCreateA("mailserver1."+MXQUERY, "10.100.200.1", 51000),
				rrCreateA("mailserver2."+MXQUERY, "10.100.200.2", 51000),
				rrCreateA("mailserver3."+MXQUERY, "10.100.200.3", 3300)}});
	}
}
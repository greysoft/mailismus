package com.grey.mailismus.mta.deliver;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.grey.base.config.XmlConfig;
import com.grey.base.utils.IP;
import com.grey.naf.BufferGenerator;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.config.SSLConfig;
import com.grey.mailismus.AppConfig;
import com.grey.mailismus.Transcript;
import com.grey.mailismus.errors.MailismusConfigException;

public class ClientConfiguration {
	private static final String LOG_PREFIX = "SMTP-Client-Config";

	public static Client.SharedFields createSharedFields(XmlConfig xmlcfg,
			Delivery.Controller ctl,
			ResolverDNS dns,
			int max_serverconns) throws IOException, GeneralSecurityException
	{
		Dispatcher dsptch = ctl.getDispatcher();
		AppConfig appConfig = ctl.getAppConfig();

		boolean fallback_mx_a = xmlcfg.getBool("fallbackMX_A", false);
		BufferGenerator bufferGenerator = createBufferGenerator(xmlcfg);
		Transcript transcript = createTranscript(xmlcfg, dsptch);

		// read the per-connection config
		ConnectionConfig defaultConfig = createConnectionConfig(xmlcfg, 0, null, dsptch, appConfig, max_serverconns, fallback_mx_a);
		XmlConfig[] cfgnodes = xmlcfg.getSections("remotenets/remotenet");
		ConnectionConfig[] remotesConfig;
		if (cfgnodes == null) {
			remotesConfig = null;
		} else {
			remotesConfig = new ConnectionConfig[cfgnodes.length];
			for (int idx = 0; idx != cfgnodes.length; idx++) {
				remotesConfig[idx] = createConnectionConfig(cfgnodes[idx], idx+1, defaultConfig, dsptch, appConfig, max_serverconns, fallback_mx_a);
			}
		}
		ctl.getDispatcher().getLogger().info(LOG_PREFIX+": "+bufferGenerator);
		return new Client.SharedFields(ctl, dns, bufferGenerator, transcript, defaultConfig, remotesConfig);
	}

	private static ConnectionConfig createConnectionConfig(XmlConfig xmlcfg,
			int id,
			ConnectionConfig dflts,
			Dispatcher dsptch,
			AppConfig appConfig,
			int max_serverconns,
			boolean fallback_mx_a) throws IOException {

		IP.Subnet[] ipnets = null;
		if (id != 0) {
			ipnets = parseSubnets(xmlcfg, "@ip", appConfig);
			if (ipnets == null) throw new MailismusConfigException(LOG_PREFIX+": ConnectionConfig-"+id+": missing 'ip' attribute");
		}
		String announceHost = appConfig.getAnnounceHost(xmlcfg, dflts==null ? appConfig.getAnnounceHost() : dflts.getAnnouncehost());
		long idleTimeout = xmlcfg.getTime("timeout", dflts==null ? 0 : dflts.getIdleTimeout().toMillis());
		long minRateData = xmlcfg.getSize("mindatarate", dflts==null ? 0 : dflts.getMinRateData());
		long delayChannelClose = xmlcfg.getTime("delay_close", dflts==null ? 0 : dflts.getDelayChannelClose().toMillis());
		boolean sayHELO = xmlcfg.getBool("sayHELO", dflts==null ? false : dflts.isSayHelo());
		boolean fallbackHELO = xmlcfg.getBool("fallbackHELO", dflts==null ? false : dflts.isFallbackHelo());
		boolean sendQUIT = xmlcfg.getBool("sendQUIT", dflts==null ? true : dflts.isSendQuit());
		boolean awaitQUIT = (sendQUIT ? xmlcfg.getBool("waitQUIT", dflts==null ? true : dflts.isAwaitQuit()) : false);

		// ESMTP settings
		int max_pipe = xmlcfg.getInt("maxpipeline", false, dflts==null ? 25 : dflts.getMaxPipeline());
		if (max_pipe == 0) max_pipe = 1;

		SSLConfig anonssl = null;
		XmlConfig sslcfg = xmlcfg.getSection("anonssl");
		if (sslcfg != null && sslcfg.exists()) {
			anonssl = new SSLConfig.Builder()
					.withIsClient(true)
					.withXmlConfig(sslcfg, dsptch.getApplicationContext().getConfig())
					.build();
		}

		ConnectionConfig.Builder bldr = ConnectionConfig.builder()
				.setAnnouncehost(announceHost)
				.setSayHelo(sayHELO)
				.setFallbackHelo(fallbackHELO)
				.setSendQuit(sendQUIT)
				.setAwaitQuit(awaitQUIT)
				.setFallbackMX2A(fallback_mx_a)
				.setMaxServerConnections(max_serverconns)
				.setMaxPipeline(max_pipe)
				.setDelayChannelClose(Duration.ofMillis(delayChannelClose))
				.setAnonSSL(anonssl)
				.setIpNets(ipnets);
		if (idleTimeout != 0) bldr = bldr.setIdleTimeout(Duration.ofMillis(idleTimeout));
		if (minRateData != 0) bldr = bldr.setMinRateData(minRateData);
		ConnectionConfig conncfg = bldr.build();

		dsptch.getLogger().info(LOG_PREFIX+": Node-"+id+"="+conncfg.toString());
		return conncfg;
	}

	private static BufferGenerator createBufferGenerator(XmlConfig xmlcfg) {
		BufferGenerator bufferGenerator = new BufferGenerator(xmlcfg, "niobuffers", 256, 128);
		if (bufferGenerator.rcvbufsiz < 40) throw new MailismusConfigException(LOG_PREFIX+": rcvbuf is too small - "+bufferGenerator);
		return bufferGenerator;
	}

	private static Transcript createTranscript(XmlConfig xmlcfg, Dispatcher dsptch) {
		return Transcript.create(dsptch, xmlcfg, "transcript");
	}

	private static IP.Subnet[] parseSubnets(XmlConfig cfg, String fldnam, AppConfig appConfig) throws UnknownHostException {
		String[] arr = cfg.getTuple(fldnam, "|", false, null);
		if (arr == null) return null;
		List<IP.Subnet> lst = new ArrayList<>();
		for (int idx = 0; idx != arr.length; idx++) {
			String val = arr[idx].trim();
			if (val.isEmpty()) continue;
			if (appConfig != null) val = appConfig.parseHost(null, null, false, val);
			lst.add(IP.parseSubnet(val));
		}
		if (lst.isEmpty()) return null;
		return lst.toArray(new IP.Subnet[0]);
	}
}

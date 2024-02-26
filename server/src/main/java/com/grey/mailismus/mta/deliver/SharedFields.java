/*
 * Copyright 2010-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import com.grey.base.collections.HashedMapIntInt;
import com.grey.base.utils.ByteChars;
import com.grey.base.utils.IP;
import com.grey.base.utils.TSAP;
import com.grey.base.sasl.CramMD5Client;
import com.grey.base.sasl.ExternalClient;
import com.grey.base.sasl.PlainClient;
import com.grey.base.sasl.SaslEntity;
import com.grey.naf.BufferGenerator;
import com.grey.naf.dns.resolver.ResolverDNS;
import com.grey.mailismus.Task;
import com.grey.mailismus.Transcript;
import com.grey.mailismus.mta.Protocol;

class SharedFields {
	private final ConnectionConfig defaultConfig;
	private final List<ConnectionConfig> remotesConfig; //alternative configs for specfic remote destinations

	private final Delivery.Controller controller;
	private final ResolverDNS dnsResolver;
	private final Transcript transcript;
	private final BufferGenerator bufferGenerator;

	private final PlainClient saslPlain = new PlainClient(true);
	private final CramMD5Client saslCramMD5 = new CramMD5Client(true);
	private final ExternalClient saslExternal = new ExternalClient(true);
	private final Map<ByteChars, SaslEntity.MECH> authTypesSupported;

	// read-only buffers - pre-allocated and pre-populated buffers, for efficient sending of canned commands
	private final ByteBuffer smtpRequestData = Task.constBuffer(Protocol.CMDREQ_DATA+Protocol.EOL);
	private final ByteBuffer smtpRequestQuit = Task.constBuffer(Protocol.CMDREQ_QUIT+Protocol.EOL);
	private final ByteBuffer smtpRequestReset = Task.constBuffer(Protocol.CMDREQ_RESET+Protocol.EOL);
	private final ByteBuffer smtpRequestEOM = Task.constBuffer(Protocol.EOM);
	private final ByteBuffer smtpRequestSTLS = Task.constBuffer(Protocol.CMDREQ_STLS+Protocol.EOL);
	private final Map<String,ByteBuffer> smtpRequestHelo = new HashMap<>(); //keyed on announce-host
	private final Map<String,ByteBuffer> smtpRequestEhlo = new HashMap<>(); //keyed on announce-host

	private final HashedMapIntInt activeServerConns = new HashedMapIntInt(); //maps server IP to current connection count

	// temp work areas pre-allocated for efficiency
	private final ByteChars pipelineBuffer = new ByteChars(); //used up in one callback, so safe to share
	private final StringBuilder disconnectMsgBuf = new StringBuilder();
	private final ByteChars failMsgBuffer = new ByteChars();
	private final ByteChars failMsgBuffer2 = new ByteChars();
	private final TSAP tmpTSAP = new TSAP();
	private final byte[] tmpIP = IP.ip2net(0, null, 0);
	private final StringBuilder tmpSB = new StringBuilder();
	private final StringBuilder tmpSB2 = new StringBuilder();
	private final ByteChars tmpBC = new ByteChars();
	private final ByteChars tmpLightBC = new ByteChars(-1); //lightweight object without own storage

	private ByteBuffer tmpNioBuffer; //grows on demand

	public SharedFields(Builder bldr) throws GeneralSecurityException {
		this.controller = bldr.controller;
		this.dnsResolver = bldr.dnsResolver;
		this.bufferGenerator = bldr.bufferGenerator;
		this.transcript = bldr.transcript;
		this.defaultConfig = bldr.defaultConfig;
		this.remotesConfig = Collections.unmodifiableList(bldr.remoteConfigs);

		authTypesSupported = new HashMap<>();
		SaslEntity.MECH[] methods = new SaslEntity.MECH[] {SaslEntity.MECH.PLAIN,
				SaslEntity.MECH.CRAM_MD5, SaslEntity.MECH.EXTERNAL};
		for (int idx = 0; idx != methods.length; idx++) {
			authTypesSupported.put(new ByteChars(methods[idx].toString().replace('_', '-')), methods[idx]);
		}

		List<ConnectionConfig> conncfgs = new ArrayList<>();
		conncfgs.add(defaultConfig);
		conncfgs.addAll(remotesConfig);
		for (ConnectionConfig conncfg : conncfgs) {
			String host = conncfg.getAnnouncehost();
			if (host == null) host = "";
			if (!smtpRequestHelo.containsKey(host)) {
				String announce = (host.isEmpty() ? "" : " " + host);
				ByteBuffer helobuf = Task.constBuffer(Protocol.CMDREQ_HELO+announce+Protocol.EOL);
				ByteBuffer ehlobuf = Task.constBuffer(Protocol.CMDREQ_EHLO+announce+Protocol.EOL);
				smtpRequestHelo.put(host, helobuf);
				smtpRequestEhlo.put(host, ehlobuf);
			}
		}
	}

	public boolean incrementServerConnections(int ip, ConnectionConfig conncfg) {
		int max_serverconns = (conncfg == null ? 0 : conncfg.getMaxServerConnections());
		if (max_serverconns == 0) return true;
		int cnt = activeServerConns.get(ip);
		if (cnt == max_serverconns) return false;
		activeServerConns.put(ip, cnt+1);
		return true;
	}

	public void decrementServerConnections(int ip, ConnectionConfig conncfg) {
		if (conncfg == null || conncfg.getMaxServerConnections() == 0) return;
		if (ip == 0) return;
		int cnt = activeServerConns.get(ip);
		if (--cnt == 0) {
			activeServerConns.remove(ip);
		} else {
			activeServerConns.put(ip, cnt);
		}
	}

	public ByteBuffer getHeloBuffer(ConnectionConfig conncfg, boolean ehlo) {
		String host = conncfg.getAnnouncehost();
		if (host == null) host = "";
		return (ehlo ? smtpRequestEhlo.get(host) : smtpRequestHelo.get(host));
	}

	public ByteBuffer encodeData(ByteChars data) {
		tmpNioBuffer = bufferGenerator.encode(data, tmpNioBuffer);
		return tmpNioBuffer;
	}

	public int getActiveServerConnections() {
		return activeServerConns.size();
	}

	public ConnectionConfig getDefaultConfig() {
		return defaultConfig;
	}

	public List<ConnectionConfig> getRemotesConfig() {
		return remotesConfig;
	}

	public Delivery.Controller getController() {
		return controller;
	}

	public ResolverDNS getDnsResolver() {
		return dnsResolver;
	}

	public Transcript getTranscript() {
		return transcript;
	}

	public BufferGenerator getBufferGenerator() {
		return bufferGenerator;
	}

	public PlainClient getSaslPlain() {
		return saslPlain;
	}

	public CramMD5Client getSaslCramMD5() {
		return saslCramMD5;
	}

	public ExternalClient getSaslExternal() {
		return saslExternal;
	}

	public Map<ByteChars, SaslEntity.MECH> getAuthTypesSupported() {
		return authTypesSupported;
	}

	public ByteBuffer getSmtpRequestData() {
		return smtpRequestData;
	}

	public ByteBuffer getSmtpRequestQuit() {
		return smtpRequestQuit;
	}

	public ByteBuffer getSmtpRequestReset() {
		return smtpRequestReset;
	}

	public ByteBuffer getSmtpRequestEOM() {
		return smtpRequestEOM;
	}

	public ByteBuffer getSmtpRequestSTLS() {
		return smtpRequestSTLS;
	}

	public ByteChars getPipelineBuffer() {
		return pipelineBuffer;
	}

	public StringBuilder getDisconnectMsgBuf() {
		return disconnectMsgBuf;
	}

	public ByteChars getFailMsgBuffer() {
		return failMsgBuffer;
	}

	public ByteChars getFailMsgBuffer2() {
		return failMsgBuffer2;
	}

	public TSAP getTmpTSAP() {
		return tmpTSAP;
	}

	public byte[] getTmpIP() {
		return tmpIP;
	}

	public StringBuilder getTmpSB() {
		return tmpSB;
	}

	public StringBuilder getTmpSB2() {
		return tmpSB2;
	}

	public ByteChars getTmpBC() {
		return tmpBC;
	}

	public ByteChars getTmpLightBC() {
		return tmpLightBC;
	}

	public static Builder builder() {
		return new Builder();
	}


	static class Builder {
		private Delivery.Controller controller;
		private ResolverDNS dnsResolver;
		private BufferGenerator bufferGenerator;
		private Transcript transcript;
		private ConnectionConfig defaultConfig;
		private final List<ConnectionConfig> remoteConfigs = new ArrayList<>();

		private Builder() {
		}

		public Builder withController(Delivery.Controller ctl) {
			this.controller = ctl;
			return this;
		}

		public Builder withDnsResolver(ResolverDNS dns) {
			this.dnsResolver = dns;
			return this;
		}

		public Builder withBufferGenerator(BufferGenerator bufferGenerator) {
			this.bufferGenerator = bufferGenerator;
			return this;
		}

		public Builder withTranscript(Transcript transcript) {
			this.transcript = transcript;
			return this;
		}

		public Builder withDefaultConfig(ConnectionConfig defaultConfig) {
			this.defaultConfig = defaultConfig;
			return this;
		}

		public Builder withRemoteConfig(ConnectionConfig cfg) {
			remoteConfigs.add(cfg);
			return this;
		}

		public Builder withRemoteConfigs(List<ConnectionConfig> cfg) {
			if (cfg != null) remoteConfigs.addAll(cfg);
			return this;
		}

		public Builder withRemoteConfigs(ConnectionConfig[] cfg) {
			if (cfg != null) remoteConfigs.addAll(Arrays.asList(cfg));
			return this;
		}

		public SharedFields build() throws GeneralSecurityException {
			return new SharedFields(this);
		}
	}
}
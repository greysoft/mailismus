/*
 * Copyright 2010-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver;

import java.time.Duration;

import com.grey.base.utils.IP;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.naf.reactor.config.SSLConfig;

/**
 * Config to apply on a per-connection basis, depending who we're talking to
 */
public class ConnectionConfig
{
	private final String announcehost;	//hostname to announce in HELO/EHLO
	private final boolean sayHelo;		//default is to initially say EHLO
	private final boolean fallbackHelo;	//fallback to HELO if EHLO is rejected - the default is not to
	private final boolean sendQuit;
	private final boolean awaitQuit;
	private final boolean fallbackMX2A; //MX queries fall back to simple hostname lookup if no MX RRs exist

	private final int maxServerConnections; //max simultaneous connections to any one server, zero means no limit
	private final int maxPipeline; //max requests that can be pipelined in one send

	private final Duration idleTimeout;
	private final Duration delayChannelClose; //has solved abort-on-close issues in the past
	private final long minRateData; //minimum allowed rate for DATA phase of connection (bits per second)

	private final SSLConfig anonSSL; //controls SSL behaviour with respect to servers other than the configured relays
	private final IP.Subnet[] ipNets; //the remote subnets to which this ConnectionConfig applies, null for the default config

	public ConnectionConfig(Builder bldr) {
		this.announcehost = bldr.announcehost;
		this.sayHelo = bldr.sayHelo;
		this.fallbackHelo = bldr.fallbackHelo;
		this.sendQuit = bldr.sendQuit;
		this.awaitQuit = bldr.awaitQuit;
		this.fallbackMX2A = bldr.fallbackMX2A;
		this.maxServerConnections = bldr.maxServerConnections;
		this.maxPipeline = bldr.maxPipeline;
		this.idleTimeout = bldr.idleTimeout;
		this.delayChannelClose = bldr.delayChannelClose;
		this.minRateData = bldr.minRateData;
		this.anonSSL = bldr.anonSSL;
		this.ipNets = bldr.ipNets;

		if (idleTimeout.toMillis() == 0 || minRateData == 0) {
			throw new MailismusConfigException("Idle timeout and minimum data rate cannot be zero");
		}
	}

	public String getAnnouncehost() {
		return announcehost;
	}

	public boolean isSayHelo() {
		return sayHelo;
	}

	public boolean isFallbackHelo() {
		return fallbackHelo;
	}

	public boolean isSendQuit() {
		return sendQuit;
	}

	public boolean isAwaitQuit() {
		return awaitQuit;
	}

	public boolean isFallbackMX2A() {
		return fallbackMX2A;
	}

	public int getMaxServerConnections() {
		return maxServerConnections;
	}

	public int getMaxPipeline() {
		return maxPipeline;
	}

	public Duration getIdleTimeout() {
		return idleTimeout;
	}

	public Duration getDelayChannelClose() {
		return delayChannelClose;
	}

	public long getMinRateData() {
		return minRateData;
	}

	public SSLConfig getAnonSSL() {
		return anonSSL;
	}

	public IP.Subnet[] getIpNets() {
		return ipNets;
	}

	@Override
	public String toString() {
		StringBuilder sb = null;
		if (ipNets != null) {
			sb = new StringBuilder();
			sb.append(ipNets.length);
			String dlm = "/[";
			for (IP.Subnet net : ipNets) {
				sb.append(dlm).append(net);
				dlm = ", ";
			}
			sb.append(']');
		}
		return getClass().getSimpleName()+"/"+System.identityHashCode(this)+'{'
				+"announce-host="+announcehost
				+", say-HELO="+sayHelo
				+", fallback-HELO="+fallbackHelo
				+", send-QUIT="+sendQuit
				+", await-QUIT="+awaitQuit
				+", fallback-MX-to-A="+fallbackMX2A
				+", max-server-connections="+maxServerConnections
				+", max-pipeline="+maxPipeline
				+", idle-timeout="+idleTimeout
				+", delay-channel-close="+delayChannelClose
				+", min-rate-data="+minRateData
				+", anon-SSL="+anonSSL
				+", ip-nets="+sb
				+'}';
	}

	public static Builder builder() {
		return new Builder();
	}


	public static class Builder {
		private String announcehost;
		private boolean sayHelo;
		private boolean fallbackHelo;
		private boolean sendQuit = true;
		private boolean awaitQuit = true;
		private boolean fallbackMX2A;
		private int maxServerConnections;
		private int maxPipeline = 25;
		private Duration idleTimeout = Duration.ofMinutes(1);
		private Duration delayChannelClose = Duration.ZERO;
		private long minRateData = 1024;
		private SSLConfig anonSSL;
		private IP.Subnet[] ipNets;

		private Builder() {
		}

		public Builder setAnnouncehost(String announcehost) {
			this.announcehost = announcehost;
			return this;
		}

		public Builder setSayHelo(boolean sayHelo) {
			this.sayHelo = sayHelo;
			return this;
		}

		public Builder setFallbackHelo(boolean fallbackHelo) {
			this.fallbackHelo = fallbackHelo;
			return this;
		}

		public Builder setSendQuit(boolean sendQuit) {
			this.sendQuit = sendQuit;
			return this;
		}

		public Builder setAwaitQuit(boolean awaitQuit) {
			this.awaitQuit = awaitQuit;
			return this;
		}

		public Builder setFallbackMX2A(boolean val) {
			this.fallbackMX2A = val;
			return this;
		}

		public Builder setMaxServerConnections(int val) {
			this.maxServerConnections = val;
			return this;
		}

		public Builder setMaxPipeline(int max) {
			this.maxPipeline = max;
			return this;
		}

		public Builder setIdleTimeout(Duration tmt) {
			this.idleTimeout = tmt;
			return this;
		}

		public Builder setDelayChannelClose(Duration delay) {
			this.delayChannelClose = delay;
			return this;
		}

		public Builder setMinRateData(long rate) {
			this.minRateData = rate;
			return this;
		}

		public Builder setAnonSSL(SSLConfig anonSSL) {
			this.anonSSL = anonSSL;
			return this;
		}

		public Builder setIpNets(IP.Subnet[] ipNets) {
			this.ipNets = ipNets;
			return this;
		}

		public ConnectionConfig build() {
			return new ConnectionConfig(this);
		}
	}
}
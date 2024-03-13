/*
 * Copyright 2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.mta.deliver.client;

import com.grey.base.sasl.SaslEntity;
import com.grey.base.utils.TSAP;
import com.grey.naf.reactor.config.SSLConfig;

public class SmtpRelay {
	private final CharSequence name;
	private final TSAP address;
	private final SSLConfig sslConfig;
	private final boolean authRequired;
	private final SaslEntity.MECH authOverride; //override whatever auth methods the relay offers
	private final boolean authCompat; //handle deprecated Protocol.EXT_AUTH_COMPAT responses from this relay
	private final boolean authInitialResponse; //send credentials with AUTH command in single request (for supported methods)
	private final CharSequence username;
	private final CharSequence password;

	public SmtpRelay(Builder<?> bldr) {
		this.name = bldr.name;
		this.address = bldr.address;
		this.sslConfig = bldr.sslConfig;
		this.authRequired = bldr.authRequired;
		this.authOverride = bldr.authOverride;
		this.authCompat = bldr.authCompat;
		this.authInitialResponse = bldr.authInitialResponse;
		this.username = bldr.username;
		this.password = bldr.password;
	}

	public CharSequence getName() {
		return name;
	}

	public TSAP getAddress() {
		return address;
	}

	public SSLConfig getSslConfig() {
		return sslConfig;
	}

	public boolean isAuthRequired() {
		return authRequired;
	}

	public SaslEntity.MECH getAuthOverride() {
		return authOverride;
	}

	public boolean isAuthCompat() {
		return authCompat;
	}

	public boolean isAuthInitialResponse() {
		return authInitialResponse;
	}

	public CharSequence getUsername() {
		return username;
	}

	public CharSequence getPassword() {
		return password;
	}

	public static Builder<?> builder() {
		return new Builder<>();
	}

	@Override
	public String toString() {
		return "SmtpRelay["
				+"name=" + name
				+", address=" + address
				+", authRequired=" + authRequired
				+", authOverride=" + authOverride
				+", authCompat=" + authCompat
				+", authInitialResponse="+ authInitialResponse
				+", username=" + username
				+", sslConfig="+ sslConfig
				+"]";
	}


	public static class Builder<T extends Builder<T>> {
		private CharSequence name;
		private TSAP address;
		private SSLConfig sslConfig;
		private boolean authRequired;
		private SaslEntity.MECH authOverride;
		private boolean authCompat;
		private boolean authInitialResponse;
		private CharSequence username;
		private CharSequence password;

		protected Builder() {}

		public T withName(CharSequence name) {
			this.name = name;
			return self();
		}

		public T withAddress(TSAP address) {
			this.address = address;
			return self();
		}

		public T withSslConfig(SSLConfig sslConfig) {
			this.sslConfig = sslConfig;
			return self();
		}

		public T withAuthRequired(boolean authRequired) {
			this.authRequired = authRequired;
			return self();
		}

		public T withAuthOverride(SaslEntity.MECH authOverride) {
			this.authOverride = authOverride;
			return self();
		}

		public T withAuthCompat(boolean authCompat) {
			this.authCompat = authCompat;
			return self();
		}

		public T withAuthInitialResponse(boolean authInitialResponse) {
			this.authInitialResponse = authInitialResponse;
			return self();
		}

		public T withUsername(CharSequence username) {
			this.username = username;
			return self();
		}

		public T withPassword(CharSequence password) {
			this.password = password;
			return self();
		}

		protected T self() {
			@SuppressWarnings("unchecked") T bldr = (T)this;
			return bldr;
		}

		public SmtpRelay build() {
			return new SmtpRelay(this);
		}
	}
}

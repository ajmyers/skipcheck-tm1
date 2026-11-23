// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * How long the session waits, by kind of work (plan §16.2). TM1 answers some calls only
 * when the work is done, so one timeout for everything is either too short for processes
 * or too long for a login.
 *
 * {@snippet class = examples.Tm1TimeoutsExamples region = long-requests}
 *
 * <p>
 * A builder leaves unset values at the defaults below.
 *
 * @param connect how long to wait for a connection, including TLS. Default 10 s.
 * @param login how long to wait for a login response. Short, because a TM1 that is still
 * loading accepts connections and never answers. Default 30 s.
 * @param request how long a request may go without receiving any data, unless the request
 * sets its own {@linkplain io.skipcheck.tm1.client.Tm1Request#withTimeout timeout}. This
 * is an idle timeout, not a limit on the whole response, so a large collection that keeps
 * streaming isn't cut off. Default 10 min.
 * @param close how long to wait for TM1 to close the session. Default 5 s.
 * @param refusedLogin how long a login TM1 refused (wrong credentials, an unknown or
 * disabled user, impersonation not allowed) is remembered. Until then, requests fail at
 * once with the same error instead of logging in again: every attempt leaves a session
 * behind on TM1, so retrying a refusal at once would pile them up. Zero turns this off.
 * Default 30 s.
 */
public record Tm1Timeouts(Duration connect, Duration login, Duration request, Duration close, Duration refusedLogin) {

	/** The defaults. */
	public static final Tm1Timeouts DEFAULTS = new Tm1Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30),
			Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(30));

	/**
	 * Checks that every timeout is set and positive (or, for {@code refusedLogin}, not
	 * negative).
	 * @throws IllegalArgumentException if a timeout is out of range
	 */
	public Tm1Timeouts {
		positive(connect, "connect");
		positive(login, "login");
		positive(request, "request");
		positive(close, "close");
		Objects.requireNonNull(refusedLogin, "refusedLogin");
		if (refusedLogin.isNegative()) {
			throw new IllegalArgumentException("refusedLogin must not be negative: " + refusedLogin);
		}
	}

	/**
	 * A builder starting from the defaults.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder(DEFAULTS);
	}

	/**
	 * A builder starting from these timeouts, to change some of them.
	 * @return a new builder
	 */
	public Builder toBuilder() {
		return new Builder(this);
	}

	private static void positive(@Nullable Duration value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " timeout must be positive: " + value);
		}
	}

	/**
	 * Builds {@link Tm1Timeouts}. Written by hand rather than with Lombok's
	 * {@code @Builder}, so its methods can carry Javadoc.
	 */
	public static final class Builder {

		private Duration connect;

		private Duration login;

		private Duration request;

		private Duration close;

		private Duration refusedLogin;

		private Builder(Tm1Timeouts start) {
			this.connect = start.connect();
			this.login = start.login();
			this.request = start.request();
			this.close = start.close();
			this.refusedLogin = start.refusedLogin();
		}

		/**
		 * Sets the connect timeout.
		 * @param connect how long to wait for a connection, including TLS
		 * @return this builder
		 */
		public Builder connect(Duration connect) {
			this.connect = Objects.requireNonNull(connect, "connect");
			return this;
		}

		/**
		 * Sets the login timeout.
		 * @param login how long to wait for a login response
		 * @return this builder
		 */
		public Builder login(Duration login) {
			this.login = Objects.requireNonNull(login, "login");
			return this;
		}

		/**
		 * Sets the request idle timeout.
		 * @param request how long a request may go without receiving any data
		 * @return this builder
		 */
		public Builder request(Duration request) {
			this.request = Objects.requireNonNull(request, "request");
			return this;
		}

		/**
		 * Sets the close timeout.
		 * @param close how long to wait for TM1 to close the session
		 * @return this builder
		 */
		public Builder close(Duration close) {
			this.close = Objects.requireNonNull(close, "close");
			return this;
		}

		/**
		 * Sets how long a refused login is remembered.
		 * @param refusedLogin how long requests fail at once after TM1 refused a login;
		 * zero to log in again straight away
		 * @return this builder
		 */
		public Builder refusedLogin(Duration refusedLogin) {
			this.refusedLogin = Objects.requireNonNull(refusedLogin, "refusedLogin");
			return this;
		}

		/**
		 * The timeouts.
		 * @return the timeouts, with unset values at their defaults
		 * @throws IllegalArgumentException if a timeout is zero or negative
		 */
		public Tm1Timeouts build() {
			return new Tm1Timeouts(connect, login, request, close, refusedLogin);
		}

	}

}

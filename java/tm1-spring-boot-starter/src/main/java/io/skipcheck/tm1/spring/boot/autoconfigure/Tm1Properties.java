// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code tm1.*} properties.
 *
 * <pre class="code">
 * tm1:
 *   address: http://tm1:8010        # or base-url: http://tm1:8010/api/v1/
 *   user: admin
 *   password: ${TM1_PASSWORD}
 *   pool:
 *     enabled: true
 * </pre>
 *
 * @param address the server's address, such as {@code http://tm1:8010}; the service root
 * is derived with the TM1 11 dialect. Set this or {@code base-url}.
 * @param baseUrl the REST API's service root itself, such as
 * {@code http://tm1:8010/api/v1/}; takes precedence over {@code address}
 * @param user the user to log in as
 * @param password the password, or none for an empty one
 * @param namespace the CAM namespace, for CAM security (TM1 security mode 5); with it,
 * the client logs in with {@code CAMNamespace} rather than Basic
 * @param cognosUrl the Cognos dispatcher TM1 is pointed at, such as
 * {@code http://cognos:9300}; with it and {@code namespace}, the client logs on to Cognos
 * for a CAM passport at each login, so only Cognos sees the password
 * @param sessionContext the label TM1 shows for the sessions in {@code Sessions} and
 * {@code Threads}; {@code spring.application.name} unless set
 * @param timeouts timeouts by kind of work
 * @param ssl the SSL bundle for HTTPS
 * @param pool the per-identity session pool
 */
@ConfigurationProperties("tm1")
public record Tm1Properties(@Nullable String address, @Nullable String baseUrl, @Nullable String user,
		@Nullable String password, @Nullable String namespace, @Nullable String cognosUrl,
		@Nullable String sessionContext, @DefaultValue Timeouts timeouts, @DefaultValue Ssl ssl,
		@DefaultValue Pool pool) {

	/**
	 * Timeouts by kind of work; unset ones keep the client's defaults (see
	 * {@code Tm1Timeouts}).
	 *
	 * @param connect how long to wait for a connection, including TLS
	 * @param login how long to wait for a login response
	 * @param request how long a request may go without receiving data
	 * @param close how long to wait for TM1 to close a session
	 * @param refusedLogin how long a login TM1 refused is remembered before trying again
	 */
	public record Timeouts(@Nullable Duration connect, @Nullable Duration login, @Nullable Duration request,
			@Nullable Duration close, @Nullable Duration refusedLogin) {
	}

	/**
	 * HTTPS settings.
	 *
	 * @param bundle the name of a Spring Boot SSL bundle ({@code spring.ssl.bundle.*}) to
	 * connect with
	 */
	public record Ssl(@Nullable String bundle) {
	}

	/**
	 * The {@code Tm1SessionPool}, one session per identity, for applications acting on
	 * behalf of many users.
	 *
	 * @param enabled whether to create the pool bean
	 * @param idleTimeout how long a session may go unused before it's evicted
	 * @param maxAge how long a session may live before it's evicted
	 * @param maxSize the most sessions the pool holds
	 * @param closeGrace how long an evicted session stays open for requests still running
	 * @param sweepInterval how often the pool looks for expired sessions
	 */
	public record Pool(@DefaultValue("false") boolean enabled, @DefaultValue("10m") Duration idleTimeout,
			@DefaultValue("8h") Duration maxAge, @DefaultValue("100") int maxSize,
			@DefaultValue("30s") Duration closeGrace, @DefaultValue("1m") Duration sweepInterval) {
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.session.Tm1SessionPool;
import io.skipcheck.tm1.client.session.Tm1Timeouts;

/**
 * Auto-configures the TM1 client for each TM1 server the application names: a
 * {@link Tm1ConnectionDetails}, a {@link Tm1Session}, a {@link Tm1Client} and, with the
 * server's {@code pool.enabled=true}, a {@link Tm1SessionPool}. Sessions and pools are
 * closed on shutdown.
 *
 * <p>
 * Sessions are built on Boot's {@code WebClient.Builder} when there is one (so its
 * customizers, codecs and observations apply) and report to the application's
 * {@link ObservationRegistry}. The connector is Reactor Netty, built with Boot's
 * connector builder so {@code ssl.bundle} works like any other Boot client's SSL bundle.
 *
 * <p>
 * Set the {@link Tm1Properties tm1.* properties} and inject the client:
 *
 * {@snippet class = examples.Tm1AutoConfigurationExamples region = inject}
 *
 * <p>
 * An application acting for many users gets each user's session from the pool:
 *
 * {@snippet class = examples.Tm1AutoConfigurationExamples region = pool}
 *
 * <h2>Servers by name</h2>
 *
 * <p>
 * Every server has a name. Each {@link Tm1ServersProperties tm1.servers.&lt;name&gt;.*}
 * is one, with the same properties as {@code tm1.*}; and {@code tm1.*} is the server
 * named {@code default}, the same as {@code tm1.servers.default.*}. Setting both
 * {@code tm1.*} and {@code tm1.servers.default.*} is an error. Each server's beans are
 * named after it, {@code <name>Tm1ConnectionDetails}, {@code <name>Tm1Session},
 * {@code <name>Tm1Client} and {@code <name>Tm1SessionPool}, and qualified by the name:
 *
 * {@snippet class = examples.Tm1AutoConfigurationExamples region = servers}
 *
 * <p>
 * The default server's beans are the default candidates, so a plain {@code Tm1Client}
 * gets its client. Every other server's are not ({@code defaultCandidate = false}):
 * Spring injects them only where a {@code @Qualifier} names the server. So with only
 * named servers there's no plain {@code Tm1Client}, even when there's one; name it
 * {@code default} for that. Spring Boot's bean conditions skip beans that aren't default
 * candidates, too: a {@code @ConditionalOnBean(Tm1Client.class)} of your own matches the
 * default server's client only.
 *
 * <h2>Beans of your own</h2>
 *
 * <p>
 * A bean of yours under one of a server's bean names takes the place of the starter's,
 * for any server: {@code planningTm1ConnectionDetails}, say, for a login the properties
 * can't describe. The starter gives it the server's qualifier and, for a server other
 * than {@code default}, makes it no default candidate, as its own are. A bean named
 * {@code defaultTm1ConnectionDetails} makes a default server even without {@code tm1.*}.
 *
 * <p>
 * For the default server, a bean of one of these types under any other name also takes
 * the place of the starter's, as long as it's a default candidate, since the default
 * server's beans are the default candidates. That is how Spring Boot's connection details
 * work: {@code @ServiceConnection} registers a {@link Tm1ConnectionDetails} under a name
 * of its own, and it becomes the default server, {@code tm1.*} or not.
 */
@AutoConfiguration(afterName = { "org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration",
		"org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration" })
@ConditionalOnClass(Tm1Session.class)
@Import(Tm1ServersRegistrar.class)
public final class Tm1AutoConfiguration {

	/** How long shutdown waits for a session to close, a login in progress included. */
	static final Duration SHUTDOWN_WAIT = Duration.ofMinutes(1);

	/** Not instantiated by applications; Spring creates it. */
	Tm1AutoConfiguration() {
	}

	/** The session settings the connection details and properties describe. */
	static Tm1Session.Builder sessionSettings(Tm1ConnectionDetails connection, Tm1Properties properties,
			Environment environment, ObjectProvider<WebClient.Builder> webClient,
			ObjectProvider<ObservationRegistry> observations) {
		Tm1Timeouts timeouts = timeouts(properties.timeouts());
		Tm1Session.Builder settings = Tm1Session.builder()
			.dialect(connection.getDialect())
			.baseUrl(connection.getServiceRoot())
			.authenticator(connection.getAuthenticator())
			.timeouts(timeouts)
			.connector(connector(timeouts, connection.getSslBundle()));
		String label = properties.sessionContext() != null ? properties.sessionContext()
				: environment.getProperty("spring.application.name");
		if (label != null && !label.isBlank()) {
			settings.sessionContext(label);
		}
		webClient.ifAvailable(settings::webClient);
		observations.ifAvailable(settings::observationRegistry);
		return settings;
	}

	/** The pool of sessions with {@code settings}, as {@code pool} configures it. */
	static Tm1SessionPool pool(Tm1Session.Builder settings, Tm1Properties.Pool pool) {
		return Tm1SessionPool.builder(settings)
			.idleTimeout(pool.idleTimeout())
			.maxAge(pool.maxAge())
			.maxSize(pool.maxSize())
			.closeGrace(pool.closeGrace())
			.sweepInterval(pool.sweepInterval())
			.build();
	}

	private static Tm1Timeouts timeouts(Tm1Properties.Timeouts configured) {
		Tm1Timeouts.Builder timeouts = Tm1Timeouts.builder();
		if (configured.connect() != null) {
			timeouts.connect(configured.connect());
		}
		if (configured.login() != null) {
			timeouts.login(configured.login());
		}
		if (configured.request() != null) {
			timeouts.request(configured.request());
		}
		if (configured.close() != null) {
			timeouts.close(configured.close());
		}
		if (configured.refusedLogin() != null) {
			timeouts.refusedLogin(configured.refusedLogin());
		}
		return timeouts.build();
	}

	/**
	 * Reactor Netty, as the client requires, through Boot's builder for the SSL bundle
	 * and connect timeout, with the client's own defaults on top: gzip, and the request
	 * idle timeout.
	 */
	private static ClientHttpConnector connector(Tm1Timeouts timeouts, @Nullable SslBundle ssl) {
		HttpClientSettings settings = HttpClientSettings.defaults().withConnectTimeout(timeouts.connect());
		if (ssl != null) {
			settings = settings.withSslBundle(ssl);
		}
		return ClientHttpConnectorBuilder.reactor()
			.withHttpClientCustomizer(http -> http.compress(true).responseTimeout(timeouts.request()))
			.build(settings);
	}

}

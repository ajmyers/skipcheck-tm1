package io.skipcheck.tm1.spring.boot.autoconfigure;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Auto-configures the TM1 client (plan §11): given {@link Tm1ConnectionDetails} (see
 * {@link Tm1ConnectionDetailsAutoConfiguration}), a {@link Tm1Session}, a
 * {@link Tm1Client} and, with {@code tm1.pool.enabled=true}, a {@link Tm1SessionPool}.
 * Each bean backs off if you define your own. Sessions are closed on shutdown.
 *
 * <p>
 * Sessions are built on Boot's {@code WebClient.Builder} when there is one (so its
 * customizers, codecs and observations apply) and report to the application's
 * {@link ObservationRegistry}. The connector is Reactor Netty, built with Boot's
 * connector builder so {@code tm1.ssl.bundle} works like any other Boot client's SSL
 * bundle.
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
 */
@AutoConfiguration(after = Tm1ConnectionDetailsAutoConfiguration.class,
		afterName = "org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration")
@ConditionalOnClass(Tm1Session.class)
@EnableConfigurationProperties(Tm1Properties.class)
@Import(Tm1AutoConfiguration.SessionConfiguration.class)
public final class Tm1AutoConfiguration {

	/** How long shutdown waits for a session to close, a login in progress included. */
	private static final Duration SHUTDOWN_WAIT = Duration.ofMinutes(1);

	/** Not instantiated by applications; Spring creates it. */
	Tm1AutoConfiguration() {
	}

	/** The session, client and pool, once there are connection details. */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(Tm1ConnectionDetails.class)
	static class SessionConfiguration {

		@Bean(destroyMethod = "")
		@ConditionalOnMissingBean
		Tm1Session tm1Session(Tm1ConnectionDetails connection, Tm1Properties properties, Environment environment,
				ObjectProvider<WebClient.Builder> webClient, ObjectProvider<ObservationRegistry> observations) {
			return sessionSettings(connection, properties, environment, webClient, observations).build();
		}

		@Bean
		DisposableBean tm1SessionShutdown(Tm1Session session) {
			return () -> session.close().block(SHUTDOWN_WAIT);
		}

		@Bean
		@ConditionalOnMissingBean
		Tm1Client tm1Client(Tm1Session session) {
			return Tm1Client.of(session);
		}

		@Bean(destroyMethod = "")
		@ConditionalOnMissingBean
		@ConditionalOnProperty(prefix = "tm1.pool", name = "enabled", havingValue = "true")
		Tm1SessionPool tm1SessionPool(Tm1ConnectionDetails connection, Tm1Properties properties,
				Environment environment, ObjectProvider<WebClient.Builder> webClient,
				ObjectProvider<ObservationRegistry> observations) {
			Tm1Properties.Pool pool = properties.pool();
			return Tm1SessionPool.builder(sessionSettings(connection, properties, environment, webClient, observations))
				.idleTimeout(pool.idleTimeout())
				.maxAge(pool.maxAge())
				.maxSize(pool.maxSize())
				.closeGrace(pool.closeGrace())
				.sweepInterval(pool.sweepInterval())
				.build();
		}

		@Bean
		@ConditionalOnBean(Tm1SessionPool.class)
		DisposableBean tm1SessionPoolShutdown(Tm1SessionPool pool) {
			return () -> pool.close().block(SHUTDOWN_WAIT);
		}

	}

	/** The session settings the connection details and properties describe. */
	static Tm1Session.Builder sessionSettings(Tm1ConnectionDetails connection, Tm1Properties properties,
			Environment environment, ObjectProvider<WebClient.Builder> webClient,
			ObjectProvider<ObservationRegistry> observations) {
		Tm1Timeouts timeouts = timeouts(properties.timeouts());
		Tm1Session.Builder settings = Tm1Session.builder()
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
	 * Reactor Netty, as the client requires (plan Q4), through Boot's builder for the SSL
	 * bundle and connect timeout, with the client's own defaults on top: gzip, and the
	 * request idle timeout.
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

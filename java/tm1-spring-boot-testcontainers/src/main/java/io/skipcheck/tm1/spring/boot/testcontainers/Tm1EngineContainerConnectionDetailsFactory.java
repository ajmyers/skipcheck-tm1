// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1Login;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link Tm1ConnectionDetails} from a {@link Tm1EngineContainer} annotated
 * {@code @ServiceConnection}: its database's REST URL,
 * {@link Tm1Dialect#v12(String, String)} dialect, and login — OAuth 2.0 bearer tokens
 * when the container has {@code withOAuth2} and a login for {@code admin}, pass-through
 * credentials when it has {@code withPassThrough}, or the instance's application
 * (service-to-service) credentials otherwise. It connects the starter's default server,
 * the one a plain {@code Tm1Client} gets. Registered in
 * {@code META-INF/spring.factories}, so Spring Boot finds it; applications don't use it
 * directly.
 *
 * <p>
 * A Spring Boot test with a TM1 v12 engine container:
 *
 * {@snippet class = examples.ServiceConnectionExamples region = engine-test}
 */
public final class Tm1EngineContainerConnectionDetailsFactory
		extends ContainerConnectionDetailsFactory<Tm1EngineContainer, Tm1ConnectionDetails> {

	/** Created by Spring Boot, through {@code META-INF/spring.factories}. */
	public Tm1EngineContainerConnectionDetailsFactory() {
	}

	@Override
	protected Tm1ConnectionDetails getContainerConnectionDetails(ContainerConnectionSource<Tm1EngineContainer> source) {
		return new Tm1EngineContainerConnectionDetails(source);
	}

	/**
	 * The database {@code @ServiceConnection} connects to: the default {@code tm1/tm1}
	 * database when present, or the first database the engine holds.
	 */
	static Tm1Database database(Tm1EngineContainer container) {
		return selectDatabase(container.databases());
	}

	static Tm1Database selectDatabase(List<Tm1Database> databases) {
		if (databases.isEmpty()) {
			throw new IllegalStateException("The TM1 v12 engine container has no databases");
		}
		for (Tm1Database db : databases) {
			if (Tm1EngineContainer.DEFAULT_INSTANCE.equals(db.getInstance())
					&& Tm1EngineContainer.DEFAULT_DATABASE.equals(db.getName())) {
				return db;
			}
		}
		return databases.get(0);
	}

	/**
	 * How to log in to {@code database}: OAuth 2.0 bearer tokens from Keycloak when the
	 * engine has {@code withOAuth2} and a password for {@code admin}, the pass-through
	 * TM1 11 server's administrator login when it has {@code withPassThrough}, and the
	 * instance's application (service-to-service) credentials otherwise.
	 */
	static Tm1Authenticator authenticator(Tm1Database database) {
		Tm1EngineContainer engine = database.getEngine();
		if (engine.getKeycloak() != null) {
			try {
				Tm1Login oauth2 = database.oauth2Login("admin");
				return Tm1Authenticator
					.bearer(() -> Mono.fromCallable(oauth2::token).subscribeOn(Schedulers.boundedElastic()));
			}
			catch (IllegalArgumentException ignored) {
				// withOAuth2 was configured without a password for admin; fall back to
				// the instance's application credentials, which still work alongside
				// OAuth
			}
		}
		Tm1ServerContainer passThrough = engine.getPassThrough();
		if (passThrough != null) {
			return Tm1ContainerConnectionDetailsFactory.authenticator(passThrough);
		}
		Tm1Login login = database.login();
		return Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), login.getUser());
	}

	/** The connection a started TM1 v12 engine container offers. */
	static final class Tm1EngineContainerConnectionDetails extends ContainerConnectionDetails<Tm1EngineContainer>
			implements Tm1ConnectionDetails {

		private @Nullable Tm1Database resolved;

		Tm1EngineContainerConnectionDetails(ContainerConnectionSource<Tm1EngineContainer> source) {
			super(source);
		}

		private synchronized Tm1Database resolvedDatabase() {
			if (resolved == null) {
				resolved = Tm1EngineContainerConnectionDetailsFactory.database(getContainer());
			}
			return resolved;
		}

		@Override
		public String getServiceRoot() {
			return resolvedDatabase().getRestUrl();
		}

		@Override
		public Tm1Dialect getDialect() {
			Tm1Database db = resolvedDatabase();
			return Tm1Dialect.v12(db.getInstance(), db.getName());
		}

		@Override
		public Tm1Authenticator getAuthenticator() {
			return authenticator(resolvedDatabase());
		}

		@Override
		public @Nullable SslBundle getSslBundle() {
			return super.getSslBundle();
		}

	}

}

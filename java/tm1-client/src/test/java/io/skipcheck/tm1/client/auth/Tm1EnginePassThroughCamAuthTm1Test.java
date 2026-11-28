// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1AuthenticationException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1ForbiddenException;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.model.UserType;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.RequiresCognos;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;
import reactor.core.publisher.Mono;

/**
 * Pass-through CAM authentication on a TM1 v12 engine backed by a TM1 11 server in Cognos
 * (CAM) security: {@code CAMNamespace} and {@code CAMPassport} (via Cognos). Cognos takes
 * a couple of minutes to start, so the containers are shared across the class.
 */
@RequiresCognos
@RequiresTm1
@RequiresTm1Engine
class Tm1EnginePassThroughCamAuthTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final String ANALYST = "CAMID(\"LDAP:u:uid=analyst,ou=people\")";

	private static CognosContainer cognos;

	private static Tm1ServerContainer server;

	private static Tm1EngineContainer engine;

	private static Tm1Database database;

	@BeforeAll
	@SuppressWarnings("resource") // stopped in stop()
	static void start() {
		cognos = new CognosContainer(CognosSource.discover().orElseThrow()).withUser("admin", "adminpw", "Admins")
			.withUser("analyst", "analystpw", "Analysts")
			.withUser("viewer", "viewerpw", "Viewers");
		server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withCamSecurity(cognos, "admin");
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withUser("analyst", "Analysts")
			.withPassThrough(server);
		engine.start();
		database = engine.database("tm1");
	}

	@AfterAll
	static void stop() {
		try {
			if (engine != null) {
				engine.stop();
			}
		}
		finally {
			try {
				if (server != null) {
					server.stop();
				}
			}
			finally {
				if (cognos != null) {
					cognos.stop();
				}
			}
		}
	}

	private static Tm1Client client(Tm1Authenticator authenticator) {
		return Tm1Client.builder()
			.baseUrl(database.getRestUrl())
			.dialect(Tm1Dialect.v12(database.getInstance(), database.getName()))
			.authenticator(authenticator)
			.build();
	}

	private static Supplier<Mono<String>> counting(Supplier<Mono<String>> passports, AtomicInteger asked) {
		return () -> Mono.defer(() -> {
			asked.incrementAndGet();
			return passports.get();
		});
	}

	@Test
	void logsOnWithCamNamespaceThroughPassThrough() {
		Tm1Client analyst = client(Tm1Authenticator.camNamespace("analyst", "analystpw", "LDAP"));
		try {
			User user = analyst.users().activeUser(ODataQuery.none().expand("Groups")).block(WAIT);
			assertThat(user.name()).isEqualTo(ANALYST);
			assertThat(user.type()).isEqualTo(UserType.USER);
			assertThat(user.groups()).extracting(Group::name).contains("Analysts");
		}
		finally {
			analyst.close().block(WAIT);
		}

		Tm1Client admin = client(Tm1Authenticator.camNamespace("admin", "adminpw", "LDAP"));
		try {
			User user = admin.users().activeUser(ODataQuery.none()).block(WAIT);
			assertThat(user.name()).contains("uid=admin,");
			assertThat(user.type()).isEqualTo(UserType.ADMIN);
		}
		finally {
			admin.close().block(WAIT);
		}
	}

	@Test
	void logsOnWithACognosPassportThroughPassThrough() {
		Tm1Client analyst = client(Tm1Authenticator.cognosLogon(cognos.getUrl(), "LDAP", "analyst", "analystpw"));
		try {
			User user = analyst.users().activeUser(ODataQuery.none().expand("Groups")).block(WAIT);
			assertThat(user.name()).isEqualTo(ANALYST);
			assertThat(user.type()).isEqualTo(UserType.USER);
			assertThat(user.groups()).extracting(Group::name).contains("Analysts");
		}
		finally {
			analyst.close().block(WAIT);
		}
	}

	@Test
	void refusesAWrongPasswordAndACognosUserTheDatabaseLacks() {
		AtomicInteger asked = new AtomicInteger();
		Tm1Client wrongPassport = client(Tm1Authenticator
			.camPassport(counting(CognosPassports.password(cognos.getUrl(), "LDAP", "analyst", "not-it"), asked)));
		try {
			assertThatThrownBy(() -> wrongPassport.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
			assertThatThrownBy(() -> wrongPassport.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class);
			assertThat(asked).as("Cognos asked once: a refusal isn't retried at once").hasValue(1);
		}
		finally {
			wrongPassport.close().onErrorComplete().block(WAIT);
		}

		Tm1Client wrongNamespace = client(Tm1Authenticator.camNamespace("analyst", "not-it", "LDAP"));
		try {
			assertThatThrownBy(() -> wrongNamespace.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
		}
		finally {
			wrongNamespace.close().onErrorComplete().block(WAIT);
		}

		// Cognos and TM1 11 know viewer; the TM1 v12 database has no such user
		Tm1Client viewer = client(Tm1Authenticator.camNamespace("viewer", "viewerpw", "LDAP"));
		try {
			assertThatThrownBy(() -> viewer.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1ForbiddenException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.FORBIDDEN);
		}
		finally {
			viewer.close().onErrorComplete().block(WAIT);
		}
	}

}

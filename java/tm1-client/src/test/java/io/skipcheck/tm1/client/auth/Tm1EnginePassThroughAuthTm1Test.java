// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

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
import io.skipcheck.tm1.client.model.UserCreate;
import io.skipcheck.tm1.client.model.UserType;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1Seed;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Pass-through Basic authentication on a TM1 v12 engine backed by a TM1 11 server: the
 * client sends {@code Authorization: Basic} to the v12 engine, which checks the password
 * with TM1 11 and authorizes the user in its own database.
 */
@SuppressWarnings("deprecation")
@RequiresTm1
@RequiresTm1Engine
class Tm1EnginePassThroughAuthTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1ServerContainer server;

	private static Tm1EngineContainer engine;

	private static Tm1Database database;

	@BeforeAll
	@SuppressWarnings("resource") // stopped in stop()
	static void start() {
		server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withSeed(Tm1Seed.SDATA);
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withDatabase("planning")
			.withUser("analyst", "Analysts")
			.withPassThrough(server);
		engine.start();
		database = engine.database("planning");
		Tm1Client tm11 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		try {
			tm11.users().create(UserCreate.builder().name("analyst").password("analystpw").build()).block(WAIT);
			tm11.users().create(UserCreate.builder().name("stranger").password("strangerpw").build()).block(WAIT);
		}
		finally {
			tm11.close().block(WAIT);
		}
	}

	@AfterAll
	static void stop() {
		try {
			if (engine != null) {
				engine.stop();
			}
		}
		finally {
			if (server != null) {
				server.stop();
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

	@Test
	void logsOnAsAnAdministratorAndAUserThroughPassThrough() {
		Tm1Client admin = client(Tm1Authenticator.basic(server.getUsername(), server.getPassword()));
		try {
			User user = admin.users().activeUser(ODataQuery.none()).block(WAIT);
			assertThat(user.type()).isEqualTo(UserType.ADMIN);
		}
		finally {
			admin.close().block(WAIT);
		}

		Tm1Client analyst = client(Tm1Authenticator.basic("analyst", "analystpw"));
		try {
			User user = analyst.users().activeUser(ODataQuery.none().expand("Groups")).block(WAIT);
			assertThat(user.name()).isEqualToIgnoringCase("analyst");
			assertThat(user.type()).isEqualTo(UserType.USER);
			assertThat(user.groups()).extracting(Group::name).contains("Analysts");
		}
		finally {
			analyst.close().block(WAIT);
		}
	}

	@Test
	void refusesAWrongPasswordAndAUserTheDatabaseLacks() {
		Tm1Client wrongPassword = client(Tm1Authenticator.basic("analyst", "not-it"));
		try {
			assertThatThrownBy(() -> wrongPassword.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1AuthenticationException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
		}
		finally {
			wrongPassword.close().onErrorComplete().block(WAIT);
		}

		// TM1 11 accepts stranger's password; the TM1 v12 database has no such user
		Tm1Client stranger = client(Tm1Authenticator.basic("stranger", "strangerpw"));
		try {
			assertThatThrownBy(() -> stranger.users().activeUser(ODataQuery.none()).block(WAIT))
				.isInstanceOf(Tm1ForbiddenException.class)
				.extracting(e -> ((Tm1ApiException) e).kind())
				.isEqualTo(Tm1ErrorKind.FORBIDDEN);
		}
		finally {
			stranger.close().onErrorComplete().block(WAIT);
		}
	}

}

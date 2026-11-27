// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.examples.Tm1EngineRest;

/**
 * Pass-through logins against real TM1 v12 and TM1 11: an engine of the class's own,
 * asking a TM1 11 server beside it who a name and password are.
 */
@RequiresTm1
@RequiresTm1Engine(shareDatabase = true)
class Tm1EnginePassThroughTest {

	private static Tm1ServerContainer server;

	private static Tm1EngineContainer engine;

	private static Tm1Database planning;

	@BeforeAll
	static void start() {
		server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withSeed(Tm1Seed.SDATA);
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withDatabase("planning")
			.withUser("analyst", "Analysts")
			.withPassThrough(server);
		engine.start();
		planning = engine.database("planning");
		// Two users of the TM1 11 server's own: one the engine has too, and one it lacks
		for (String name : new String[] { "analyst", "stranger" }) {
			var created = Tm1EngineRest.send(HttpRequest.newBuilder(URI.create(server.getRestUrl() + "Users"))
				.header("Authorization", server.getAuthorization())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers
					.ofString("{\"Name\":\"" + name + "\",\"Password\":\"" + name + "pw\"}")));
			assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		}
	}

	@AfterAll
	static void stop() {
		if (engine != null) {
			engine.stop();
		}
		if (server != null) {
			server.stop();
		}
	}

	@Test
	void theServersOwnUserLogsIn() {
		// admin / apple of the sample database, sent to TM1 v12, which keeps no password
		var response = Tm1EngineRest.get(planning, planning.passThroughLogin(), "ActiveUser?$select=Name,Type");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"Type\":\"Admin\"");
	}

	@Test
	void aUserOfBothLogsInAsThemselves() {
		var analyst = planning.passThroughLogin("analyst", "analystpw");
		var response = Tm1EngineRest.get(planning, analyst,
				"ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).containsIgnoringCase("\"Name\":\"analyst\"")
			.contains("\"Type\":\"User\"")
			.contains("Analysts");
	}

	@Test
	void theHeaderLogsInOnAnyRequest() {
		// As a client that logs in itself sends it: TM1 answers, and gives a session
		var analyst = planning.passThroughLogin("analyst", "analystpw");
		var response = Tm1EngineRest
			.send(HttpRequest.newBuilder(URI.create(planning.getRestUrl() + "ActiveUser?$select=Name"))
				.header("Authorization", analyst.getAuthorization()));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.startsWith("TM1SessionId="));
		// It has no token: the addresses are TM1's own, where an application gets one
		assertThat(analyst.getAuthUrl()).isEqualTo(engine.getUrl() + "tm1/auth/v1/");
		assertThat(analyst.getTokenUrl()).isEqualTo(engine.getUrl() + "tm1/auth/v1/token");
		// A header of the caller's own making is passed on the same
		var same = planning.passThroughLoginWith(analyst.getAuthorization());
		assertThat(Tm1EngineRest.get(planning, same, "ActiveUser").statusCode()).isEqualTo(200);
	}

	@Test
	void aWrongPasswordIsRefused() {
		assertThatIllegalStateException().isThrownBy(() -> planning.passThroughLogin("analyst", "not-it").cookie())
			.withMessageContaining("HTTP 401");
	}

	@Test
	void aUserTheDatabaseLacksIsRefused() {
		// The TM1 11 server knows the name and password; the TM1 v12 database has no such
		// user
		assertThatIllegalStateException().isThrownBy(() -> planning.passThroughLogin("stranger", "strangerpw").cookie())
			.withMessageContaining("HTTP 403");
	}

	@Test
	void loginsThroughTheApplicationStillWorkBesidePassThrough() {
		var response = Tm1EngineRest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).containsIgnoringCase("\"Name\":\"analyst\"");
	}

}

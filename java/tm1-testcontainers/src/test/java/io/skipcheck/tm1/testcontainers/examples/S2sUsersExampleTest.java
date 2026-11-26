// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;

/**
 * <b>Users with different rights</b>: an engine of this class's own, with two users
 * besides {@code admin}. TM1 v12 keeps no passwords, so a test logs in as any of them by
 * name, through the engine's application ("service-to-service"). An engine of your own
 * takes about 40 seconds to start.
 */
@RequiresTm1Engine(shareDatabase = true)
class S2sUsersExampleTest {

	private static Tm1EngineContainer engine;

	private static Tm1Database planning;

	@BeforeAll
	static void start() {
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withDatabase("planning")
			.withUser("analyst", "Analysts")
			.withUser("viewer");
		engine.start();
		planning = engine.database("planning");
	}

	@AfterAll
	static void stop() {
		if (engine != null) {
			engine.stop();
		}
	}

	@Test
	void theAdministratorIsAnAdministrator() {
		var response = Tm1EngineRest.get(planning, planning.login(), "ActiveUser?$select=Name,Type");

		assertThat(response.body()).contains("\"Type\":\"Admin\"");
	}

	@Test
	void aUserIsInTheGroupsTheyWereGiven() {
		var response = Tm1EngineRest.get(planning, planning.login("analyst"),
				"ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"Type\":\"User\"").contains("\"Name\":\"Analysts\"");
	}

	@Test
	void aUserWithoutGroupsCanStillLogIn() {
		var response = Tm1EngineRest.get(planning, planning.login("viewer"),
				"ActiveUser?$select=Name&$expand=Groups($select=Name)");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"Groups\":[]");
	}

	@Test
	void aNameTm1DoesntKnowGetsASessionButNoAccess() {
		// The application vouches for any name, so the login itself works
		var nobody = planning.login("nobody");
		assertThat(nobody.cookie()).startsWith("TM1SessionId=");

		assertThat(Tm1EngineRest.get(planning, nobody, "ActiveUser").statusCode()).isEqualTo(403);
	}

}

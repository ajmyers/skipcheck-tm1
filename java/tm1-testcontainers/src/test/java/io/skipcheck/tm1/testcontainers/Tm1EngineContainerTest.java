// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.examples.Tm1EngineRest;

/**
 * The engine against real TM1 v12: its databases and the service-to-service logins. One
 * engine for the class, with two databases in the default instance and one in another.
 */
@RequiresTm1Engine(shareDatabase = true)
class Tm1EngineContainerTest {

	private static Tm1EngineContainer engine;

	private static Tm1Database planning;

	private static Tm1Database sales;

	private static Tm1Database elsewhere;

	@BeforeAll
	static void start() {
		engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withDatabase("planning")
			.withDatabase("sales")
			.withDatabase("other", "elsewhere")
			.withUser("analyst", "Analysts");
		engine.start();
		planning = engine.database("planning");
		sales = engine.database("sales");
		elsewhere = engine.database("other", "elsewhere");
	}

	@AfterAll
	static void stop() {
		if (engine != null) {
			engine.stop();
		}
	}

	@Test
	void startsWithTheDatabasesItWasGiven() {
		assertThat(engine.databases()).containsExactlyInAnyOrder(planning, sales, elsewhere);
		assertThat(planning.getRestUrl()).isEqualTo(engine.getUrl() + "tm1/api/v1/Databases('planning')/");
		assertThat(elsewhere.getRestUrl()).isEqualTo(engine.getUrl() + "other/api/v1/Databases('elsewhere')/");
		assertThat(engine.getTm1Version().isAtLeast(Tm1Version.parse("12"))).isTrue();
		assertThatIllegalArgumentException().isThrownBy(() -> engine.database("missing"))
			.withMessageContaining("No database tm1/missing in this engine");
	}

	@Test
	void logsInWithTheApplicationsCredentialsAsBasic() {
		var admin = planning.login();
		assertThat(admin.cookie()).startsWith("TM1SessionId=");
		// The one session serves every database of the instance
		for (Tm1Database database : new Tm1Database[] { planning, sales }) {
			var response = Tm1EngineRest.get(database, admin, "ActiveUser?$select=Name,Type");
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"Type\":\"Admin\"");
		}
	}

	@Test
	void logsInWithABearerToken() {
		var analyst = planning.login("analyst");
		String token = analyst.token();
		assertThat(token.split("\\.")).hasSize(3);

		String cookie = analyst.session("Bearer " + token);
		var response = Tm1EngineRest
			.send(HttpRequest.newBuilder(URI.create(planning.getRestUrl() + "ActiveUser?$select=Name"))
				.header("Cookie", cookie));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).containsIgnoringCase("\"Name\":\"analyst\"");
	}

	@Test
	void aUserTm1DoesntKnowGetsASessionAndIsThenRefused() {
		var nobody = planning.login("nobody");
		assertThat(nobody.cookie()).startsWith("TM1SessionId=");
		assertThat(Tm1EngineRest.get(planning, nobody, "ActiveUser").statusCode()).isEqualTo(403);
	}

	@Test
	void aUserMissingFromOneDatabaseIsRefusedThereOnly() {
		// Users belong to a database: take the analyst out of one
		assertThat(Tm1EngineRest.delete(sales, sales.login(), "Users('analyst')").statusCode()).isEqualTo(204);

		var analyst = planning.login("analyst");
		assertThat(Tm1EngineRest.get(planning, analyst, "ActiveUser").statusCode()).isEqualTo(200);
		assertThat(Tm1EngineRest.get(sales, analyst, "ActiveUser").statusCode()).isEqualTo(403);
	}

	@Test
	void anotherInstancesCredentialsAreRefused() {
		// An application belongs to its instance: the other's can't log in here
		var other = elsewhere.login();
		assertThat(Tm1EngineRest.get(elsewhere, other, "ActiveUser").statusCode()).isEqualTo(200);

		String basic = Base64.getEncoder()
			.encodeToString((other.getClientId() + ":" + other.getClientSecret()).getBytes(StandardCharsets.UTF_8));
		var response = Tm1EngineRest.send(HttpRequest.newBuilder(URI.create(planning.login().getAuthUrl() + "session"))
			.header("Authorization", "Basic " + basic)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"User\":\"admin\"}")));
		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void createsAndDropsADatabase() {
		long started = System.nanoTime();
		Tm1Database made = engine.createDatabase("scratch");
		long created = System.nanoTime();
		assertThat(engine.databases()).contains(made);
		// With the engine's users, like the databases it started with
		assertThat(Tm1EngineRest.get(made, made.login("analyst"), "ActiveUser").statusCode()).isEqualTo(200);

		long before = System.nanoTime();
		engine.dropDatabase(made);
		long dropped = System.nanoTime();
		assertThat(engine.databases()).doesNotContain(made);
		System.out.printf("Created a database in %.1f s and dropped it in %.1f s%n", (created - started) / 1e9,
				(dropped - before) / 1e9);
	}

	@Test
	void givesTheRootCredentialsForTheManagementApi() {
		String basic = Base64.getEncoder()
			.encodeToString(
					(engine.getRootClientId() + ":" + engine.getRootClientSecret()).getBytes(StandardCharsets.UTF_8));
		var response = Tm1EngineRest.send(HttpRequest.newBuilder(URI.create(engine.getManagementUrl() + "Instances"))
			.header("Authorization", "Basic " + basic));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"tm1\"").contains("\"other\"");
	}

}

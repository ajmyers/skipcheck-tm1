// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1Login;

/**
 * Examples for {@link RequiresTm1Engine} and {@link Tm1EngineContainer}, quoted in their
 * Javadoc: compiled but never run.
 */
final class Tm1EngineExamples {

	// @start region = test-class
	@RequiresTm1Engine
	static class CubeTest {

		// A database of this class's own, dropped when the class is done
		@Test
		void listsCubes(Tm1Database db) throws Exception {
			HttpRequest request = db.login()
				.authorize(HttpRequest.newBuilder(URI.create(db.getRestUrl() + "Cubes?$select=Name")))
				.build();
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, response.statusCode());
		}

	}
	// @end

	// @start region = shared-database
	// The engine's one shared database instead: nothing to create, but give what you
	// make a unique name
	@RequiresTm1Engine(shareDatabase = true)
	static class ServerNameTest {

		@Test
		void readsTheServersName(Tm1Database db) throws Exception {
			HttpRequest request = db.login()
				.authorize(HttpRequest.newBuilder(URI.create(db.getRestUrl() + "Configuration/ServerName/$value")))
				.build();
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, response.statusCode());
		}

	}
	// @end

	void container(Tm1EngineSource source) {
		// @start region = container
		try (var engine = new Tm1EngineContainer(source).withDatabase("planning").withUser("analyst", "Analysts")) {
			engine.start();
			Tm1Database planning = engine.database("planning");
			// http://localhost:54321/tm1/api/v1/Databases('planning')/
			String url = planning.getRestUrl();
			Tm1Login analyst = planning.login("analyst");
			// ...
			// @end
			assertEquals("analyst", analyst.getUser(), url);
		}
	}

}

// SPDX-License-Identifier: Apache-2.0

package examples;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.PlanningAnalyticsEnvironment;
import io.skipcheck.tm1.testcontainers.Tm1Seed;

/**
 * Examples for {@link PlanningAnalyticsEnvironment}, quoted in its Javadoc. Test code, so
 * it may block.
 */
final class PlanningAnalyticsEnvironmentExamples {

	private static final Duration WAIT = Duration.ofSeconds(30);

	Map<String, List<String>> servers() {
		// @start region = servers
		// Two servers on one Admin Server, and TM1 Web listing both
		try (var env = PlanningAnalyticsEnvironment.builder()
			.tm1("planning", tm1 -> tm1.withSeed(Tm1Seed.SDATA))
			.tm1("reporting")
			.tm1Web()
			.build()) {
			env.start(); // TM1 Web at env.tm1Web().getUrl(), log on with getAdminHost()

			Map<String, List<String>> cubes = new LinkedHashMap<>();
			env.tm1Servers().forEach((name, server) -> {
				Tm1Client tm1 = Tm1Client.builder()
					.baseUrl(server.getRestUrl())
					.basicAuth(server.getUsername(), server.getPassword())
					.build();
				cubes.put(name,
						tm1.cubes()
							.modelCubes(ODataQuery.none().select(Cube.NAME))
							.map(Cube::name)
							.collectList()
							.block(WAIT));
				tm1.close().block(WAIT);
			});
			return cubes; // planning: SData's cubes; reporting: none
		}
		// @end
	}

	List<String> camSecurity() {
		// @start region = cam-security
		// Every server in CAM security, with Cognos's test users as its users
		try (var env = PlanningAnalyticsEnvironment.builder()
			.tm1("planning")
			.cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
			.build()) {
			env.start();

			Tm1Client viewer = Tm1Client.builder()
				.baseUrl(env.tm1("planning").getRestUrl())
				.authenticator(Tm1Authenticator.camNamespace("viewer", "viewerpw", CognosContainer.NAMESPACE))
				.build();
			try {
				// The cubes the viewer's groups may see
				return viewer.cubes()
					.modelCubes(ODataQuery.none().select(Cube.NAME))
					.map(Cube::name)
					.collectList()
					.block(WAIT);
			}
			finally {
				viewer.close().block(WAIT);
			}
		}
		// @end
	}

	private PlanningAnalyticsEnvironmentExamples() {
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.PlanningAnalyticsEnvironment;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;

/**
 * <b>TM1 11 and TM1 v12 in one environment</b>: {@link PlanningAnalyticsEnvironment}
 * starts a TM1 server and a TM1 v12 engine on one network, for tests of both versions, or
 * of moving from one to the other. It needs a source for each.
 */
class Tm1EngineEnvironmentExampleTest {

	@Test
	@RequiresTm1
	@RequiresTm1Engine(shareDatabase = true)
	void aServerAndAnEngineSideBySide() {
		try (var env = PlanningAnalyticsEnvironment.builder()
			.tm1("legacy")
			.tm1Engine(engine -> engine.withDatabase("planning").withUser("analyst", "Analysts"))
			.build()) {
			env.start();

			try (var legacy = new Tm1Rest(env.tm1("legacy"))) {
				assertThat(legacy.modelCubeCount()).isZero();
			}
			Tm1Database planning = env.tm1Database("planning");
			var response = Tm1EngineRest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name");
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).containsIgnoringCase("\"Name\":\"analyst\"");
		}
	}

}

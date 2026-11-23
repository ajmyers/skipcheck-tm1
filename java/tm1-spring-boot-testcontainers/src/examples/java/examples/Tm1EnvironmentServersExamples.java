// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.spring.boot.testcontainers.Tm1EnvironmentServers;
import io.skipcheck.tm1.testcontainers.PlanningAnalyticsEnvironment;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1Seed;

/**
 * Examples for {@link Tm1EnvironmentServers}, quoted in its Javadoc: a Spring Boot test,
 * compiled but never run.
 */
final class Tm1EnvironmentServersExamples {

	@SpringBootApplication
	static class ReportingApplication {

		public static void main(String[] args) {
			SpringApplication.run(ReportingApplication.class, args);
		}

	}

	// @start region = test
	// Two servers, as tm1.servers.planning and tm1.servers.reporting
	@RequiresTm1
	@SpringBootTest(classes = ReportingApplication.class)
	static class ReportingApplicationTest {

		private static PlanningAnalyticsEnvironment environment;

		@DynamicPropertySource
		static void tm1(DynamicPropertyRegistry registry) {
			environment = PlanningAnalyticsEnvironment.builder()
				.tm1("planning", tm1 -> tm1.withSeed(Tm1Seed.SDATA))
				.tm1("reporting")
				.build();
			environment.start();
			Tm1EnvironmentServers.register(environment, registry);
		}

		@AfterAll
		static void stop() {
			environment.close();
		}

		@Autowired
		@Qualifier("planning")
		private Tm1Client planning;

		@Autowired
		@Qualifier("reporting")
		private Tm1Client reporting;

		@Test
		void connectsToBoth() {
			assertNotEquals(planning.session().serviceRoot(), reporting.session().serviceRoot());
		}

	}
	// @end

	private Tm1EnvironmentServersExamples() {
	}

}

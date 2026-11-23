// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import io.skipcheck.tm1.testcontainers.PlanningAnalyticsEnvironment;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * A {@code @DynamicPropertySource} method adds the names in time for the starter's named
 * beans. Nothing is started: the URLs stand in for the containers', which only a started
 * container has, and no request is made.
 */
@SpringBootTest(classes = Tm1EnvironmentServersSpringTest.App.class)
class Tm1EnvironmentServersSpringTest {

	@Autowired
	@Qualifier("planning")
	private Tm1Client planning;

	// The server named default: the plain injection
	@Autowired
	private Tm1ConnectionDetails reporting;

	@DynamicPropertySource
	static void tm1(DynamicPropertyRegistry registry) {
		var environment = PlanningAnalyticsEnvironment.builder()
			.tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
			.tm1("planning")
			.tm1("default", tm1 -> tm1.withCredentials("reporter", "pw"))
			.build();
		Tm1EnvironmentServers.register(environment,
				(name, value) -> registry.add(name, name.endsWith(".base-url")
						? () -> "http://" + name.replace("tm1.servers.", "").replace(".base-url", "") + ":8010/api/v1/"
						: value));
	}

	@Test
	void injectsEachServerOfTheEnvironmentByName() {
		assertThat(planning.session().serviceRoot()).isEqualTo("http://planning:8010/api/v1/");
		assertThat(reporting.getAuthenticator().describe()).isEqualTo("Basic reporter");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

	}

}

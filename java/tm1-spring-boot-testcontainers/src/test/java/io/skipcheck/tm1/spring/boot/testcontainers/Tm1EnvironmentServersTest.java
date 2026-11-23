// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.PlanningAnalyticsEnvironment;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * The properties an environment's servers become. Nothing is started, so only the names
 * and the login are read; the URL needs a started container.
 */
class Tm1EnvironmentServersTest {

	private final Map<String, Supplier<Object>> properties = new LinkedHashMap<>();

	@Test
	void addsEachServerByItsName() {
		var environment = PlanningAnalyticsEnvironment.builder()
			.tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
			.tm1("planning", tm1 -> tm1.withCredentials("admin", "apple"))
			.tm1("reporting")
			.build();

		Tm1EnvironmentServers.register(environment, properties::put);

		assertThat(properties).containsOnlyKeys("tm1.servers.planning.base-url", "tm1.servers.planning.user",
				"tm1.servers.planning.password", "tm1.servers.reporting.base-url", "tm1.servers.reporting.user",
				"tm1.servers.reporting.password");
		assertThat(properties.get("tm1.servers.planning.user").get()).isEqualTo("admin");
		assertThat(properties.get("tm1.servers.planning.password").get()).isEqualTo("apple");
		assertThat(properties.get("tm1.servers.reporting.password").get()).isEqualTo("");
	}

	@Test
	void logsInThroughTheNamespaceInCamSecurity() {
		var environment = PlanningAnalyticsEnvironment.builder()
			.tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
			.cognosSource(new CognosSource.PrivateImage("cognos-server:test"))
			.tm1("planning")
			.cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins"))
			.build();

		Tm1EnvironmentServers.register(environment, properties::put);

		assertThat(properties.get("tm1.servers.planning.namespace").get()).isEqualTo("LDAP");
	}

}

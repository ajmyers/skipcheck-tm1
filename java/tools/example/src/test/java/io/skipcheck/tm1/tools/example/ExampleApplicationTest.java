// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.tools.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.skipcheck.tm1.client.Tm1Client;

/**
 * Starts the application as its main method does: the starter is found by Spring Boot's
 * own discovery of auto-configurations, which the starter's tests, naming its
 * configuration classes, don't go through. No TM1 is needed: the client connects on its
 * first request.
 */
class ExampleApplicationTest {

	@Test
	void startsWithoutATm1AndHasNoClient() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ExampleApplication.class).run()) {
			assertThat(context.getBeanNamesForType(Tm1Client.class)).isEmpty();
		}
	}

	@Test
	void theStarterGivesItAClientOnceATm1IsConfigured() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ExampleApplication.class)
			.properties("tm1.address=http://tm1:8010", "tm1.user=admin")
			.run()) {
			assertThat(context.getBean(Tm1Client.class).session().serviceRoot()).isEqualTo("http://tm1:8010/api/v1/");
		}
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * A Spring Boot test that gets its TM1 from a container through
 * {@code @ServiceConnection}, with no {@code tm1.*} properties at all.
 */
@RequiresTm1
@SpringBootTest(classes = Tm1ServiceConnectionTest.App.class)
class Tm1ServiceConnectionTest {

	@Autowired
	private Tm1Client client;

	@Autowired
	private Tm1ConnectionDetails connection;

	@Test
	void connectsToTheContainersTm1() {
		Tm1ServerContainer tm1 = Tm1ServerContainer.shared();
		assertThat(connection.getServiceRoot()).isEqualTo(tm1.getRestUrl());
		assertThat(client.session().version().block(Duration.ofSeconds(30))).isEqualTo(tm1.getTm1Version());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		/**
		 * The shared container, which outlives this context, so Spring mustn't stop it.
		 */
		@Bean(destroyMethod = "")
		@ServiceConnection
		Tm1ServerContainer tm1() {
			return Tm1ServerContainer.shared();
		}

	}

}

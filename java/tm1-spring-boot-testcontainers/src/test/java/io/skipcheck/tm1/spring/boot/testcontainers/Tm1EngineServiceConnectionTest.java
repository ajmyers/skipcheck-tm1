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
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Dialect;
import io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;

/**
 * A Spring Boot test that gets its TM1 v12 database from a {@link Tm1EngineContainer}
 * through {@code @ServiceConnection}, with no {@code tm1.*} properties at all.
 */
@RequiresTm1Engine
@SpringBootTest(classes = Tm1EngineServiceConnectionTest.App.class)
class Tm1EngineServiceConnectionTest {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Autowired
	private Tm1EngineContainer engine;

	@Autowired
	private Tm1Client client;

	@Autowired
	private Tm1ConnectionDetails connection;

	@Test
	void connectsToTheEnginesTm1Database() {
		Tm1Database db = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);
		assertThat(connection.getServiceRoot()).isEqualTo(db.getRestUrl());
		assertThat(connection.getDialect()).isEqualTo(Tm1Dialect.v12(db.getInstance(), db.getName()));
		assertThat(connection.getAuthenticator().describe()).startsWith("Application ");
		assertThat(client.session().version().block(WAIT)).isEqualTo(engine.getTm1Version());
		assertThat(client.users().activeUser(ODataQuery.none()).block(WAIT).name()).isEqualToIgnoringCase("admin");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		@Bean
		@ServiceConnection
		Tm1EngineContainer tm1() {
			return new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow());
		}

	}

}

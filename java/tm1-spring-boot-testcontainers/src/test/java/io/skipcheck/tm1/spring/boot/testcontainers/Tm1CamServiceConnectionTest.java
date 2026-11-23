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
import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.RequiresCognos;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * {@code @ServiceConnection} to a TM1 in Cognos security: the client logs in through CAM,
 * as the container's own checks do. Cognos takes a couple of minutes to start.
 */
@RequiresCognos
@SpringBootTest(classes = Tm1CamServiceConnectionTest.App.class)
class Tm1CamServiceConnectionTest {

	@Autowired
	private Tm1Client client;

	@Test
	void logsInThroughCam() {
		// TM1 knows CAM users by CAMID
		assertThat(client.users().activeUser(ODataQuery.none()).block(Duration.ofSeconds(60)).name())
			.startsWith("CAMID(\"LDAP:u:")
			.contains("admin");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		@Bean
		CognosContainer cognos() {
			return new CognosContainer(CognosSource.discover().orElseThrow()).withUser("admin", "adminpw", "Admins");
		}

		@Bean
		@ServiceConnection
		Tm1ServerContainer tm1(CognosContainer cognos) {
			return new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withCamSecurity(cognos, "admin");
		}

	}

}

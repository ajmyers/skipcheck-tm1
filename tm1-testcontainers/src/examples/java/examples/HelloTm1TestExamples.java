package examples;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * The Getting started example on tm1-testcontainers' overview page: a JUnit test,
 * compiled but never run.
 */
final class HelloTm1TestExamples {

	// @start region = hello-test
	@RequiresTm1
	static class HelloTm1Test {

		@Test
		void connects(Tm1ServerContainer server) {
			Tm1Client tm1 = Tm1Client.builder()
				.baseUrl(server.getRestUrl())
				.basicAuth(server.getUsername(), server.getPassword())
				.build();
			try {
				assertNotNull(tm1.session().version().block(Duration.ofSeconds(30)));
			}
			finally {
				tm1.close().block(Duration.ofSeconds(30));
			}
		}

	}
	// @end

	private HelloTm1TestExamples() {
	}

}

// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Runs the quick start's example (HelloWorld) against a real TM1, and records what it
 * prints for the landing page, which shows it under the code ({@link ExampleOutput}). Run
 * it after changing the example, and commit the recording in api-site/example-output with
 * it.
 *
 * <p>
 * The example logs in as a new tm1-server does (admin, no password), so this starts a
 * server of its own with the empty database, rather than use the shared server, which
 * other tests fill. It has no cubes, so the example prints the version alone.
 */
@RequiresTm1
class HelloWorldTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void printsTheVersionAndTheCubes() {
		try (Tm1ServerContainer server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow());
				ExampleOutput output = new ExampleOutput("tm1-client", "HelloWorld")) {
			server.start();
			Tm1Client tm1 = Tm1Client.builder()
				.baseUrl(server.getRestUrl())
				.basicAuth(server.getUsername(), server.getPassword())
				.build();
			try {
				output.record("hello-world", () -> HelloWorld.hello(tm1).block(WAIT));
			}
			finally {
				tm1.close().block(WAIT);
			}
			assertThat(output.output("hello-world")).containsExactly("Hello from TM1 " + server.getTm1Version());
		}
	}

}

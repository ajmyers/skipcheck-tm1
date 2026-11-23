package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * <b>Without {@code @RequiresTm1}</b>: plain JUnit assumptions and try-with-resources.
 *
 * <p>
 * Use this when you want full control of the container's lifetime, or when you're not
 * using JUnit Jupiter extensions (other test frameworks, or code outside tests, such as a
 * local dev tool). It's also the pattern for a test that <b>damages its server</b>, such
 * as changing server configuration or testing a shutdown: give it a fresh server of its
 * own, thrown away at the end, so it can't affect other tests.
 */
class WithoutTheExtensionExampleTest {

	@Test
	void startsAThrowawayServer() {
		assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "needs Docker");
		Optional<Tm1Source> source = Tm1Source.discover();
		assumeTrue(source.isPresent(), "needs a TM1 source");

		try (var tm1 = new Tm1ServerContainer(source.get())) {
			tm1.start();
			try (var rest = new Tm1Rest(tm1)) {
				assertThat(rest.get("Configuration/ProductVersion/$value")).isEqualTo(tm1.getTm1Version().toString());
			}
		} // the container, and anything the test did to it, is removed here
	}

}

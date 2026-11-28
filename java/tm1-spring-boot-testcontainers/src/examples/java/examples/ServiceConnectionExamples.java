// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1Seed;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Examples for {@code @ServiceConnection} on a {@link Tm1ServerContainer} or
 * {@link Tm1EngineContainer}, quoted in the Javadoc of
 * {@link io.skipcheck.tm1.spring.boot.testcontainers}: Spring Boot tests and a
 * development-time application, compiled but never run.
 */
final class ServiceConnectionExamples {

	@SpringBootApplication
	static class SalesApplication {

		public static void main(String[] args) {
			SpringApplication.run(SalesApplication.class, args);
		}

	}

	// @start region = test
	// Skipped without Docker or TM1, like any @RequiresTm1 test
	@RequiresTm1
	@SpringBootTest(classes = { SalesApplication.class, SalesApplicationTest.Tm1Container.class })
	static class SalesApplicationTest {

		@Autowired
		private Tm1Client tm1;

		@Test
		void connectsToTm1() {
			assertNotNull(tm1.session().version().block(Duration.ofSeconds(30)));
		}

		@TestConfiguration(proxyBeanMethods = false)
		static class Tm1Container {

			// The shared server outlives the Spring context, so Spring mustn't stop it
			@Bean(destroyMethod = "")
			@ServiceConnection
			Tm1ServerContainer tm1() {
				return Tm1ServerContainer.shared();
			}

		}

	}
	// @end

	// @start region = dev-app
	// Run the application locally against TM1 in Docker, with IBM's SData sample
	static class TestSalesApplication {

		public static void main(String[] args) {
			SpringApplication.from(SalesApplication::main).with(Tm1Container.class).run(args);
		}

		@TestConfiguration(proxyBeanMethods = false)
		static class Tm1Container {

			@Bean
			@ServiceConnection
			Tm1ServerContainer tm1() {
				return new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withSeed(Tm1Seed.SDATA);
			}

		}

	}
	// @end

	// @start region = engine-test
	// Skipped without Docker or a TM1 v12 source, like any @RequiresTm1Engine test
	@RequiresTm1Engine
	@SpringBootTest(classes = { SalesApplication.class, SalesEngineApplicationTest.Tm1Container.class })
	static class SalesEngineApplicationTest {

		@Autowired
		private Tm1Client tm1;

		@Test
		void connectsToTm1Engine() {
			assertNotNull(tm1.session().version().block(Duration.ofSeconds(30)));
		}

		@TestConfiguration(proxyBeanMethods = false)
		static class Tm1Container {

			@Bean
			@ServiceConnection
			Tm1EngineContainer tm1() {
				return new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow());
			}

		}

	}
	// @end

	private ServiceConnectionExamples() {
	}

}

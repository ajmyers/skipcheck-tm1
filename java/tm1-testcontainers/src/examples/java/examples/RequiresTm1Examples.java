package examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * Examples for {@link RequiresTm1}, quoted in its Javadoc: JUnit test classes, compiled
 * but never run.
 */
final class RequiresTm1Examples {

	// @start region = test-class
	@RequiresTm1
	static class RegionDimensionTest {

		private static final Duration WAIT = Duration.ofSeconds(30);

		private static Tm1Client tm1;

		// One client per class, on the shared server
		@BeforeAll
		static void connect(Tm1ServerContainer server) {
			tm1 = Tm1Client.builder()
				.baseUrl(server.getRestUrl())
				.basicAuth(server.getUsername(), server.getPassword())
				.build();
		}

		@AfterAll
		static void close() {
			tm1.close().block(WAIT);
		}

		// The server is shared, so use a unique name and delete it afterwards
		private final String region = "Region-" + UUID.randomUUID();

		@AfterEach
		void deleteRegion() {
			tm1.dimensions().delete(region).onErrorComplete().block(WAIT);
		}

		@Test
		void createsTheRegions() {
			Dimension created = tm1.dimensions()
				.create(DimensionPatch.builder()
					.name(region)
					.hierarchies(List.of(HierarchyPatch.builder()
						.name(region)
						.elements(List.of(ElementPatch.builder().name("North").type(ElementType.NUMERIC).build(),
								ElementPatch.builder().name("South").type(ElementType.NUMERIC).build()))
						.build()))
					.build())
				.block(WAIT);
			assertEquals(region, created.name());
		}

	}
	// @end

	// @start region = by-version
	@RequiresTm1
	static class NewFeatureTest {

		@Test
		void needsARecentServer(Tm1ServerContainer tm1) {
			// Skips, rather than fails, on servers older than the feature
			Tm1Version introducedIn = Tm1Version.parse("11.8.03000");
			assumeTrue(tm1.getTm1Version().isAtLeast(introducedIn), "needs TM1 " + introducedIn + " or newer");
			// ...
		}

	}
	// @end

	private RequiresTm1Examples() {
	}

}

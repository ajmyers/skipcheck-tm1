package net.ajmyers.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1Seed;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * <b>Two TM1 servers at once</b>, for code that works across servers: migrating objects
 * from one to another, comparing environments, or syncing a model from source control.
 *
 * <p>
 * Each container is an independent TM1 server with its own Admin Server, on its own
 * mapped ports. For servers that register with one shared Admin Server, like a real
 * multi-server setup, see {@link SharedAdminServerExampleTest}.
 */
@RequiresTm1
class TwoServersExampleTest {

	@Test
	void comparesASourceAndATarget(Tm1ServerContainer target) {
		// The shared, empty server is the target. Start a second one, with data, as the
		// source.
		try (var source = new Tm1ServerContainer(target.getSource()).withSeed(Tm1Seed.SDATA)) {
			source.start();

			try (var from = new Tm1Rest(source); var to = new Tm1Rest(target)) {
				assertThat(source.getRestUrl()).isNotEqualTo(target.getRestUrl());
				assertThat(from.modelCubeCount()).isPositive();
				assertThat(to.modelCubeCount()).isZero();
				// ...then run your migration from `source` to `target` and compare again
			}
		}
	}

}

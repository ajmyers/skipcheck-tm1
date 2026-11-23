// SPDX-License-Identifier: Apache-2.0

package examples;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.session.Tm1Identity;
import io.skipcheck.tm1.client.session.Tm1SessionPool;
import reactor.core.publisher.Flux;

/**
 * Examples for {@link io.skipcheck.tm1.spring.boot.autoconfigure.Tm1AutoConfiguration},
 * quoted in its Javadoc: Spring components, compiled but never run.
 */
final class Tm1AutoConfigurationExamples {

	// @start region = inject
	// In application.properties:
	// tm1.address=http://tm1:8010
	// tm1.user=reporting
	// tm1.password=${TM1_PASSWORD}
	@Service
	static class SalesCubes {

		private final Tm1Client tm1;

		SalesCubes(Tm1Client tm1) {
			this.tm1 = tm1;
		}

		Flux<String> names() {
			return tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)).map(Cube::name);
		}

	}
	// @end

	// @start region = pool
	// With tm1.pool.enabled=true, and a service account that may impersonate users
	@Service
	static class UserCubes {

		private final Tm1SessionPool pool;

		UserCubes(Tm1SessionPool pool) {
			this.pool = pool;
		}

		// The cubes the user may see. Get a session for each piece of work
		Flux<String> names(String user) {
			return Flux.defer(() -> Tm1Client.of(pool.session(Tm1Identity.impersonating(user)))
				.cubes()
				.modelCubes(ODataQuery.none().select(Cube.NAME))
				.map(Cube::name));
		}

	}
	// @end

	// @start region = servers
	// With tm1.servers.planning.* and tm1.servers.reporting.*: a client for each
	@Service
	static class Reconciliation {

		private final Tm1Client planning;

		private final Tm1Client reporting;

		Reconciliation(@Qualifier("planning") Tm1Client planning, @Qualifier("reporting") Tm1Client reporting) {
			this.planning = planning;
			this.reporting = reporting;
		}

		// The cubes planning has and reporting doesn't
		Flux<String> notYetReported() {
			return cubes(reporting).collectList()
				.flatMapMany(reported -> cubes(planning).filter(cube -> !reported.contains(cube)));
		}

		private static Flux<String> cubes(Tm1Client tm1) {
			return tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)).map(Cube::name);
		}

	}
	// @end

	private Tm1AutoConfigurationExamples() {
	}

}

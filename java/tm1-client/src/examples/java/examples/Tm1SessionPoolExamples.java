// SPDX-License-Identifier: Apache-2.0

package examples;

import java.time.Duration;

import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.CubeService;
import io.skipcheck.tm1.client.session.Tm1Identity;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.session.Tm1SessionPool;
import reactor.core.publisher.Flux;

/**
 * Examples for {@link Tm1SessionPool}, quoted in its Javadoc.
 */
final class Tm1SessionPoolExamples {

	Flux<Cube> perUser(String secret, String user) {
		// @start region = per-user
		// One pool for the application, built once
		Tm1SessionPool pool = Tm1SessionPool
			.builder(Tm1Session.builder().baseUrl("http://tm1:8010/api/v1/").basicAuth("service", secret))
			.idleTimeout(Duration.ofMinutes(10))
			.build();
		// Each time it's needed: the user's session, which logs in on its first request
		Tm1Session session = pool.session(Tm1Identity.impersonating(user));
		return new CubeService(session).list(ODataQuery.none());
		// @end
	}

	private Tm1SessionPoolExamples() {
	}

}

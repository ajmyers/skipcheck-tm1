// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;
import io.skipcheck.tm1.testcontainers.Tm1EngineSource;
import io.skipcheck.tm1.testcontainers.Tm1Seed;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * <b>Pass-through logins, to a TM1 11 server</b>: the way TM1 v12 logs in the users a TM1
 * 11 server already has. The engine asks the server beside it who a name and password
 * are, so a test logs in to TM1 v12 with a login of TM1 11's.
 */
class PassThroughExampleTest {

	@Test
	@RequiresTm1
	@RequiresTm1Engine(shareDatabase = true)
	void aTm1ServersUserLogsInToTheEngine() {
		try (var server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withSeed(Tm1Seed.SDATA);
				var engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow()).withPassThrough(server)) {
			engine.start();
			Tm1Database db = engine.database("tm1");

			// admin / apple of the sample database: TM1 v12 keeps no password of its own
			var response = Tm1EngineRest.get(db, db.passThroughLogin(), "ActiveUser?$select=Name,Type");

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"Type\":\"Admin\"");
		}
	}

}

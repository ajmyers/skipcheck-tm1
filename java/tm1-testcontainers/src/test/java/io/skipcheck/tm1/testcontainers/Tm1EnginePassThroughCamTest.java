// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.examples.Tm1EngineRest;

/**
 * Pass-through logins to a TM1 11 server in Cognos (CAM) security, against real TM1 v12,
 * TM1 11 and Cognos: Cognos's users log in to TM1 v12 as they do to TM1 11. Cognos takes
 * minutes to start, so this class has one test.
 */
@RequiresCognos
@RequiresTm1
@RequiresTm1Engine(shareDatabase = true)
class Tm1EnginePassThroughCamTest {

	@Test
	void cognosUsersLogInToTheEngine() {
		try (var cognos = new CognosContainer(CognosSource.discover().orElseThrow())
			.withUser("admin", "adminpw", "Admins")
			.withUser("analyst", "analystpw", "Analysts")
			.withUser("viewer", "viewerpw", "Viewers");
				var server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withCamSecurity(cognos,
						"admin");
				var engine = new Tm1EngineContainer(Tm1EngineSource.discover().orElseThrow())
					.withUser("analyst", "Analysts")
					.withPassThrough(server)) {
			engine.start();
			Tm1Database db = engine.database("tm1");

			// A user the engine was given: under its CAMID, in the group it was given
			var analyst = Tm1EngineRest.get(db, db.passThroughLogin("analyst"),
					"ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");
			assertThat(analyst.statusCode()).isEqualTo(200);
			assertThat(analyst.body()).contains("CAMID(\\\"LDAP:u:uid=analyst,ou=people\\\")")
				.contains("\"Type\":\"User\"")
				.contains("Analysts");

			// The server's administrator is one here too
			var admin = Tm1EngineRest.get(db, db.passThroughLogin(), "ActiveUser?$select=Name,Type");
			assertThat(admin.statusCode()).isEqualTo(200);
			assertThat(admin.body()).contains("uid=admin,").contains("\"Type\":\"Admin\"");

			// Cognos knows the viewer, and TM1 11 logs them in; the engine has no such
			// user
			assertThatIllegalStateException().isThrownBy(() -> db.passThroughLogin("viewer").cookie())
				.withMessageContaining("HTTP 403");
			// Cognos checks the password
			assertThatIllegalStateException().isThrownBy(() -> db.passThroughLogin("analyst", "not-it").cookie())
				.withMessageContaining("HTTP 401");
			// The database's own admin still logs in through the application
			assertThat(Tm1EngineRest.get(db, db.login(), "ActiveUser").statusCode()).isEqualTo(200);
		}
	}

}

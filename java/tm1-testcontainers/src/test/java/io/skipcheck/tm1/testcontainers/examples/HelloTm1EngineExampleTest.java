// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;

/**
 * <b>A first test against TM1 v12</b>: {@link RequiresTm1Engine} starts one engine for
 * the run and gives this class a database of its own. TM1 v12 has no passwords:
 * {@code db.login()} logs in as {@code admin} through the engine's application.
 */
@RequiresTm1Engine
class HelloTm1EngineExampleTest {

	@Test
	void readsTheVersion(Tm1Database db) {
		var response = Tm1EngineRest.get(db, db.login(), "Configuration/ProductVersion/$value");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(Tm1Version.parse(response.body())).isEqualTo(db.getVersion());
		assertThat(db.getVersion().isAtLeast(Tm1Version.parse("12"))).isTrue();
	}

	@Test
	void logsInAsTheAdministrator(Tm1Database db) {
		var response = Tm1EngineRest.get(db, db.login(), "ActiveUser?$select=Name,Type");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"Type\":\"Admin\"");
	}

}

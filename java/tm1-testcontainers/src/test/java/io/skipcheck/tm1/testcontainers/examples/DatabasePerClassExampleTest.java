// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.skipcheck.tm1.testcontainers.RequiresTm1Engine;
import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1EngineContainer;

/**
 * <b>A database per test class, or one shared</b>: by default each class gets a new
 * database, so it can create what it likes under any name and leave it there.
 * {@code shareDatabase = true} uses the engine's one shared database instead, which costs
 * nothing to get but is shared with every other such class.
 */
@RequiresTm1Engine
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabasePerClassExampleTest {

	private static final String DIMENSION = "{\"Name\":\"Region\",\"Hierarchies\":[{\"Name\":\"Region\"}]}";

	@Test
	@Order(1)
	void theClassHasADatabaseOfItsOwn(Tm1Database db, Tm1EngineContainer engine) {
		// Named after the class, with a few random characters
		assertThat(db.getName()).startsWith("databaseperclassexample");
		assertThat(engine.databases()).contains(db, engine.database("tm1"));

		// No need for a unique name: nothing else is in here
		assertThat(Tm1EngineRest.post(db, db.login(), "Dimensions", DIMENSION).statusCode()).isEqualTo(201);
	}

	@Test
	@Order(2)
	void everyTestOfTheClassSeesTheSameDatabase(Tm1Database db) {
		assertThat(Tm1EngineRest.get(db, db.login(), "Dimensions('Region')?$select=Name").statusCode()).isEqualTo(200);
	}

	@Nested
	@RequiresTm1Engine(shareDatabase = true)
	class Shared {

		@Test
		void aSharingClassGetsTheEnginesOwnDatabase(Tm1Database db) {
			assertThat(db.getName()).isEqualTo(Tm1EngineContainer.DEFAULT_DATABASE);
			// Which the other class's dimension isn't in
			assertThat(Tm1EngineRest.get(db, db.login(), "Dimensions('Region')?$select=Name").statusCode())
				.isEqualTo(404);
		}

	}

}

// SPDX-License-Identifier: Apache-2.0

// Examples for requiresTm1Engine (Java's @RequiresTm1Engine) and Tm1EngineContainer, quoted in their
// documentation: type-checked but never run.
import { Tm1EngineContainer, type Tm1EngineSource } from "@skipcheck/tm1-testcontainers";
import { requiresTm1Engine } from "@skipcheck/tm1-testcontainers/vitest";
import { expect } from "vitest";

// @start region = test-class
// A database of this file's own, dropped when the file is done
requiresTm1Engine("lists cubes", async ({ tm1Database: db }) => {
  const response = await fetch(db.getRestUrl() + "Cubes?$select=Name", await db.login().authorize());
  expect(response.status).toBe(200);
});
// @end

// @start region = shared-database
// The engine's one shared database instead: nothing to create, but give what you make a unique name
const requiresSharedDatabase = requiresTm1Engine.extend({ shareDatabase: true });

requiresSharedDatabase("reads the server's name", async ({ tm1Database: db }) => {
  const response = await fetch(db.getRestUrl() + "Configuration/ServerName/$value", await db.login().authorize());
  expect(response.status).toBe(200);
});
// @end

export async function container(source: Tm1EngineSource): Promise<void> {
  // @start region = container
  await using engine = new Tm1EngineContainer(source).withDatabase("planning").withUser("analyst", "Analysts");
  await engine.start();
  const planning = engine.database("planning");
  // http://localhost:54321/tm1/api/v1/Databases('planning')/
  const url = planning.getRestUrl();
  const analyst = planning.login("analyst");
  // ...
  // @end
  expect(analyst.getUser(), url).toBe("analyst");
}

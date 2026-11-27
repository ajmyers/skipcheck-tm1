// SPDX-License-Identifier: Apache-2.0

// Examples for requiresTm1Engine (Java's @RequiresTm1Engine) and Tm1EngineContainer, quoted in their
// documentation: type-checked but never run.
import { KeycloakContainer, Tm1EngineContainer, type Tm1EngineSource, Tm1Seed, Tm1ServerContainer, type Tm1Source } from "@skipcheck/tm1-testcontainers";
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

export async function oauth2(source: Tm1EngineSource): Promise<void> {
  // @start region = oauth2
  // Starting the engine starts Keycloak first; the Keycloak is yours to stop
  await using keycloak = new KeycloakContainer();
  await using engine = new Tm1EngineContainer(source).withUser("analyst", "Analysts").withLogin("analyst", "analystpw").withOAuth2(keycloak);
  await engine.start();
  const db = engine.database("tm1");
  // A token from Keycloak, with the analyst's password, sent to TM1 as Bearer
  const request = await db.oauth2Login("analyst").authorizeWithToken();
  const response = await fetch(db.getRestUrl() + "Cubes?$select=Name", request);
  // @end
  expect(response.status).toBe(200);
}

export async function passThrough(source: Tm1EngineSource, tm1: Tm1Source): Promise<void> {
  // @start region = pass-through
  // Starting the engine starts the TM1 11 server first; the server is yours to stop
  await using server = new Tm1ServerContainer(tm1).withSeed(Tm1Seed.SDATA);
  await using engine = new Tm1EngineContainer(source).withPassThrough(server);
  await engine.start();
  const db = engine.database("tm1");
  // The sample database's admin / apple, which TM1 v12 asks the server about
  const request = await db.passThroughLogin().authorize();
  const response = await fetch(db.getRestUrl() + "Cubes?$select=Name", request);
  // @end
  expect(response.status).toBe(200);
}

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

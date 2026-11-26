// SPDX-License-Identifier: Apache-2.0

import { Tm1Version } from "@skipcheck/tm1-client";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { type Tm1Database, Tm1EngineContainer, Tm1EngineSource } from "../src/index.js";
import { RequiresTm1Engine, requiresTm1Engine } from "../src/vitest.js";
import { Tm1EngineRest } from "./support/Tm1EngineRest.js";

/** The Java module's examples for TM1 v12 (its *ExampleTest classes), test for test: each is a real
 * test, so it always works as written. They're in one file, and so share one database, where Java
 * has a class and a database for each: the first two only read, and the third says what it
 * creates. Skipped without Docker or a TM1 v12 source. */

/** Java's HelloTm1EngineExampleTest: a first test against TM1 v12. `requiresTm1Engine` starts one
 * engine for the run and gives this file a database of its own. TM1 v12 has no passwords:
 * `db.login()` logs in as `admin` through the engine's application. */
describe("A first test against TM1 v12", () => {
  requiresTm1Engine("reads the version", async ({ tm1Database: db }) => {
    const response = await Tm1EngineRest.get(db, db.login(), "Configuration/ProductVersion/$value");

    expect(response.status).toBe(200);
    expect(Tm1Version.parse(response.body).equals(db.getVersion())).toBe(true);
    expect(db.getVersion().isAtLeast(Tm1Version.parse("12"))).toBe(true);
  });

  requiresTm1Engine("logs in as the administrator", async ({ tm1Database: db }) => {
    const response = await Tm1EngineRest.get(db, db.login(), "ActiveUser?$select=Name,Type");

    expect(response.status).toBe(200);
    expect(response.body).toContain('"Type":"Admin"');
  });
});

/** Java's DatabasePerClassExampleTest: a database per test file, or one shared. By default each
 * file gets a new database, so it can create what it likes under any name and leave it there.
 * `shareDatabase: true` uses the engine's one shared database instead, which costs nothing to get
 * but is shared with every other such test. */
describe("A database per test file, or one shared", () => {
  const DIMENSION = '{"Name":"Region","Hierarchies":[{"Name":"Region"}]}';

  // A file here, where Java has a class: the unit vitest shares set-up over
  requiresTm1Engine("the class has a database of its own", async ({ tm1Database: db, tm1Engine: engine }) => {
    // Named after the file, with a few random characters
    expect(db.getName()).toMatch(/^engineexamples_[0-9a-z]{4}$/);
    const databases = await engine.databases();
    expect(databases.some((d) => d.equals(db))).toBe(true);
    expect(databases.some((d) => d.equals(engine.database("tm1")))).toBe(true);

    // No need for a unique name: nothing else is in here
    expect((await Tm1EngineRest.post(db, db.login(), "Dimensions", DIMENSION)).status).toBe(201);
  });

  requiresTm1Engine("every test of the class sees the same database", async ({ tm1Database: db }) => {
    expect((await Tm1EngineRest.get(db, db.login(), "Dimensions('Region')?$select=Name")).status).toBe(200);
  });

  describe("Shared", () => {
    // Java's @RequiresTm1Engine(shareDatabase = true)
    const requiresSharedDatabase = requiresTm1Engine.extend({ shareDatabase: true });

    requiresSharedDatabase("a sharing class gets the engine's own database", async ({ tm1Database: db }) => {
      expect(db.getName()).toBe(Tm1EngineContainer.DEFAULT_DATABASE);
      // Which the file's dimension isn't in
      expect((await Tm1EngineRest.get(db, db.login(), "Dimensions('Region')?$select=Name")).status).toBe(404);
    });
  });
});

/** Java's S2sUsersExampleTest: users with different rights. An engine of these tests' own, with two
 * users besides `admin`. TM1 v12 keeps no passwords, so a test logs in as any of them by name,
 * through the engine's application ("service-to-service"). An engine of your own takes about 40
 * seconds to start. */
describe("Users with different rights", async () => {
  // A file-level setup that needs the engine: skip it as the tests are skipped
  const skipped = (await RequiresTm1Engine.skipReason()) !== undefined;
  let engine: Tm1EngineContainer | undefined;
  let planning: Tm1Database;

  beforeAll(async () => {
    if (skipped) return;
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withDatabase("planning").withUser("analyst", "Analysts").withUser("viewer");
    await engine.start();
    planning = engine.database("planning");
  });

  afterAll(async () => {
    await engine?.stop();
  });

  requiresTm1Engine("the administrator is an administrator", async () => {
    const response = await Tm1EngineRest.get(planning, planning.login(), "ActiveUser?$select=Name,Type");

    expect(response.body).toContain('"Type":"Admin"');
  });

  requiresTm1Engine("a user is in the groups they were given", async () => {
    const response = await Tm1EngineRest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");

    expect(response.status).toBe(200);
    expect(response.body).toContain('"Type":"User"');
    expect(response.body).toContain('"Name":"Analysts"');
  });

  requiresTm1Engine("a user without groups can still log in", async () => {
    const response = await Tm1EngineRest.get(planning, planning.login("viewer"), "ActiveUser?$select=Name&$expand=Groups($select=Name)");

    expect(response.status).toBe(200);
    expect(response.body).toContain('"Groups":[]');
  });

  requiresTm1Engine("a name TM1 doesn't know gets a session but no access", async () => {
    // The application vouches for any name, so the login itself works
    const nobody = planning.login("nobody");
    expect(await nobody.cookie()).toMatch(/^TM1SessionId=/);

    expect((await Tm1EngineRest.get(planning, nobody, "ActiveUser")).status).toBe(403);
  });
});

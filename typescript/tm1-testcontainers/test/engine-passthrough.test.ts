// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  CognosContainer,
  IllegalStateException,
  type Tm1Database,
  Tm1EngineContainer,
  Tm1EngineSource,
  Tm1Seed,
  Tm1ServerContainer,
  Tm1Source,
} from "../src/index.js";
import { RequiresTm1, RequiresTm1Engine, requiresCognos, requiresTm1Engine } from "../src/vitest.js";
import { Tm1EngineRest } from "./support/Tm1EngineRest.js";

// Nothing here asks for the shared engine, the shared server or a database of the file's own:
// each part starts an engine and a TM1 11 server of its own, and stops them

/** As Java's @RequiresTm1 with @RequiresTm1Engine: skipped without a TM1 11 source too. */
async function requireTm1(skip: (note?: string) => never): Promise<void> {
  const why = await RequiresTm1.skipReason();
  if (why !== undefined) skip(why);
}

/** Java's Tm1EnginePassThroughTest: pass-through logins against real TM1 v12 and TM1 11. An engine
 * of these tests' own, asking a TM1 11 server beside it who a name and password are. Skipped
 * without Docker, a TM1 v12 source or a TM1 11 source. */
describe("Tm1Engine pass-through", async () => {
  // A file-level setup that needs the engine and the server: skip it as the tests are skipped
  const skipped = (await RequiresTm1Engine.skipReason()) !== undefined || (await RequiresTm1.skipReason()) !== undefined;
  let server: Tm1ServerContainer | undefined;
  let engine: Tm1EngineContainer | undefined;
  let planning: Tm1Database;

  beforeAll(async () => {
    if (skipped) return;
    server = new Tm1ServerContainer(Tm1Source.discover()!).withSeed(Tm1Seed.SDATA);
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withDatabase("planning").withUser("analyst", "Analysts").withPassThrough(server);
    await engine.start();
    planning = engine.database("planning");
    // Two users of the TM1 11 server's own: one the engine has too, and one it lacks
    for (const name of ["analyst", "stranger"]) {
      const created = await Tm1EngineRest.send(server.getRestUrl() + "Users", {
        method: "POST",
        headers: { Authorization: server.getAuthorization(), "Content-Type": "application/json" },
        body: JSON.stringify({ Name: name, Password: name + "pw" }),
      });
      expect(created.status, created.body).toBe(201);
    }
  });

  afterAll(async () => {
    try {
      await engine?.stop();
    } finally {
      // The server is the caller's to stop: the engine only started it first
      await server?.stop();
    }
  });

  requiresTm1Engine("the server's own user logs in", async ({ skip }) => {
    await requireTm1(skip);
    // admin / apple of the sample database, sent to TM1 v12, which keeps no password
    const response = await Tm1EngineRest.get(planning, planning.passThroughLogin(), "ActiveUser?$select=Name,Type");
    expect(response.status).toBe(200);
    expect(response.body).toContain('"Type":"Admin"');
  });

  requiresTm1Engine("a user of both logs in as themselves", async ({ skip }) => {
    await requireTm1(skip);
    const analyst = planning.passThroughLogin("analyst", "analystpw");
    const response = await Tm1EngineRest.get(planning, analyst, "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");
    expect(response.status).toBe(200);
    expect(response.body.toLowerCase()).toContain('"name":"analyst"');
    expect(response.body).toContain('"Type":"User"');
    expect(response.body).toContain("Analysts");
  });

  requiresTm1Engine("the header logs in on any request", async ({ skip }) => {
    await requireTm1(skip);
    // As a client that logs in itself sends it: TM1 answers, and gives a session
    const analyst = planning.passThroughLogin("analyst", "analystpw");
    const response = await Tm1EngineRest.send(planning.getRestUrl() + "ActiveUser?$select=Name", { headers: { Authorization: analyst.getAuthorization() } });
    expect(response.status).toBe(200);
    expect(response.cookies.some((cookie) => cookie.startsWith("TM1SessionId="))).toBe(true);
    expect(analyst.getAuthUrl()).toBe(engine!.getUrl() + "tm1/auth/v1/");
    expect(analyst.getTokenUrl()).toBe(engine!.getUrl() + "tm1/auth/v1/token");
    // A header of the caller's own making is passed on the same
    const same = planning.passThroughLoginWith(analyst.getAuthorization());
    expect((await Tm1EngineRest.get(planning, same, "ActiveUser")).status).toBe(200);
  });

  requiresTm1Engine("a wrong password is refused", async ({ skip }) => {
    await requireTm1(skip);
    const refused = planning.passThroughLogin("analyst", "not-it").cookie();
    await expect(refused).rejects.toThrow(IllegalStateException);
    await expect(refused).rejects.toThrow("HTTP 401");
  });

  requiresTm1Engine("a user the database lacks is refused", async ({ skip }) => {
    await requireTm1(skip);
    // The TM1 11 server knows the name and password; the TM1 v12 database has no such user
    const refused = planning.passThroughLogin("stranger", "strangerpw").cookie();
    await expect(refused).rejects.toThrow(IllegalStateException);
    await expect(refused).rejects.toThrow("HTTP 403");
  });

  requiresTm1Engine("logins through the application still work beside pass-through", async ({ skip }) => {
    await requireTm1(skip);
    const response = await Tm1EngineRest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name");
    expect(response.status).toBe(200);
    expect(response.body.toLowerCase()).toContain('"name":"analyst"');
  });
});

/** Java's Tm1EnginePassThroughCamTest: pass-through logins to a TM1 11 server in Cognos (CAM)
 * security, against real TM1 v12, TM1 11 and Cognos. Cognos's users log in to TM1 v12 as they do
 * to TM1 11. Cognos takes minutes to start, so this has one test. */
describe("Tm1Engine pass-through to CAM security", () => {
  // As Java's @RequiresCognos (which needs TM1 too) with @RequiresTm1Engine
  requiresCognos("Cognos users log in to the engine", { timeout: 45 * 60_000 }, async ({ skip, cognosSource }) => {
    const why = await RequiresTm1Engine.skipReason();
    if (why !== undefined) skip(why);
    // Stopped in reverse: the engine, then the server, then Cognos
    await using cognos = new CognosContainer(cognosSource)
      .withUser("admin", "adminpw", "Admins")
      .withUser("analyst", "analystpw", "Analysts")
      .withUser("viewer", "viewerpw", "Viewers");
    await using server = new Tm1ServerContainer(Tm1Source.discover()!).withCamSecurity(cognos, "admin");
    await using engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withUser("analyst", "Analysts").withPassThrough(server);
    await engine.start();
    const db = engine.database("tm1");

    // A user the engine was given: under its CAMID, in the group it was given
    const analyst = await Tm1EngineRest.get(db, db.passThroughLogin("analyst"), "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");
    expect(analyst.status).toBe(200);
    expect(analyst.body).toContain('CAMID(\\"LDAP:u:uid=analyst,ou=people\\")');
    expect(analyst.body).toContain('"Type":"User"');
    expect(analyst.body).toContain("Analysts");

    // The server's administrator is one here too
    const admin = await Tm1EngineRest.get(db, db.passThroughLogin(), "ActiveUser?$select=Name,Type");
    expect(admin.status).toBe(200);
    expect(admin.body).toContain("uid=admin,");
    expect(admin.body).toContain('"Type":"Admin"');

    // Cognos knows the viewer, and TM1 11 logs them in; the engine has no such user
    await expect(db.passThroughLogin("viewer").cookie()).rejects.toThrow("HTTP 403");
    // Cognos checks the password
    await expect(db.passThroughLogin("analyst", "not-it").cookie()).rejects.toThrow("HTTP 401");
    // The database's own admin still logs in through the application
    expect((await Tm1EngineRest.get(db, db.login(), "ActiveUser")).status).toBe(200);
  });
});

/** Java's PassThroughExampleTest: pass-through logins, to a TM1 11 server, the way TM1 v12 logs in
 * the users a TM1 11 server already has. The engine asks the server beside it who a name and
 * password are, so a test logs in to TM1 v12 with a login of TM1 11's. */
describe("Pass-through logins, to a TM1 11 server", () => {
  requiresTm1Engine("a TM1 server's user logs in to the engine", { timeout: 30 * 60_000 }, async ({ skip }) => {
    await requireTm1(skip);
    // Stopped in reverse: the engine, then the server, which is yours to stop
    await using server = new Tm1ServerContainer(Tm1Source.discover()!).withSeed(Tm1Seed.SDATA);
    await using engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withPassThrough(server);
    await engine.start();
    const db = engine.database("tm1");

    // admin / apple of the sample database: TM1 v12 keeps no password of its own
    const response = await Tm1EngineRest.get(db, db.passThroughLogin(), "ActiveUser?$select=Name,Type");

    expect(response.status).toBe(200);
    expect(response.body).toContain('"Type":"Admin"');
  });
});

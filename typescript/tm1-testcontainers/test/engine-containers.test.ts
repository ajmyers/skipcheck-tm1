// SPDX-License-Identifier: Apache-2.0

import { Tm1Version } from "@skipcheck/tm1-client";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { IllegalArgumentException, type Tm1Database, Tm1EngineContainer, Tm1EngineSource } from "../src/index.js";
import { RequiresTm1Engine, requiresTm1Engine } from "../src/vitest.js";
import { Tm1EngineRest } from "./support/Tm1EngineRest.js";

const basic = (clientId: string, clientSecret: string): string => "Basic " + Buffer.from(clientId + ":" + clientSecret, "utf8").toString("base64");

/** Java's Tm1EngineContainerTest: the engine against real TM1 v12, its databases and the
 * service-to-service logins. One engine for these tests, with two databases in the default
 * instance and one in another. Skipped without Docker or a TM1 v12 source. */
describe("Tm1EngineContainer", async () => {
  // A file-level setup that needs the engine: skip it as the tests are skipped
  const skipped = (await RequiresTm1Engine.skipReason()) !== undefined;
  let engine: Tm1EngineContainer | undefined;
  let planning: Tm1Database;
  let sales: Tm1Database;
  let elsewhere: Tm1Database;

  beforeAll(async () => {
    if (skipped) return;
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!)
      .withDatabase("planning")
      .withDatabase("sales")
      .withDatabase("other", "elsewhere")
      .withUser("analyst", "Analysts");
    await engine.start();
    planning = engine.database("planning");
    sales = engine.database("sales");
    elsewhere = engine.database("other", "elsewhere");
  });

  afterAll(async () => {
    await engine?.stop();
  });

  requiresTm1Engine("starts with the databases it was given", async () => {
    const databases = await engine!.databases();
    expect(databases.map(String).sort()).toEqual([planning, sales, elsewhere].map(String).sort());
    for (const database of [planning, sales, elsewhere]) expect(databases.some((d) => d.equals(database))).toBe(true);
    expect(planning.getRestUrl()).toBe(engine!.getUrl() + "tm1/api/v1/Databases('planning')/");
    expect(elsewhere.getRestUrl()).toBe(engine!.getUrl() + "other/api/v1/Databases('elsewhere')/");
    expect(engine!.getTm1Version().isAtLeast(Tm1Version.parse("12"))).toBe(true);
    expect(() => engine!.database("missing")).toThrow(IllegalArgumentException);
    expect(() => engine!.database("missing")).toThrow("No database tm1/missing in this engine");
  });

  requiresTm1Engine("logs in with the application's credentials as Basic", async () => {
    const admin = planning.login();
    expect(await admin.cookie()).toMatch(/^TM1SessionId=/);
    // The one session serves every database of the instance
    for (const database of [planning, sales]) {
      const response = await Tm1EngineRest.get(database, admin, "ActiveUser?$select=Name,Type");
      expect(response.status).toBe(200);
      expect(response.body).toContain('"Type":"Admin"');
    }
  });

  requiresTm1Engine("logs in with a bearer token", async () => {
    const analyst = planning.login("analyst");
    const token = await analyst.token();
    expect(token.split(".")).toHaveLength(3);

    const cookie = await analyst.session("Bearer " + token);
    const response = await Tm1EngineRest.send(planning.getRestUrl() + "ActiveUser?$select=Name", { headers: { Cookie: cookie } });
    expect(response.status).toBe(200);
    expect(response.body.toLowerCase()).toContain('"name":"analyst"');
  });

  requiresTm1Engine("a user TM1 doesn't know gets a session and is then refused", async () => {
    const nobody = planning.login("nobody");
    expect(await nobody.cookie()).toMatch(/^TM1SessionId=/);
    expect((await Tm1EngineRest.get(planning, nobody, "ActiveUser")).status).toBe(403);
  });

  requiresTm1Engine("a user missing from one database is refused there only", async () => {
    // Users belong to a database: take the analyst out of one
    expect((await Tm1EngineRest.delete(sales, sales.login(), "Users('analyst')")).status).toBe(204);

    const analyst = planning.login("analyst");
    expect((await Tm1EngineRest.get(planning, analyst, "ActiveUser")).status).toBe(200);
    expect((await Tm1EngineRest.get(sales, analyst, "ActiveUser")).status).toBe(403);
  });

  requiresTm1Engine("another instance's credentials are refused", async () => {
    // An application belongs to its instance: the other's can't log in here
    const other = elsewhere.login();
    expect((await Tm1EngineRest.get(elsewhere, other, "ActiveUser")).status).toBe(200);

    const response = await Tm1EngineRest.send(planning.login().getAuthUrl() + "session", {
      method: "POST",
      headers: { Authorization: basic(other.getClientId(), other.getClientSecret()), "Content-Type": "application/json" },
      body: '{"User":"admin"}',
    });
    expect(response.status).toBe(401);
  });

  requiresTm1Engine("creates and drops a database", async () => {
    const started = performance.now();
    const made = await engine!.createDatabase("scratch");
    const created = performance.now();
    expect((await engine!.databases()).some((d) => d.equals(made))).toBe(true);
    expect(engine!.database("scratch").equals(made)).toBe(true);
    // With the engine's users, like the databases it started with
    expect((await Tm1EngineRest.get(made, made.login("analyst"), "ActiveUser")).status).toBe(200);

    const before = performance.now();
    await engine!.dropDatabase(made);
    const dropped = performance.now();
    expect((await engine!.databases()).some((d) => d.equals(made))).toBe(false);
    expect(() => engine!.database("scratch")).toThrow("No database tm1/scratch in this engine");
    console.log(`Created a database in ${((created - started) / 1000).toFixed(1)} s and dropped it in ${((dropped - before) / 1000).toFixed(1)} s`);
  });

  requiresTm1Engine("gives the root credentials for the management API", async () => {
    const response = await Tm1EngineRest.send(engine!.getManagementUrl() + "Instances", {
      headers: { Authorization: basic(engine!.getRootClientId(), engine!.getRootClientSecret()) },
    });
    expect(response.status).toBe(200);
    expect(response.body).toContain('"tm1"');
    expect(response.body).toContain('"other"');
  });
});

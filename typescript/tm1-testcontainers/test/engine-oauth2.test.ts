// SPDX-License-Identifier: Apache-2.0

import { setTimeout as sleep } from "node:timers/promises";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { IllegalStateException, KeycloakContainer, type Tm1Database, Tm1EngineContainer, Tm1EngineSource } from "../src/index.js";
import { RequiresTm1Engine, requiresTm1Engine } from "../src/vitest.js";
import { Tm1EngineRest } from "./support/Tm1EngineRest.js";

// Nothing here asks for the shared engine or a database of the file's own: each part starts an
// engine and a Keycloak of its own, and stops them

/** Java's Tm1EngineOAuth2Test: OAuth 2.0 logins against real TM1 v12 and Keycloak. An engine of
 * these tests' own, taking tokens from a Keycloak beside it. Skipped without Docker or a TM1 v12
 * source. */
describe("Tm1Engine OAuth2", async () => {
  // A file-level setup that needs the engine: skip it as the tests are skipped
  const skipped = (await RequiresTm1Engine.skipReason()) !== undefined;
  let keycloak: KeycloakContainer | undefined;
  let engine: Tm1EngineContainer | undefined;
  let planning: Tm1Database;

  beforeAll(async () => {
    if (skipped) return;
    keycloak = new KeycloakContainer();
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!)
      .withDatabase("planning")
      .withUser("analyst", "Analysts")
      .withLogin("analyst", "analyst$pw")
      .withLogin("admin", "adminpw")
      .withOAuth2(keycloak);
    await engine.start();
    planning = engine.database("planning");
  });

  afterAll(async () => {
    try {
      await engine?.stop();
    } finally {
      // The Keycloak is the caller's to stop: the engine only started it first
      await keycloak?.stop();
    }
  });

  const bearer = (token: string): RequestInit => ({ headers: { Authorization: "Bearer " + token } });

  requiresTm1Engine("a Keycloak token logs the user in", async () => {
    const analyst = planning.oauth2Login("analyst");
    // The token on a request of its own: TM1 answers, and gives a session
    const response = await Tm1EngineRest.send(planning.getRestUrl() + "ActiveUser?$select=Name,Type", await analyst.authorizeWithToken());
    expect(response.status).toBe(200);
    expect(response.body.toLowerCase()).toContain('"name":"analyst"');
    expect(response.body).toContain('"Type":"User"');
    expect(response.cookies.some((cookie) => cookie.startsWith("TM1SessionId="))).toBe(true);
    // And the session that gives serves later requests
    expect(await analyst.cookie()).toMatch(/^TM1SessionId=/);
    expect((await Tm1EngineRest.get(planning, analyst, "ActiveUser")).status).toBe(200);
  });

  requiresTm1Engine("the administrator logs in through OAuth2 too", async () => {
    const response = await Tm1EngineRest.get(planning, planning.oauth2Login("admin"), "ActiveUser?$select=Name,Type");
    expect(response.status).toBe(200);
    expect(response.body).toContain('"Type":"Admin"');
  });

  requiresTm1Engine("a token of another realm is refused", async () => {
    // Signed by the same Keycloak, but not by the issuer TM1 trusts, and without its audience
    const other = await keycloak!.accessToken("master", "admin-cli", "admin", "admin");
    expect((await Tm1EngineRest.send(planning.getRestUrl() + "ActiveUser", bearer(other))).status).toBe(401);
  });

  requiresTm1Engine("something that isn't a token is refused", async () => {
    expect((await Tm1EngineRest.send(planning.getRestUrl() + "ActiveUser", bearer("not.a.token"))).status).toBe(401);
  });

  /** Sets how long the realm's access tokens live, through Keycloak's admin API. */
  async function setTokenLife(adminToken: string, seconds: number): Promise<void> {
    const response = await Tm1EngineRest.send(keycloak!.getUrl() + "/admin/realms/tm1", {
      method: "PUT",
      headers: { Authorization: "Bearer " + adminToken, "Content-Type": "application/json" },
      body: JSON.stringify({ accessTokenLifespan: seconds }),
    });
    expect(response.status).toBe(204);
  }

  requiresTm1Engine("an expired token is refused", async () => {
    // Tokens that live a second, for as long as it takes to get one
    const admin = await keycloak!.accessToken("master", "admin-cli", "admin", "admin");
    await setTokenLife(admin, 1);
    let token: string;
    try {
      token = await planning.oauth2Login("analyst").token();
    } finally {
      await setTokenLife(admin, 300);
    }
    // TM1 may allow a clock some slack: wait for it to refuse, for up to two minutes
    const started = performance.now();
    let status = 0;
    while (performance.now() - started < 120_000) {
      status = (await Tm1EngineRest.send(planning.getRestUrl() + "ActiveUser", bearer(token))).status;
      if (status === 401) break;
      await sleep(2_000);
    }
    console.log(`TM1 refused the expired token after ${((performance.now() - started) / 1000).toFixed(0)} s`);
    expect(status).toBe(401);
  });

  requiresTm1Engine("logins through the application still work", async () => {
    const analyst = planning.login("analyst");
    expect(analyst.isOAuth2()).toBe(false);
    expect((await Tm1EngineRest.get(planning, analyst, "ActiveUser")).status).toBe(200);
    // TM1's own token isn't one to send on a request
    await expect(analyst.authorizeWithToken()).rejects.toThrow(IllegalStateException);
    // And an OAuth 2.0 login has no session endpoint to ask
    await expect(planning.oauth2Login("analyst").session("Bearer x")).rejects.toThrow(IllegalStateException);
  });

  requiresTm1Engine("the login says where its tokens come from", async () => {
    const analyst = planning.oauth2Login("analyst");
    expect(analyst.getTokenUrl()).toBe(keycloak!.getUrl() + "/realms/tm1/protocol/openid-connect/token");
    expect(analyst.getClientId()).toBe("tm1");
    expect(analyst.getPassword()).toBe("analyst$pw");
    expect((await analyst.token()).split(".")).toHaveLength(3);
  });
});

/** Java's OAuth2ExampleTest: OAuth 2.0 logins, with Keycloak, the way TM1 v12 logs users in from an
 * identity provider. The engine takes tokens from a Keycloak beside it, which knows the users who
 * were given a password. A test gets a token as the user and sends it to TM1. */
describe("OAuth 2.0 logins, with Keycloak", () => {
  requiresTm1Engine("a user logs in with a token from Keycloak", { timeout: 30 * 60_000 }, async () => {
    // Stopped in reverse: the engine, then the Keycloak, which is yours to stop
    await using keycloak = new KeycloakContainer();
    await using engine = new Tm1EngineContainer(Tm1EngineSource.discover()!)
      .withUser("analyst", "Analysts")
      .withLogin("analyst", "analystpw")
      .withOAuth2(keycloak);
    await engine.start();
    const db = engine.database("tm1");

    const response = await Tm1EngineRest.send(db.getRestUrl() + "ActiveUser?$select=Name", await db.oauth2Login("analyst").authorizeWithToken());

    expect(response.status).toBe(200);
    expect(response.body.toLowerCase()).toContain('"name":"analyst"');
  });
});

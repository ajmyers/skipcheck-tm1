// SPDX-License-Identifier: Apache-2.0

import { Tm1Database, Tm1EngineContainer, Tm1EngineSource, Tm1Seed, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  ODataQuery,
  Tm1ApiException,
  Tm1AuthenticationException,
  Tm1Authenticator,
  Tm1Client,
  Tm1Dialect,
  Tm1ForbiddenException,
  UserCreate,
  UserType,
} from "../../src/index.js";
import { enginePassThroughConfigured, enginePassThroughReason, enginePassThroughTest } from "./live.js";

const WAIT = 60_000;

/** Pass-through Basic authentication on a TM1 v12 engine backed by a TM1 11 server. The Java
 * client's `Tm1EnginePassThroughAuthTm1Test`. */
describe("TM1 v12 engine pass-through Basic authentication" + enginePassThroughReason, () => {
  let server: Tm1ServerContainer | undefined;
  let engine: Tm1EngineContainer | undefined;
  let database: Tm1Database | undefined;

  const client = (authenticator: Tm1Authenticator): Tm1Client =>
    Tm1Client.builder()
      .baseUrl(database!.getRestUrl())
      .dialect(Tm1Dialect.v12(database!.getInstance(), database!.getName()))
      .authenticator(authenticator)
      .build();

  beforeAll(async () => {
    if (!enginePassThroughConfigured) return;
    server = new Tm1ServerContainer(Tm1Source.discover()!).withSeed(Tm1Seed.SDATA);
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withDatabase("planning").withUser("analyst", "Analysts").withPassThrough(server);
    await engine.start();
    database = engine.database("planning");
    const tm11 = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()).build();
    try {
      await tm11.users.create(UserCreate.builder().name("analyst").password("analystpw").build());
      await tm11.users.create(UserCreate.builder().name("stranger").password("strangerpw").build());
    } finally {
      await tm11.close();
    }
  });

  afterAll(async () => {
    try {
      await engine?.stop();
    } finally {
      await server?.stop();
    }
  });

  enginePassThroughTest(
    "logs on as an administrator and a user through pass through",
    async () => {
      const admin = client(Tm1Authenticator.basic(server!.getUsername(), server!.getPassword()));
      try {
        expect((await admin.users.activeUser()).Type).toBe(UserType.Admin);
      } finally {
        await admin.close();
      }

      const analyst = client(Tm1Authenticator.basic("analyst", "analystpw"));
      try {
        const user = await analyst.users.activeUser(ODataQuery.none().expand("Groups"));
        expect(user.Name?.toLowerCase()).toBe("analyst");
        expect(user.Type).toBe(UserType.User);
        expect((user.Groups ?? []).map((g) => g.Name)).toContain("Analysts");
      } finally {
        await analyst.close();
      }
    },
    WAIT,
  );

  enginePassThroughTest(
    "refuses a wrong password and a user the database lacks",
    async () => {
      const wrongPassword = client(Tm1Authenticator.basic("analyst", "not-it"));
      try {
        const err = await wrongPassword.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1AuthenticationException);
        expect((err as Tm1ApiException).kind).toBe("CredentialRejected");
      } finally {
        await wrongPassword.close().catch(() => undefined);
      }

      // TM1 11 accepts stranger's password; the TM1 v12 database has no such user
      const stranger = client(Tm1Authenticator.basic("stranger", "strangerpw"));
      try {
        const err = await stranger.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1ForbiddenException);
        expect((err as Tm1ApiException).kind).toBe("Forbidden");
      } finally {
        await stranger.close().catch(() => undefined);
      }
    },
    WAIT,
  );
});

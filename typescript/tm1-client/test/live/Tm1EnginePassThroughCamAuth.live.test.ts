// SPDX-License-Identifier: Apache-2.0

import { CognosContainer, CognosSource, Tm1Database, Tm1EngineContainer, Tm1EngineSource, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  CognosPassports,
  ODataQuery,
  Tm1ApiException,
  Tm1AuthenticationException,
  Tm1Authenticator,
  Tm1Client,
  Tm1Dialect,
  Tm1ForbiddenException,
  type Tm1Supplier,
  UserType,
} from "../../src/index.js";
import { enginePassThroughCamConfigured, enginePassThroughCamReason, enginePassThroughCamTest } from "./live.js";

const WAIT = 60_000;
const ANALYST = 'CAMID("LDAP:u:uid=analyst,ou=people")';

/** Pass-through CAM authentication on a TM1 v12 engine backed by a TM1 11 server in Cognos (CAM)
 * security: `CAMNamespace` and `CAMPassport` (via Cognos). The Java client's
 * `Tm1EnginePassThroughCamAuthTm1Test`. */
describe("TM1 v12 engine pass-through CAM authentication" + enginePassThroughCamReason, () => {
  let cognos: CognosContainer | undefined;
  let server: Tm1ServerContainer | undefined;
  let engine: Tm1EngineContainer | undefined;
  let database: Tm1Database | undefined;

  const client = (authenticator: Tm1Authenticator): Tm1Client =>
    Tm1Client.builder()
      .baseUrl(database!.getRestUrl())
      .dialect(Tm1Dialect.v12(database!.getInstance(), database!.getName()))
      .authenticator(authenticator)
      .build();

  const counting = (passports: Tm1Supplier): { supplier: Tm1Supplier; asked: () => number } => {
    let asked = 0;
    return {
      supplier: (signal) => {
        asked++;
        return passports(signal);
      },
      asked: () => asked,
    };
  };

  beforeAll(async () => {
    if (!enginePassThroughCamConfigured) return;
    cognos = new CognosContainer(CognosSource.discover()!)
      .withUser("admin", "adminpw", "Admins")
      .withUser("analyst", "analystpw", "Analysts")
      .withUser("viewer", "viewerpw", "Viewers");
    server = new Tm1ServerContainer(Tm1Source.discover()!).withCamSecurity(cognos, "admin");
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withUser("analyst", "Analysts").withPassThrough(server);
    await engine.start();
    database = engine.database("tm1");
  });

  afterAll(async () => {
    try {
      await engine?.stop();
    } finally {
      try {
        await server?.stop();
      } finally {
        await cognos?.stop();
      }
    }
  });

  enginePassThroughCamTest(
    "logs on with CAM namespace through pass through",
    async () => {
      const analyst = client(Tm1Authenticator.camNamespace("analyst", "analystpw", "LDAP"));
      try {
        const user = await analyst.users.activeUser(ODataQuery.none().expand("Groups"));
        expect(user.Name).toBe(ANALYST);
        expect(user.Type).toBe(UserType.User);
        expect((user.Groups ?? []).map((g) => g.Name)).toContain("Analysts");
      } finally {
        await analyst.close();
      }

      const admin = client(Tm1Authenticator.camNamespace("admin", "adminpw", "LDAP"));
      try {
        const user = await admin.users.activeUser();
        expect(user.Name).toContain("uid=admin,");
        expect(user.Type).toBe(UserType.Admin);
      } finally {
        await admin.close();
      }
    },
    WAIT,
  );

  enginePassThroughCamTest(
    "logs on with a Cognos passport through pass through",
    async () => {
      const analyst = client(Tm1Authenticator.cognosLogon(cognos!.getUrl(), "LDAP", "analyst", "analystpw"));
      try {
        const user = await analyst.users.activeUser(ODataQuery.none().expand("Groups"));
        expect(user.Name).toBe(ANALYST);
        expect(user.Type).toBe(UserType.User);
        expect((user.Groups ?? []).map((g) => g.Name)).toContain("Analysts");
      } finally {
        await analyst.close();
      }
    },
    WAIT,
  );

  enginePassThroughCamTest(
    "refuses a wrong password and a Cognos user the database lacks",
    async () => {
      const passports = counting(CognosPassports.password(cognos!.getUrl(), "LDAP", "analyst", "not-it"));
      const wrongPassport = client(Tm1Authenticator.camPassport(passports.supplier));
      try {
        const err = await wrongPassport.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1AuthenticationException);
        expect((err as Tm1ApiException).kind).toBe("CredentialRejected");
        await expect(wrongPassport.users.activeUser()).rejects.toBeInstanceOf(Tm1AuthenticationException);
        expect(passports.asked(), "Cognos asked once: a refusal isn't retried at once").toBe(1);
      } finally {
        await wrongPassport.close().catch(() => undefined);
      }

      const wrongNamespace = client(Tm1Authenticator.camNamespace("analyst", "not-it", "LDAP"));
      try {
        const err = await wrongNamespace.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1AuthenticationException);
        expect((err as Tm1ApiException).kind).toBe("CredentialRejected");
      } finally {
        await wrongNamespace.close().catch(() => undefined);
      }

      // Cognos and TM1 11 know viewer; the TM1 v12 database has no such user
      const viewer = client(Tm1Authenticator.camNamespace("viewer", "viewerpw", "LDAP"));
      try {
        const err = await viewer.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1ForbiddenException);
        expect((err as Tm1ApiException).kind).toBe("Forbidden");
      } finally {
        await viewer.close().catch(() => undefined);
      }
    },
    WAIT,
  );
});

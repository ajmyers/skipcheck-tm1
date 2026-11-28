// SPDX-License-Identifier: Apache-2.0

import { KeycloakContainer, Tm1Database, Tm1EngineContainer, Tm1EngineSource } from "@skipcheck/tm1-testcontainers";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { ODataQuery, Tm1ApiException, Tm1AuthenticationException, Tm1Authenticator, Tm1Client, Tm1Dialect, Tm1ForbiddenException } from "../../src/index.js";
import { engineConfigured, engineReason, engineTest } from "./live.js";

const WAIT = 60_000;

/** Logging on to a TM1 v12 engine through its application (service-to-service) and through OAuth
 * 2.0 bearer tokens from Keycloak, including real error classification and refusal caching. The Java
 * client's `Tm1EngineAuthTm1Test`. */
describe("TM1 v12 engine authentication" + engineReason, () => {
  let keycloak: KeycloakContainer | undefined;
  let engine: Tm1EngineContainer | undefined;
  let database: Tm1Database | undefined;

  const client = (authenticator: Tm1Authenticator): Tm1Client =>
    Tm1Client.builder()
      .baseUrl(database!.getRestUrl())
      .dialect(Tm1Dialect.v12(database!.getInstance(), database!.getName()))
      .authenticator(authenticator)
      .build();

  /** A real JWT signed by Keycloak's master realm, not the realm TM1 trusts. */
  async function masterRealmToken(signal?: AbortSignal): Promise<string> {
    const form = new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: "admin", password: "admin" });
    const response = await fetch(keycloak!.getUrl() + "/realms/master/protocol/openid-connect/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form.toString(),
      signal: signal ?? AbortSignal.timeout(30_000),
    });
    const text = await response.text();
    expect(response.status, text).toBe(200);
    return (JSON.parse(text) as { access_token: string }).access_token;
  }

  beforeAll(async () => {
    if (!engineConfigured) return;
    keycloak = new KeycloakContainer();
    engine = new Tm1EngineContainer(Tm1EngineSource.discover()!).withUser("analyst", "Analysts").withLogin("analyst", "analystpw").withOAuth2(keycloak);
    await engine.start();
    database = engine.database(Tm1EngineContainer.DEFAULT_DATABASE);
  });

  afterAll(async () => {
    try {
      await engine?.stop();
    } finally {
      await keycloak?.stop();
    }
  });

  engineTest(
    "logs on through application credentials",
    async () => {
      const login = database!.login("analyst");
      const analyst = client(Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), login.getUser()));
      try {
        const user = await analyst.users.activeUser(ODataQuery.none().expand("Groups"));
        expect(user.Name?.toLowerCase()).toBe("analyst");
        expect((user.Groups ?? []).map((g) => g.Name)).toContain("Analysts");
      } finally {
        await analyst.close();
      }
    },
    WAIT,
  );

  engineTest(
    "refuses wrong application secret and unknown user",
    async () => {
      const login = database!.login("analyst");
      const wrongSecret = client(Tm1Authenticator.application(login.getClientId(), "wrong-secret", login.getUser()));
      try {
        const err = await wrongSecret.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1AuthenticationException);
        expect((err as Tm1ApiException).kind).toBe("CredentialRejected");
        expect((err as Tm1ApiException).error?.code).toBe("invalid_client");
        await expect(wrongSecret.users.activeUser()).rejects.toBeInstanceOf(Tm1AuthenticationException);
      } finally {
        await wrongSecret.close().catch(() => undefined);
      }

      // The gateway creates a session for the unknown user; the database refuses it (403)
      const unknownUser = client(Tm1Authenticator.application(login.getClientId(), login.getClientSecret(), "nobody_here"));
      try {
        const err = await unknownUser.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1ForbiddenException);
        expect((err as Tm1ApiException).kind).toBe("Forbidden");
      } finally {
        await unknownUser.close().catch(() => undefined);
      }
    },
    WAIT,
  );

  engineTest(
    "logs on with a bearer token",
    async () => {
      const oauth2 = database!.oauth2Login("analyst");
      const supplied = client(Tm1Authenticator.bearer(() => oauth2.token()));
      try {
        const user = await supplied.users.activeUser(ODataQuery.none().expand("Groups"));
        expect(user.Name?.toLowerCase()).toBe("analyst");
        expect((user.Groups ?? []).map((g) => g.Name)).toContain("Analysts");
      } finally {
        await supplied.close();
      }

      const fixed = client(Tm1Authenticator.bearer(await oauth2.token()));
      try {
        expect((await fixed.users.activeUser()).Name?.toLowerCase()).toBe("analyst");
      } finally {
        await fixed.close();
      }
    },
    WAIT,
  );

  engineTest(
    "refuses invalid and wrong realm bearer tokens once without retrying",
    async () => {
      let asked = 0;
      const wrongRealm = client(
        Tm1Authenticator.bearer((signal) => {
          asked++;
          return masterRealmToken(signal);
        }),
      );
      try {
        const err = await wrongRealm.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1AuthenticationException);
        expect((err as Tm1ApiException).kind).toBe("CredentialRejected");
        await expect(wrongRealm.users.activeUser()).rejects.toBeInstanceOf(Tm1AuthenticationException);
        expect(asked, "supplier asked once: a refusal isn't retried at once").toBe(1);
      } finally {
        await wrongRealm.close().catch(() => undefined);
      }

      const malformed = client(Tm1Authenticator.bearer("not.a.token"));
      try {
        const err = await malformed.users.activeUser().catch((e: unknown) => e);
        expect(err).toBeInstanceOf(Tm1AuthenticationException);
        expect((err as Tm1ApiException).kind).toBe("CredentialRejected");
      } finally {
        await malformed.close().catch(() => undefined);
      }
    },
    WAIT,
  );
});

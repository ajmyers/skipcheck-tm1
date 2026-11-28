// SPDX-License-Identifier: Apache-2.0

import { KeycloakContainer, Tm1Database, Tm1EngineContainer, Tm1EngineSource } from "@skipcheck/tm1-testcontainers";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { Tm1Authenticator, Tm1Client, Tm1Dialect } from "../../src/index.js";
import { engineConfigured, engineReason, engineTest } from "./live.js";

const WAIT = 60_000;

/** Logging on to a TM1 v12 engine through its application (service-to-service) and through OAuth
 * 2.0 bearer tokens from Keycloak. The Java client's `Tm1EngineAuthTm1Test`. */
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
        expect((await analyst.users.activeUser()).Name?.toLowerCase()).toBe("analyst");
      } finally {
        await analyst.close();
      }
    },
    WAIT,
  );

  engineTest(
    "logs on with a bearer token",
    async () => {
      const oauth2 = database!.oauth2Login("analyst");
      const analyst = client(Tm1Authenticator.bearer(() => oauth2.token()));
      try {
        expect((await analyst.users.activeUser()).Name?.toLowerCase()).toBe("analyst");
      } finally {
        await analyst.close();
      }
    },
    WAIT,
  );
});

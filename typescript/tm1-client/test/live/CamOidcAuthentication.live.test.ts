import { CognosContainer, CognosSource, KeycloakContainer, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { Tm1Authenticator, Tm1Client } from "../../src/index.js";
import { cognosConfigured, cognosReason, cognosTest } from "./live.js";

const WAIT = 60_000;

/** Logging on to a TM1 in CAM security through Cognos's OpenID Connect namespace, with Keycloak as
 * the provider: a password Cognos checks with Keycloak, and an ID token the application holds,
 * exchanged at Cognos for a passport. The Java client's `CamOidcAuthenticationTm1Test`. */
describe("CAM authentication through OpenID Connect on TM1" + cognosReason, () => {
  let keycloak: KeycloakContainer | undefined;
  let cognos: CognosContainer | undefined;
  let tm1: Tm1ServerContainer | undefined;

  const client = (authenticator: Tm1Authenticator): Tm1Client => Tm1Client.builder().baseUrl(tm1!.getRestUrl()).authenticator(authenticator).build();

  beforeAll(async () => {
    if (!cognosConfigured) return;
    keycloak = new KeycloakContainer();
    cognos = new CognosContainer(CognosSource.discover()!)
      .withUser("admin", "adminpw", "Admins")
      .withUser("viewer", "viewerpw", "Readers")
      .withKeycloak(keycloak);
    tm1 = new Tm1ServerContainer(Tm1Source.discover()!).withCamSecurity(cognos, CognosContainer.Namespace.OIDC, "admin");
    await tm1.start();
  });

  afterAll(async () => {
    await tm1?.stop();
    await cognos?.stop();
    await keycloak?.stop();
  });

  cognosTest(
    "logs on with a password Cognos checks with the provider",
    async () => {
      const admin = client(Tm1Authenticator.camNamespace("admin", "adminpw", "OIDC"));
      try {
        expect((await admin.users.activeUser()).Name).toBe('CAMID("OIDC:u:admin")');
      } finally {
        await admin.close();
      }
    },
    WAIT,
  );

  cognosTest(
    "exchanges an ID token for a passport",
    async () => {
      const viewer = client(Tm1Authenticator.cognosIdToken(cognos!.getUrl(), "OIDC", () => keycloak!.idToken("viewer", "viewerpw")));
      try {
        const groups = await viewer.users.groups('CAMID("OIDC:u:viewer")').toArray();
        expect(groups.map((g) => g.Name)).toContain('CAMID("OIDC:g:readers")');
      } finally {
        await viewer.close();
      }
    },
    WAIT,
  );
});

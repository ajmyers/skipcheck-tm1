import { CognosContainer, CognosSource, Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";
import { afterAll, beforeAll, describe, expect } from "vitest";
import { CognosPassports, Tm1AuthenticationException, Tm1Authenticator, Tm1Client, type Tm1Supplier } from "../../src/index.js";
import { cognosConfigured, cognosReason, cognosTest } from "./live.js";

const WAIT = 60_000;
const VIEWER = 'CAMID("LDAP:u:uid=viewer,ou=people")';

/** Logging on to a TM1 in CAM security through Cognos's LDAP namespace: with the password
 * (`CAMNamespace`), and with a passport the client gets from Cognos. Cognos takes a couple of minutes
 * to start, so the containers are shared by the file. The Java client's
 * `CamLdapAuthenticationTm1Test`. */
describe("CAM authentication through LDAP on TM1" + cognosReason, () => {
  let cognos: CognosContainer | undefined;
  let tm1: Tm1ServerContainer | undefined;
  let admin: Tm1Client | undefined;

  const client = (authenticator: Tm1Authenticator): Tm1Client => Tm1Client.builder().baseUrl(tm1!.getRestUrl()).authenticator(authenticator).build();

  /** The same passports, counting how often they're asked for. */
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
    if (!cognosConfigured) return;
    cognos = new CognosContainer(CognosSource.discover()!).withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers");
    tm1 = new Tm1ServerContainer(Tm1Source.discover()!).withCamSecurity(cognos, "admin");
    await tm1.start();
    admin = client(Tm1Authenticator.camNamespace("admin", "adminpw", "LDAP"));
  });

  afterAll(async () => {
    await admin?.close().catch(() => undefined);
    await tm1?.stop();
    await cognos?.stop();
  });

  cognosTest(
    "logs on with the password",
    async () => {
      const viewer = client(Tm1Authenticator.camNamespace("viewer", "viewerpw", "LDAP"));
      try {
        expect((await viewer.users.activeUser()).Name).toBe(VIEWER);
      } finally {
        await viewer.close();
      }
    },
    WAIT,
  );

  cognosTest(
    "logs on with a passport from Cognos, and a fresh one after TM1 drops the session",
    async () => {
      const passports = counting(CognosPassports.password(cognos!.getUrl(), "LDAP", "viewer", "viewerpw"));
      const viewer = client(Tm1Authenticator.camPassport(passports.supplier));
      try {
        expect((await viewer.users.activeUser()).Name).toBe(VIEWER);
        const first = await viewer.session.sessionId();
        await admin!.sessions.close(first);

        expect((await viewer.users.activeUser()).Name, "logged on again").toBe(VIEWER);
        expect(await viewer.session.sessionId()).not.toBe(first);
        expect(passports.asked(), "a fresh passport for the new login").toBe(2);
      } finally {
        await viewer.close();
      }
    },
    WAIT,
  );

  cognosTest(
    "refuses a wrong password once, without retrying",
    async () => {
      const passports = counting(CognosPassports.password(cognos!.getUrl(), "LDAP", "viewer", "wrong"));
      const wrong = client(Tm1Authenticator.camPassport(passports.supplier));
      try {
        await expect(wrong.users.activeUser()).rejects.toBeInstanceOf(Tm1AuthenticationException);
        await expect(wrong.users.activeUser()).rejects.toBeInstanceOf(Tm1AuthenticationException);
        expect(passports.asked(), "Cognos asked once: a refusal isn't retried at once").toBe(1);
      } finally {
        await wrong.close().catch(() => undefined);
      }
      const wrongNamespace = client(Tm1Authenticator.camNamespace("viewer", "wrong", "LDAP"));
      try {
        await expect(wrongNamespace.users.activeUser()).rejects.toBeInstanceOf(Tm1AuthenticationException);
      } finally {
        await wrongNamespace.close().catch(() => undefined);
      }
    },
    WAIT,
  );
});

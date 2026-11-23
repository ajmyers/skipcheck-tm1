import { afterAll, beforeAll, describe, expect } from "vitest";
import { Tm1ApiException, Tm1Session, type Tm1ErrorKind } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { scratch, sessions, RawTm1, ACTIVE_USER, failure } from "./raw-tm1.js";

/** The Java client's `Tm1SessionImpersonationTm1Test`. */
describe("Tm1Session impersonating on TM1" + liveReason, () => {
  let raw: RawTm1;
  let admin: string;
  let alice: string;
  const session = async (user: string): Promise<Tm1Session> => (await sessions()).impersonate(user).build();
  const kindOf = async (s: Tm1Session): Promise<Tm1ErrorKind> => {
    const e = await failure(s.execute(ACTIVE_USER));
    expect(e).toBeInstanceOf(Tm1ApiException);
    return (e as Tm1ApiException).kind;
  };

  beforeAll(async () => {
    if (!Live.configured) return;
    raw = await RawTm1.connect();
    admin = await raw.login();
    alice = scratch("zz_alice_");
    await raw.createUser(admin, alice, "a");
  });

  afterAll(async () => {
    if (!raw) return;
    await raw.deleteUser(admin, alice);
    await raw.close(admin);
  });

  tm1Test("runs as the impersonated user", async () => {
    const s = await session(alice.toUpperCase());
    try {
      expect((await s.execute(ACTIVE_USER)).Name).toBe(alice);
    } finally {
      await s.close();
    }
  });

  tm1Test("runs as a user whose name is outside ASCII", async () => {
    for (const name of [alice + "_ë", alice + "_日本"]) {
      await raw.createUser(admin, name, "a");
      const s = await session(name);
      try {
        expect((await s.execute(ACTIVE_USER)).Name).toBe(name);
      } finally {
        await s.close();
        await raw.deleteUser(admin, name);
      }
    }
  });

  tm1Test("reports an unknown user", async () => {
    expect(await kindOf(await session("zz_nobody"))).toBe("UnknownUser");
  });

  tm1Test("reports that administrators can't be impersonated", async () => {
    expect(await kindOf(await session("Admin"))).toBe("ImpersonationRefused");
  });

  tm1Test("reports a disabled user as refused credentials", async () => {
    await raw.request("PATCH", `Users('${alice}')`).withCookie(admin).withBody('{"Enabled":false}').send();
    try {
      expect(await kindOf(await session(alice))).toBe("CredentialRejected");
    } finally {
      await raw.request("PATCH", `Users('${alice}')`).withCookie(admin).withBody('{"Enabled":true}').send();
    }
  });

  /** Each refused login leaves a session on TM1; a remembered refusal doesn't add more. */
  tm1Test("doesn't log in again while a refusal is remembered", async () => {
    const s = await session("zz_nobody");
    expect(await kindOf(s)).toBe("UnknownUser");
    const after = await raw.allSessionCount(admin);
    for (let i = 0; i < 5; i++) expect(await kindOf(s)).toBe("UnknownUser");
    expect(await raw.allSessionCount(admin)).toBe(after);
  });
});

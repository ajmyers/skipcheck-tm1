// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, describe, expect } from "vitest";
import { Tm1ApiException, Tm1Authenticator, Tm1Identity, Tm1Session, Tm1SessionPool } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { sleep, sessions, RawTm1, uniqueContext, ACTIVE_USER, failure } from "./raw-tm1.js";

/** The Java client's `Tm1SessionPoolTm1Test`. */
describe("Tm1SessionPool on TM1" + liveReason, () => {
  let raw: RawTm1;
  let admin: string;
  let alice: string;
  let bob: string;
  let context: string;
  let pool: Tm1SessionPool;
  const name = async (s: Tm1Session): Promise<string> => (await s.execute(ACTIVE_USER)).Name;

  beforeAll(async () => {
    if (!Live.configured) return;
    raw = await RawTm1.connect();
    admin = await raw.login();
    const run = randomUUID().replace(/-/g, "").slice(0, 6);
    alice = "zz_alice_" + run;
    bob = "zz_bob_" + run;
    await raw.createUser(admin, alice, "a");
    await raw.createUser(admin, bob, "b");
  });

  afterAll(async () => {
    if (!raw) return;
    await raw.deleteUser(admin, alice);
    await raw.deleteUser(admin, bob);
    await raw.close(admin);
  });

  const start = async (): Promise<void> => {
    context = uniqueContext();
    pool = Tm1SessionPool.builder((await sessions()).sessionContext(context))
      .idleTimeout(1_000)
      .closeGrace(0)
      .sweepInterval(3_600_000)
      .build();
  };

  afterEach(async () => {
    await pool?.close();
  });

  const identity = async (user: string, password: string): Promise<Tm1Identity> =>
    Live.isEngine ? Tm1Identity.withCredentials(user, await Live.authenticator(user, password)) : Tm1Identity.impersonating(user);

  tm1Test("runs each identity as itself", async () => {
    await start();
    const targetUser = (await Live.target()).user.toLowerCase();
    if (Live.isEngine) {
      expect(await name(pool.session(await identity(alice, "a")))).toBe(alice);
      expect(await name(pool.session(await identity(bob, "b")))).toBe(bob);
      expect((await name(pool.session())).toLowerCase()).toBe(targetUser);
      expect(pool.stats().held).toBe(3);
      return;
    }
    expect(await name(pool.session(Tm1Identity.impersonating(alice)))).toBe(alice);
    expect(await name(pool.session(Tm1Identity.impersonating(bob)))).toBe(bob);
    expect(await name(pool.session(Tm1Identity.withCredentials(bob, Tm1Authenticator.basic(bob, "b"))))).toBe(bob);
    expect((await name(pool.session())).toLowerCase()).toBe(targetUser);
    expect(pool.stats().held).toBe(4);
  });

  tm1Test("closes evicted sessions on TM1", async () => {
    await start();
    const s = pool.session(await identity(alice, "a"));
    expect(await name(s)).toBe(alice);
    const id = await s.sessionId();
    await sleep(1_100);
    pool.evictExpired();
    expect(await raw.awaitGone(admin, id)).toBe(true);
    for (let i = 0; i < 100 && pool.stats().closed !== 1; i++) await sleep(50);
    expect(pool.stats().closed).toBe(1);
  });

  tm1Test("leaves no session behind when closed", async () => {
    await start();
    await name(pool.session(await identity(alice, "a")));
    await name(pool.session(await identity(bob, "b")));
    await pool.close();
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  /** Asking for an administrator fails; nothing runs as the pool's own user instead. */
  tm1Test("never falls back to another identity", async (context) => {
    if (Live.isEngine) return context.skip("TM1 v12 does not support TM1-Impersonate");
    await start();
    const e = await failure(pool.session(Tm1Identity.impersonating("Admin")).execute(ACTIVE_USER));
    expect(e).toBeInstanceOf(Tm1ApiException);
    expect((e as Tm1ApiException).kind).toBe("ImpersonationRefused");
  });
});

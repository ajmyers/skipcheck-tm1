import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  ODataPath,
  ODataQuery,
  Tm1Authenticator,
  Tm1Clock,
  Tm1Identity,
  Tm1Request,
  Tm1Session,
  Tm1SessionClosedException,
  Tm1SessionPool,
  type Tm1SessionPoolBuilder,
} from "../../src/index.js";
import { eventually, ManualClock, ScriptedTm1 } from "../support/scripted-tm1.js";

const SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none());
const MINUTE = 60_000;

/** The Java client's `Tm1SessionPoolTest`: one session per identity, evicted when idle, old or
 * least recently used, and closed after a grace period, on a clock the test moves.
 * (`observesEvictionsWithTheirReason` is Micrometer's, which TypeScript doesn't have: the counts in
 * `stats()` stand for it.) */
describe("Tm1SessionPool", () => {
  let tm1: ScriptedTm1;
  let time: ManualClock;
  let pool: Tm1SessionPool;

  const build = (settings: (b: Tm1SessionPoolBuilder) => Tm1SessionPoolBuilder): Tm1SessionPool =>
    settings(Tm1SessionPool.builder(tm1.builder().basicAuth("admin", "apple")).clock(time)).build();

  beforeEach(() => {
    tm1 = new ScriptedTm1().on("GET Server", () => ScriptedTm1.json(200, "{}"));
    time = new ManualClock();
    pool = build((b) => b);
  });

  afterEach(async () => {
    await pool.close();
  });

  it("keeps one session per identity, matching names as TM1 does", () => {
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    expect(pool.session(Tm1Identity.impersonating("ALICE"))).toBe(alice);
    expect(pool.session(Tm1Identity.impersonating("al ice"))).toBe(alice);
    expect(pool.session(Tm1Identity.impersonating("bob"))).not.toBe(alice);
    expect(pool.session()).not.toBe(alice);
    expect(pool.stats().held).toBe(3);
    expect(pool.stats().created).toBe(3);
    expect(tm1.requests(), "getting a session sends nothing").toHaveLength(0);
  });

  it("logs each identity in as itself", async () => {
    await pool.session(Tm1Identity.impersonating("alice")).execute(SERVER);
    await pool.session().execute(SERVER);
    await pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c"))).execute(SERVER);

    const logins = tm1.requests(ScriptedTm1.LOGIN);
    expect(logins.map((r) => r.header("TM1-Impersonate"))).toEqual(["alice", undefined, undefined]);
    expect(logins.map((r) => r.authorization)).toEqual(["Basic YWRtaW46YXBwbGU=", "Basic YWRtaW46YXBwbGU=", "Basic Y2Fyb2w6Yw=="]);
  });

  it("logs a user's own credentials in without the pool's impersonation", async () => {
    await pool.close();
    pool = Tm1SessionPool.builder(tm1.builder().basicAuth("admin", "apple").impersonate("someone")).clock(time).build();
    await pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c"))).execute(SERVER);
    await pool.session().execute(SERVER);
    expect(tm1.requests(ScriptedTm1.LOGIN).map((r) => r.header("TM1-Impersonate"))).toEqual([undefined, "someone"]);
  });

  it("evicts an idle session and closes it after the grace period", async () => {
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    await alice.execute(SERVER);

    time.advance(10 * MINUTE);
    const fresh = pool.session(Tm1Identity.impersonating("alice"));
    expect(fresh).not.toBe(alice);
    expect(pool.stats().evictedIdle).toBe(1);
    expect(alice.isClosed(), "still open during the grace period").toBe(false);
    expect(pool.stats().closing).toBe(1);

    time.advance(30_000);
    expect(alice.isClosed()).toBe(true);
    await eventually(() => pool.stats().closed === 1, "for the close");
    expect(tm1.requests(ScriptedTm1.CLOSE)).toHaveLength(1);
    expect(pool.stats().closing).toBe(0);
  });

  it("keeps a session that's in use", () => {
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    for (let i = 0; i < 5; i++) {
      time.advance(9 * MINUTE);
      expect(pool.session(Tm1Identity.impersonating("alice"))).toBe(alice);
    }
  });

  it("evicts a session past its maximum age even if in use", async () => {
    await pool.close();
    pool = build((b) => b.maxAge(30 * MINUTE));
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    for (let i = 0; i < 3; i++) {
      time.advance(9 * MINUTE);
      pool.session(Tm1Identity.impersonating("alice"));
    }
    time.advance(4 * MINUTE);
    expect(pool.session(Tm1Identity.impersonating("alice"))).not.toBe(alice);
    expect(pool.stats().evictedAge).toBe(1);
  });

  it("evicts the least recently used session when full", async () => {
    await pool.close();
    pool = build((b) => b.maxSize(2));
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    const bob = pool.session(Tm1Identity.impersonating("bob"));
    pool.session(Tm1Identity.impersonating("alice"));
    pool.session(Tm1Identity.impersonating("carol"));

    expect(pool.stats().held).toBe(2);
    expect(pool.stats().evictedSize).toBe(1);
    expect(pool.session(Tm1Identity.impersonating("alice"))).toBe(alice);
    expect(pool.session(Tm1Identity.impersonating("bob"))).not.toBe(bob);
  });

  it("sweeps expired sessions on its own", async () => {
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    await alice.execute(SERVER);
    time.advance(11 * MINUTE);
    expect(pool.stats().held).toBe(0);
    time.advance(30_000);
    expect(alice.isClosed()).toBe(true);
  });

  it("evicts on demand, and closes at once with no grace period", async () => {
    await pool.close();
    pool = build((b) =>
      b
        .idleTimeout(1_000)
        .closeGrace(0)
        .sweepInterval(60 * MINUTE),
    );
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    await alice.execute(SERVER);
    time.advance(1_000);
    pool.evictExpired();
    expect(alice.isClosed()).toBe(true);
    await eventually(() => pool.stats().closed === 1, "for the close");
  });

  it("closes every session at once, including those in their grace period", async () => {
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    await alice.execute(SERVER);
    time.advance(10 * MINUTE);
    const fresh = pool.session(Tm1Identity.impersonating("alice"));
    await fresh.execute(SERVER);

    await pool.close();
    expect(alice.isClosed()).toBe(true);
    expect(fresh.isClosed()).toBe(true);
    expect(tm1.requests(ScriptedTm1.CLOSE)).toHaveLength(2);
    expect(pool.stats().closed).toBe(2);
    expect(pool.close(), "every call returns the same result").toBe(pool.close());
    expect(() => pool.session()).toThrow(Tm1SessionClosedException);
  });

  it("refuses identities TM1 would misread", () => {
    expect(() => Tm1Identity.impersonating(" ")).toThrow(RangeError);
    expect(() => Tm1Identity.withCredentials("", Tm1Authenticator.basic("x", "y"))).toThrow(RangeError);
    expect(Tm1Identity.impersonating("Alice").equals(Tm1Identity.impersonating("alice"))).toBe(true);
    expect(Tm1Identity.impersonating("Alice").equals(Tm1Identity.withCredentials("alice", Tm1Authenticator.basic("alice", "a")))).toBe(false);
    expect(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "secret")).toString()).not.toContain("secret");
    expect(Tm1Identity.serviceAccount().kind()).toBe(Tm1Identity.Kind.ServiceAccount);
    expect(Tm1Identity.impersonating("Alice").name()).toBe("Alice");
    expect(Tm1Identity.impersonating("Alice").authenticator()).toBeUndefined();
    expect(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c")).authenticator()!.describe()).toBe("Basic carol");
  });

  it("refuses settings out of range", () => {
    const builder = Tm1SessionPool.builder(tm1.builder().basicAuth("admin", "apple"));
    expect(() => builder.idleTimeout(0)).toThrow(RangeError);
    expect(() => builder.maxAge(-1_000)).toThrow(RangeError);
    expect(() => builder.maxSize(0)).toThrow(RangeError);
    expect(() => builder.closeGrace(-1_000)).toThrow(RangeError);
    expect(() => builder.sweepInterval(0)).toThrow(RangeError);
    builder.closeGrace(0);
  });

  it("builds from a copy of the settings, sharing one $metadata fetch", async () => {
    const settings = tm1.builder().basicAuth("admin", "apple").checkCapabilities(true);
    tm1
      .on("GET Server?$select=ProductVersion", () => ScriptedTm1.json(200, '{"ProductVersion":"11.8.03500.6"}'))
      .on("GET $metadata", () =>
        ScriptedTm1.xml(
          '<?xml version="1.0"?><edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices><Schema Namespace="ibm.tm1.api.v1" xmlns="http://docs.oasis-open.org/odata/ns/edm"/></edmx:DataServices></edmx:Edmx>',
        ),
      );
    const shared = Tm1SessionPool.builder(settings).clock(time).build();
    settings.impersonate("changed later");
    try {
      const first = await shared.session().capabilities();
      expect(await shared.session(Tm1Identity.withCredentials("again", Tm1Authenticator.basic("admin", "apple"))).capabilities()).toBe(first);
      expect(tm1.requests("GET $metadata")).toHaveLength(1);
      expect(tm1.requests(ScriptedTm1.LOGIN).map((r) => r.header("TM1-Impersonate"))).toEqual([undefined, undefined]);
    } finally {
      await shared.close();
    }
  });

  it("runs on the real clock unless given one, without keeping Node alive", async () => {
    const real = Tm1SessionPool.builder(Tm1Session.builder().baseUrl(ScriptedTm1.ROOT).basicAuth("admin", "apple").transport(tm1)).build();
    expect(real.session()).toBe(real.session());
    expect(Tm1Clock.system.now()).toBeGreaterThan(0);
    await real.close();
  });
});

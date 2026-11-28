// SPDX-License-Identifier: Apache-2.0

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  Json,
  ODataPath,
  ODataQuery,
  Tm1Authenticator,
  Tm1Clock,
  Tm1Dialect,
  Tm1Identity,
  Tm1Request,
  Tm1Session,
  Tm1SessionClosedException,
  Tm1SessionPool,
  type Tm1SessionPoolBuilder,
} from "../../src/index.js";
import { eventually, ManualClock, Pending, ScriptedTm1, settle } from "../support/scripted-tm1.js";

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

  it("gives different credentials for the same name their own session", async () => {
    const carol = pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c")));
    expect(pool.session(Tm1Identity.withCredentials("Carol", Tm1Authenticator.basic("carol", "c"))), "the same credentials, made again").toBe(carol);
    await carol.execute(SERVER);

    const other = pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "wrong")));
    expect(other, "never the session someone else's credentials opened").not.toBe(carol);
    await other.execute(SERVER);
    expect(
      tm1.requests(ScriptedTm1.LOGIN).map((r) => r.authorization),
      "each logs in with what it was given",
    ).toEqual(["Basic Y2Fyb2w6Yw==", "Basic Y2Fyb2w6d3Jvbmc="]);
    expect(pool.stats().held).toBe(2);
    expect(pool.session(Tm1Identity.withCredentials("carol", Tm1Authenticator.basic("carol", "c"))), "and the first is still its own").toBe(carol);
  });

  it("shares a session for the same authenticator when its credentials can't be compared", () => {
    const passport = Tm1Authenticator.camPassport(() => "p1");
    const dave = pool.session(Tm1Identity.withCredentials("dave", passport));
    expect(pool.session(Tm1Identity.withCredentials("dave", passport))).toBe(dave);
    expect(
      pool.session(
        Tm1Identity.withCredentials(
          "dave",
          Tm1Authenticator.camPassport(() => "p1"),
        ),
      ),
    ).not.toBe(dave);
    expect(Tm1Authenticator.camPassport("p1").equals(Tm1Authenticator.camPassport("p1"))).toBe(true);
    expect(Tm1Authenticator.camPassport("p1").equals(Tm1Authenticator.camPassport("p2"))).toBe(false);
    const logon = Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "dave", "d");
    expect(logon.equals(Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "dave", "d"))).toBe(true);
    expect(logon.equals(Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "dave", "other"))).toBe(false);
  });

  it("evicts each of a name's sessions on its own", async () => {
    await pool.close();
    pool = build((b) => b.maxSize(2));
    const right = Tm1Identity.withCredentials("carol", Tm1Authenticator.camPassport("p1"));
    const renewed = Tm1Identity.withCredentials("carol", Tm1Authenticator.camPassport("p2"));
    const first = pool.session(right);
    const second = pool.session(renewed);
    pool.session(Tm1Identity.impersonating("bob"));

    expect(pool.stats().evictedSize, "the least recently used of the two").toBe(1);
    expect(pool.session(renewed)).toBe(second);
    expect(pool.session(right), "a new one for the passport whose session was evicted").not.toBe(first);
    expect(right.equals(renewed)).toBe(false);
    expect(right.equals(Tm1Identity.withCredentials("Carol", Tm1Authenticator.camPassport("p1")))).toBe(true);
  });

  it("keeps a session while a request is running on it", async () => {
    const answer = new Pending();
    tm1.on("GET Server", answer.reply);
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    const result = alice.execute(SERVER);
    await settle();

    time.advance(25 * MINUTE);
    expect(pool.stats().evictedIdle, "a running request is use").toBe(0);
    expect(alice.isClosed()).toBe(false);

    answer.answer(ScriptedTm1.json(200, "{}"));
    expect(await result).toEqual({});
    time.advance(12 * MINUTE);
    expect(pool.stats().evictedIdle, "idle once it has finished").toBe(1);
    expect(alice.isClosed()).toBe(true);
  });

  it("closes an evicted session only once its requests have finished", async () => {
    await pool.close();
    pool = build((b) => b.maxAge(30 * MINUTE));
    const answer = new Pending();
    tm1.on("GET Server", answer.reply);
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    const result = alice.execute(SERVER);
    await settle();

    time.advance(40 * MINUTE);
    expect(pool.stats().evictedAge, "out of the pool at its age").toBe(1);
    expect(alice.isClosed(), "but open for the request still running").toBe(false);

    answer.answer(ScriptedTm1.json(200, "{}"));
    expect(await result).toEqual({});
    time.advance(31_000);
    expect(alice.isClosed()).toBe(true);
  });

  it("counts a stream as running until it is finished or stopped", async () => {
    tm1
      .on("GET Cubes", () => ScriptedTm1.json(200, '{"value":[{"Name":"a"}],"@odata.nextLink":"Cubes?$skiptoken=2"}'))
      .on("GET Cubes?$skiptoken=2", () => ScriptedTm1.json(200, '{"value":[{"Name":"b"}]}'));
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    const cubes = alice.stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Cubes"), ODataQuery.none()))[Symbol.asyncIterator]();
    expect((await cubes.next()).value).toEqual({ Name: "a" });

    // The caller is slow with the first page: the next is still to be asked for
    time.advance(25 * MINUTE);
    expect(pool.stats().evictedIdle, "a stream being read is use").toBe(0);
    expect((await cubes.next()).value).toEqual({ Name: "b" });
    expect((await cubes.next()).done).toBe(true);

    time.advance(12 * MINUTE);
    expect(pool.stats().evictedIdle, "idle once it has finished").toBe(1);

    const again = pool.session(Tm1Identity.impersonating("alice"));
    for await (const cube of again.stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Cubes"), ODataQuery.none()))) {
      expect(cube.Name).toBe("a");
      break;
    }
    time.advance(12 * MINUTE);
    expect(pool.stats().evictedIdle, "and once it has been stopped").toBe(2);
  });

  it("counts an async request as running while it is polled", async () => {
    const run = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), null, Json);
    const done = new Pending();
    tm1
      .on("POST Processes('p')/tm1.ExecuteWithReturn", () => ScriptedTm1.bytes(202, new Uint8Array(), "text/plain", { location: "../_async('a1')" }))
      .onEach("GET _async('a1')", () => ScriptedTm1.status(202), done.reply);
    const alice = pool.session(Tm1Identity.impersonating("alice"));
    // Between its polls no request is being sent: a minute apart, so the test's clock decides
    const cancel = new AbortController();
    const result = alice.withAsync(60_000).execute(run, { signal: cancel.signal });
    await eventually(() => tm1.requests("GET _async('a1')").length === 1, "for the first poll");
    await settle();

    time.advance(25 * MINUTE);
    expect(pool.stats().evictedIdle, "waiting to poll is use").toBe(0);
    expect(alice.isClosed()).toBe(false);
    cancel.abort();
    await expect(result).rejects.toBeDefined();
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

  it("pools per-user application sessions", async () => {
    const dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
    const authUrl = "POST http://tm1.test/tm1/auth/v1/session";
    const v12 = new ScriptedTm1()
      .onEach(
        authUrl,
        () => ScriptedTm1.status(201, "TM1SessionId=u1; Path=/; HttpOnly"),
        () => ScriptedTm1.status(201, "TM1SessionId=u2; Path=/; HttpOnly"),
      )
      .on("GET " + dbRoot + "ActiveSession?$select=ID", () => ScriptedTm1.json(200, '{"ID":1}'))
      .on("GET " + dbRoot + "Server", () => ScriptedTm1.json(200, "{}"))
      .on("POST http://tm1.test/tm1/api/v1/ActiveSession/tm1s.Close", () => ScriptedTm1.status(204));

    const v12Pool = Tm1SessionPool.builder(
      Tm1Session.builder()
        .dialect(Tm1Dialect.v12("sales"))
        .address("http://tm1.test")
        .authenticator(Tm1Authenticator.application("svc", "secret", "default-user"))
        .transport(v12),
    )
      .clock(time)
      .build();
    try {
      const alice = v12Pool.session(Tm1Identity.withCredentials("alice", Tm1Authenticator.application("svc", "secret", "alice")));
      expect(v12Pool.session(Tm1Identity.withCredentials("ALICE", Tm1Authenticator.application("svc", "secret", "alice")))).toBe(alice);
      const bob = v12Pool.session(Tm1Identity.withCredentials("bob", Tm1Authenticator.application("svc", "secret", "bob")));
      expect(bob).not.toBe(alice);
      expect(() => v12Pool.session(Tm1Identity.impersonating("charlie"))).toThrow(Error);

      await alice.execute(SERVER);
      await bob.execute(SERVER);
      expect(v12.requests(authUrl).map((r) => r.json)).toEqual([{ User: "alice" }, { User: "bob" }]);
    } finally {
      await v12Pool.close();
    }
  });
});

import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { Agent as HttpAgent, request as httpRequest, type IncomingMessage } from "node:http";
import { Agent as HttpsAgent, request as httpsRequest } from "node:https";
import type { Socket } from "node:net";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  Json,
  ODataPath,
  ODataQuery,
  Tm1ApiException,
  Tm1Authenticator,
  Tm1Capabilities,
  Tm1Capability,
  Tm1Exception,
  Tm1Identity,
  Tm1NotFoundException,
  Tm1Request,
  Tm1Session,
  Tm1SessionPool,
  Tm1UnsupportedOperationException,
  Tm1Version,
  type Tm1ErrorKind,
  type Tm1SessionBuilder,
} from "../../src/index.js";
import { Repo } from "../support/repo.js";
import { Live, liveReason, tm1Test } from "./live.js";

// The Java client's session, pool, capability, async, batch and client tests against a real TM1
// (Tm1SessionTm1Test, Tm1SessionImpersonationTm1Test, Tm1SessionPoolTm1Test, Tm1CapabilitiesTm1Test,
// Tm1SessionAsyncTm1Test, Tm1BatchTm1Test, Tm1ClientTm1Test), and its checks of TM1's own behaviour
// that the session relies on (Tm1SessionBehaviorTest, Tm1ImpersonationBehaviorTest), as the .NET
// client's SessionLiveTests.

const WAIT = 30_000;
const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
const scratch = (prefix = "zz_session_"): string => prefix + randomUUID().replace(/-/g, "").slice(0, 8);
const basic = (user: string, password: string): string => "Basic " + Buffer.from(`${user}:${password}`, "utf8").toString("base64");

/** Settings for a session of the TM1 under test, logging in as `Live.client()` does. */
async function sessions(): Promise<Tm1SessionBuilder> {
  const t = await Live.target();
  // Node's fetch takes no per-request certificate options: TM1_VERIFY=false turns checking off for the run
  if (process.env["TM1_VERIFY"] === "false") process.env["NODE_TLS_REJECT_UNAUTHORIZED"] = "0";
  return Tm1Session.builder()
    .baseUrl(t.url)
    .authenticator(t.namespace ? Tm1Authenticator.camNamespace(t.user, t.password, t.namespace) : Tm1Authenticator.basic(t.user, t.password));
}

/** A response: status, headers (all `Set-Cookie`s apart) and body. */
interface RawResponse {
  status: number;
  headers: Headers;
  cookies: string[];
  body: string;
}

/** One request: method, path relative to the service root, and optional credentials, cookie,
 * headers and JSON body. */
class RawRequest {
  readonly headers: Record<string, string> = {};
  body: string | undefined;

  constructor(
    private readonly tm1: RawTm1,
    readonly method: string,
    readonly path: string,
  ) {}

  withBasic(): this {
    return this.withHeader("Authorization", this.tm1.basic);
  }

  withCookie(cookie: string): this {
    return this.withHeader("Cookie", cookie);
  }

  withHeader(name: string, value: string): this {
    this.headers[name] = value;
    return this;
  }

  withBody(json: string): this {
    this.body = json;
    return this;
  }

  send(): Promise<RawResponse> {
    return this.tm1.send(this);
  }
}

/** TM1 over plain HTTP, as the Java tests' `RawTm1`: for setting up, checking what the client left
 * on TM1, and checking TM1's own behaviour, without going through the client. */
class RawTm1 {
  private constructor(
    readonly root: string,
    readonly user: string,
    readonly password: string,
  ) {}

  get basic(): string {
    return basic(this.user, this.password);
  }

  static async connect(): Promise<RawTm1> {
    await sessions();
    const t = await Live.target();
    return new RawTm1(t.url, t.user, t.password);
  }

  request(method: string, path: string): RawRequest {
    return new RawRequest(this, method, path);
  }

  /** Sends a request; a GET or DELETE on a kept-alive connection TM1 closed gets one more try. */
  async send(request: RawRequest): Promise<RawResponse> {
    try {
      return await this.sendOnce(request);
    } catch (e) {
      if (request.method !== "GET" && request.method !== "DELETE") throw e;
      return this.sendOnce(request);
    }
  }

  private async sendOnce(request: RawRequest): Promise<RawResponse> {
    const headers = new Headers(request.headers);
    if (request.body !== undefined) headers.set("Content-Type", "application/json");
    const response = await fetch(this.root + request.path, { method: request.method, headers, body: request.body, redirect: "manual", signal: AbortSignal.timeout(60_000) });
    return { status: response.status, headers: response.headers, cookies: response.headers.getSetCookie(), body: await response.text() };
  }

  /** The `TM1SessionId` cookie a response sets, as `name=value`. */
  static cookieOf(response: RawResponse): string | undefined {
    return response.cookies.find((c) => c.startsWith("TM1SessionId="))?.split(";")[0];
  }

  static json(response: RawResponse): any {
    return JSON.parse(response.body);
  }

  /** Logs in with Basic credentials and no cookie; the new session's cookie, as `name=value`. */
  async login(): Promise<string> {
    const cookie = RawTm1.cookieOf(await this.request("GET", "ActiveSession?$select=ID").withBasic().send());
    if (!cookie) throw new Error("TM1 set no session cookie");
    return cookie;
  }

  async sessionId(cookie: string): Promise<number> {
    return RawTm1.json(await this.request("GET", "ActiveSession?$select=ID").withCookie(cookie).send())["ID"] as number;
  }

  async close(cookie: string): Promise<void> {
    await this.request("POST", "ActiveSession/tm1.Close").withCookie(cookie).withBody("{}").send();
  }

  /** Kills another session by ID from `admin`'s session. */
  async kill(admin: string, id: number): Promise<number> {
    return (await this.request("POST", `Sessions(${id})/tm1.Close`).withCookie(admin).withBody("{}").send()).status;
  }

  /** Waits until session `id` is no longer listed (TM1 takes a few seconds). */
  async awaitGone(admin: string, id: number, timeout = WAIT): Promise<boolean> {
    const deadline = Date.now() + timeout;
    while ((await this.request("GET", `Sessions(${id})?$select=ID`).withCookie(admin).send()).status === 200) {
      if (Date.now() > deadline) return false;
      await sleep(250);
    }
    return true;
  }

  /** How many sessions labelled `context` TM1 has open: leak checks count a label unique to the
   * test, since the server is shared. */
  async sessionCount(admin: string, context: string): Promise<number> {
    const all = RawTm1.json(await this.request("GET", "Sessions?$select=ID,Context").withCookie(admin).send())["value"] as { Context?: string }[];
    return all.filter((s) => s.Context === context).length;
  }

  /** Waits until TM1 lists `expected` sessions labelled `context`; the last count seen. */
  async awaitSessionCount(admin: string, context: string, expected: number, timeout = WAIT): Promise<number> {
    const deadline = Date.now() + timeout;
    let count = await this.sessionCount(admin, context);
    while (count !== expected && Date.now() < deadline) {
      await sleep(250);
      count = await this.sessionCount(admin, context);
    }
    return count;
  }

  /** How many sessions TM1 has open, those with no user included. */
  async allSessionCount(admin: string): Promise<number> {
    return (RawTm1.json(await this.request("GET", "Sessions?$select=ID").withCookie(admin).send())["value"] as unknown[]).length;
  }

  /** Creates a TM1 user with no groups (so not an administrator). */
  async createUser(admin: string, name: string, password: string): Promise<void> {
    const created = await this.request("POST", "Users").withCookie(admin).withBody(JSON.stringify({ Name: name, Password: password })).send();
    if (created.status !== 201) throw new Error(`Couldn't create user ${name}: ${created.status} ${created.body}`);
  }

  async deleteUser(admin: string, name: string): Promise<void> {
    await this.request("DELETE", `Users('${encodeURIComponent(name.replace(/'/g, "''"))}')`).withCookie(admin).send();
  }
}

/** A label unique to one test, for `sessionCount`. */
const uniqueContext = (): string => "test-" + randomUUID();

const ACTIVE_USER = Tm1Request.get<{ Name: string }>(ODataPath.of("ActiveUser"), ODataQuery.none().select("Name"));
const SERVER = Tm1Request.get<{ Name: string; ProductVersion: string }>(ODataPath.of("Server"), ODataQuery.none().select("Name", "ProductVersion"));

/** What a promise failed with; fails the test if it didn't. */
async function failure(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    () => expect.fail("expected a failure"),
    (e: unknown) => e,
  );
}

/** The Java client's `Tm1SessionTm1Test`: the session against a real TM1, leaving no session behind. */
describe("Tm1Session on TM1" + liveReason, () => {
  let raw: RawTm1;
  let admin: string;
  let context: string;
  const session = async (): Promise<Tm1Session> => (await sessions()).sessionContext(context).build();

  beforeAll(async () => {
    if (!Live.configured) return;
    raw = await RawTm1.connect();
    admin = await raw.login();
  });

  afterAll(async () => {
    if (raw) await raw.close(admin);
  });

  beforeEach(() => {
    context = uniqueContext();
  });

  tm1Test("reads the server and closes its session, leaving none behind", async () => {
    const s = await session();
    const tm1 = await s.execute(SERVER);
    expect(tm1.ProductVersion).toBe(RawTm1.json(await raw.request("GET", "Server?$select=ProductVersion").withCookie(admin).send())["ProductVersion"]);
    const id = await s.sessionId();
    expect(await raw.sessionCount(admin, context)).toBe(1);
    await s.close();
    expect(await raw.awaitGone(admin, id)).toBe(true);
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  tm1Test("shares one login between concurrent first requests", async () => {
    const s = await session();
    const results = await Promise.all(Array.from({ length: 8 }, () => s.execute(SERVER)));
    expect(results).toHaveLength(8);
    expect(await raw.sessionCount(admin, context)).toBe(1);
    await s.close();
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  tm1Test("leaves no session when close races the first request", async () => {
    const s = await session();
    const first = s.execute(SERVER).catch(() => undefined);
    await s.close();
    // Either outcome is fine; what matters is what's left on TM1
    await first;
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  /** The replay the client relies on, end to end: the session is killed from another session, then a
   * POST through the client logs in again and creates the dimension exactly once, and the session
   * TM1 opened for the 401 is the one logged in, so nothing is left behind. */
  tm1Test("logs in again after its session is killed and runs the POST once", async () => {
    const s = await session();
    const name = scratch();
    try {
      const first = await s.sessionId();
      expect(await raw.kill(admin, first)).toBe(204);
      await s.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: name, Hierarchies: [{ Name: name }] }));
      expect((await raw.request("GET", `Dimensions/$count?$filter=Name%20eq%20'${name}'`).withCookie(admin).send()).body).toBe("1");
      expect(await s.sessionId()).not.toBe(first);
    } finally {
      await raw.request("DELETE", `Dimensions('${name}')`).withCookie(admin).send();
      await s.close();
    }
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  tm1Test("labels the session with its context", async () => {
    const s = (await sessions()).sessionContext("tm1-client test").build();
    try {
      const active = await s.execute(Tm1Request.get<{ Context: string }>(ODataPath.of("ActiveSession"), ODataQuery.none().select("Context")));
      expect(active.Context).toBe("tm1-client test");
    } finally {
      await s.close();
    }
  });

  /** TM1 reads `!sandbox` on any request: a sandbox that exists works, one that doesn't fails. */
  tm1Test("runs requests in the view's sandbox", async () => {
    const s = await session();
    const sandbox = scratch();
    try {
      await s.execute(Tm1Request.post(ODataPath.of("Sandboxes"), { Name: sandbox }));
      await s.withSandbox(sandbox).execute(SERVER);
      expect(await failure(s.withSandbox(sandbox + "_missing").execute(SERVER))).toBeInstanceOf(Tm1ApiException);
    } finally {
      await s.execute(Tm1Request.delete(ODataPath.of("Sandboxes").key(sandbox))).catch((e: unknown) => {
        if (!(e instanceof Tm1Exception)) throw e;
      });
      await s.close();
    }
  });

  tm1Test("streams a collection", async () => {
    const s = await session();
    try {
      const names = await s
        .stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Dimensions"), ODataQuery.none().select("Name")))
        .map((d) => d.Name)
        .toArray();
      expect(names.length).toBeGreaterThan(0);
      for (const n of names) expect(n.trim()).not.toBe("");
    } finally {
      await s.close();
    }
  });

  tm1Test("reports a missing object as not found", async () => {
    const s = await session();
    try {
      expect(await failure(s.execute(Tm1Request.get(ODataPath.of("Cubes").key("zz_no_such_cube"))))).toBeInstanceOf(Tm1NotFoundException);
    } finally {
      await s.close();
    }
  });
});

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
    pool = Tm1SessionPool.builder((await sessions()).sessionContext(context)).idleTimeout(1_000).closeGrace(0).sweepInterval(3_600_000).build();
  };

  afterEach(async () => {
    await pool?.close();
  });

  tm1Test("runs each identity as itself", async () => {
    await start();
    expect(await name(pool.session(Tm1Identity.impersonating(alice)))).toBe(alice);
    expect(await name(pool.session(Tm1Identity.impersonating(bob)))).toBe(bob);
    expect(await name(pool.session(Tm1Identity.withCredentials(bob, Tm1Authenticator.basic(bob, "b"))))).toBe(bob);
    expect((await name(pool.session())).toLowerCase()).toBe("admin");
    expect(pool.stats().held).toBe(4);
  });

  tm1Test("closes evicted sessions on TM1", async () => {
    await start();
    const s = pool.session(Tm1Identity.impersonating(alice));
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
    await name(pool.session(Tm1Identity.impersonating(alice)));
    await name(pool.session(Tm1Identity.impersonating(bob)));
    await pool.close();
    expect(await raw.awaitSessionCount(admin, context, 0)).toBe(0);
  });

  /** Asking for an administrator fails; nothing runs as the pool's own user instead. */
  tm1Test("never falls back to another identity", async () => {
    await start();
    const e = await failure(pool.session(Tm1Identity.impersonating("Admin")).execute(ACTIVE_USER));
    expect(e).toBeInstanceOf(Tm1ApiException);
    expect((e as Tm1ApiException).kind).toBe("ImpersonationRefused");
  });
});

/** The Java client's `Tm1CapabilitiesTm1Test`. */
describe("Tm1 capabilities on TM1" + liveReason, () => {
  const snapshot = (): string => readFileSync(Repo.snapshot, "utf8");

  tm1Test("reads the version and the capabilities the snapshot describes", async () => {
    const s = (await sessions()).build();
    try {
      const reported = (await s.execute(SERVER)).ProductVersion;
      expect((await s.version()).equals(Tm1Version.parse(reported))).toBe(true);
      const capabilities = await s.capabilities();
      const xml = snapshot();
      expect(capabilities.schemaVersion).toBe(Tm1Capabilities.parse(xml).schemaVersion);
      const container = xml.slice(xml.indexOf("<EntityContainer"), xml.indexOf("</EntityContainer>"));
      for (const [, kind, n] of container.matchAll(/<(EntitySet|Singleton|ActionImport) Name="([^"]+)"/g)) {
        const capability = kind === "EntitySet" ? Tm1Capability.entitySet(n!) : kind === "Singleton" ? Tm1Capability.singleton(n!) : Tm1Capability.unboundOperation(n!);
        expect(capabilities.supports(capability), `${kind} ${n}`).toBe(true);
      }
      expect(capabilities.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve"))).toBe(true);
    } finally {
      await s.close();
    }
  });

  tm1Test("refuses a request the server doesn't define", async () => {
    const s = (await sessions()).build();
    try {
      const version = await s.version();
      const missing = Tm1Request.get(ODataPath.of("Bananas")).requires(Tm1Capability.entitySet("Bananas").since("99.0"));
      const e = await failure(s.execute(missing));
      expect(e).toBeInstanceOf(Tm1UnsupportedOperationException);
      const message = (e as Error).message;
      expect(message).toContain("added in schema 99.0");
      expect(message).toContain("reports schema " + Tm1Capabilities.parse(snapshot()).schemaVersion);
      expect(message).toContain("product version " + version.toString());
    } finally {
      await s.close();
    }
  });

  tm1Test("shares one fetch between a pool's sessions", async () => {
    const t = await Live.target();
    const pool = Tm1SessionPool.builder(await sessions()).build();
    try {
      const first = await pool.session().capabilities();
      const second = await pool.session(Tm1Identity.withCredentials("again", Tm1Authenticator.basic(t.user, t.password))).capabilities();
      expect(second).toBe(first);
    } finally {
      await pool.close();
    }
  });
});

const createProcess = (name: string, prolog: string): Tm1Request<void> => Tm1Request.post(ODataPath.of("Processes"), { Name: name, PrologProcedure: prolog });
const execute = (process: string): Tm1Request<{ ProcessExecuteStatusCode: string }> =>
  Tm1Request.post(ODataPath.of("Processes").key(process).action("tm1.ExecuteWithReturn"), null, Json) as Tm1Request<{ ProcessExecuteStatusCode: string }>;

/** The Java client's `Tm1SessionAsyncTm1Test`: a process run asynchronously, and stopped by cancelling. */
describe("Tm1Session async on TM1" + liveReason, () => {
  /** Waits up to 30 s for a thread running `process` to be there, or gone. */
  const waitFor = async (s: Tm1Session, process: string, running: boolean): Promise<boolean> => {
    for (let i = 0; i < 150; i++) {
      const threads = await s.stream(Tm1Request.list<{ Function?: string }>(ODataPath.of("Threads"), null)).toArray();
      if (threads.some((t) => typeof t.Function === "string" && t.Function.includes(`Processes('${process}')`)) === running) return true;
      await sleep(200);
    }
    return false;
  };

  tm1Test("runs a process and reads a collection through the monitor", async () => {
    const tm1 = await Live.client();
    const async = tm1.withAsync(200);
    const process = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
    await tm1.session.execute(createProcess(process, "x = 1;\n"));
    try {
      expect((await async.session.execute(execute(process))).ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
      expect((await async.session.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none().select("Name"))).toArray()).length).toBeGreaterThan(0);
    } finally {
      await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)));
      await tm1.close();
    }
  }, 60_000);

  tm1Test("stops the work on TM1 when cancelled", async () => {
    const tm1 = await Live.client();
    const async = tm1.withAsync(200);
    const process = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
    await tm1.session.execute(createProcess(process, "i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n"));
    try {
      const cancel = new AbortController();
      const run = async.session.execute(execute(process), { signal: cancel.signal });
      run.catch(() => undefined);
      expect(await waitFor(tm1.session, process, true), "the process runs").toBe(true);
      cancel.abort();
      await expect(run).rejects.toBeDefined();
      expect(await waitFor(tm1.session, process, false), "cancelling deleted the monitor, which stopped it").toBe(true);
    } finally {
      await waitFor(tm1.session, process, false);
      await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)));
      await tm1.close();
    }
  }, 120_000);
});

/** The Java client's `Tm1BatchTm1Test`. */
describe("Tm1 batches on TM1" + liveReason, () => {
  tm1Test("runs reads, writes and failures together", async () => {
    const tm1 = await Live.client();
    const dimension = "batch" + randomUUID().replace(/-/g, "").slice(0, 8);
    const process = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
    await tm1.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: dimension, Hierarchies: [{ Name: dimension }] }));
    await tm1.session.execute(createProcess(process, "x = 1;\n"));
    try {
      const batch = tm1.batch();
      const queued = batch.client.session;
      const elements = ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension).nav("Elements");
      const cube = queued.execute(Tm1Request.get<{ Name: string }>(ODataPath.of("Cubes").key("}ClientGroups"), ODataQuery.none().select("Name")));
      const missing = queued.execute(Tm1Request.get(ODataPath.of("Cubes").key("not-a-cube")));
      const north = queued.execute(Tm1Request.post(elements, { Name: "North" }, Json) as Tm1Request<{ Name: string }>);
      const twice = queued.execute(Tm1Request.post(elements, { Name: "North" }, Json));
      const listed = queued
        .stream(Tm1Request.list<{ Name: string }>(elements, null))
        .map((e) => e.Name)
        .toArray();
      const run = queued.execute(execute(process));
      expect(batch.size()).toBe(6);
      await batch.run();
      expect((await cube).Name).toBe("}ClientGroups");
      expect(await failure(missing)).toBeInstanceOf(Tm1NotFoundException);
      expect((await north).Name).toBe("North");
      // TM1 carries on past it, in order
      expect(await failure(twice)).toBeInstanceOf(Tm1Exception);
      // Sees the element made before it
      expect(await listed).toEqual(["North"]);
      expect((await run).ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
    } finally {
      await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)));
      await tm1.session.execute(Tm1Request.delete(ODataPath.of("Dimensions").key(dimension)));
      await tm1.close();
    }
  }, 60_000);

  tm1Test("takes a thousand requests", async () => {
    const tm1 = await Live.client();
    try {
      const batch = tm1.batch();
      const reads = Array.from({ length: 1000 }, () => batch.client.session.execute(Tm1Request.get(ODataPath.of("Cubes").key("}ClientGroups"), ODataQuery.none().select("Name"))));
      await batch.run(1000);
      expect(await Promise.all(reads)).toHaveLength(1000);
    } finally {
      await tm1.close();
    }
  }, 60_000);
});

/** The Java client's `Tm1ClientTm1Test`. */
describe("Tm1Client on TM1" + liveReason, () => {
  tm1Test("connects, reads the server and closes", async () => {
    const client = await Live.client();
    const server = await (await client.connect()).session.execute(SERVER);
    const version = await client.session.version();
    await client.close();
    expect(version.toString()).toBe(server.ProductVersion);
    expect(client.session.isClosed()).toBe(true);
    expect(client.withSandbox("x").session.sandbox()).toBe("x");
  });
});

const dimensionJson = (name: string): string => JSON.stringify({ Name: name, Hierarchies: [{ Name: name }] });

/** The Java client's `Tm1SessionBehaviorTest`: what TM1 itself does that the session relies on,
 * checked over plain HTTP. */
describe("TM1's session behaviour" + liveReason, () => {
  let tm1: RawTm1;

  beforeAll(async () => {
    if (Live.configured) tm1 = await RawTm1.connect();
  });

  /** The client replays a request once after a 401 on an established session, POSTs included.
   * That's only safe if TM1 refuses the request before running it. */
  tm1Test("refuses a request on a killed session without running it", async () => {
    const admin = await tm1.login();
    const victim = await tm1.login();
    const dimension = scratch();
    try {
      expect(await tm1.kill(admin, await tm1.sessionId(victim))).toBe(204);
      const refused = await tm1.request("POST", "Dimensions").withCookie(victim).withBody(dimensionJson(dimension)).send();
      expect(refused.status).toBe(401);
      expect((await tm1.request("GET", `Dimensions('${dimension}')`).withCookie(admin).send()).status, "the refused POST must not have created it").toBe(404);
      const relogged = RawTm1.cookieOf(refused)!;
      expect((await tm1.request("GET", "ActiveSession?$select=ID").withCookie(relogged).withBasic().send()).status).toBe(200);
      expect((await tm1.request("POST", "Dimensions").withCookie(relogged).withBody(dimensionJson(dimension)).send()).status).toBe(201);
      expect((await tm1.request("GET", `Dimensions/$count?$filter=Name%20eq%20'${dimension}'`).withCookie(admin).send()).body).toBe("1");
      await tm1.close(relogged);
    } finally {
      await tm1.request("DELETE", `Dimensions('${dimension}')`).withCookie(admin).send();
      await tm1.close(admin);
    }
  });

  /** Every 401 opens a TM1 session with no user, and sets its cookie. Sending that cookie with the
   * credentials logs that same session in, instead of opening another. */
  tm1Test("opens a session on every 401, and logs that session in when its cookie is sent with credentials", async () => {
    const admin = await tm1.login();
    try {
      const before = await tm1.allSessionCount(admin);
      const anonymous = await tm1.request("GET", "ActiveSession?$select=ID").send();
      expect(anonymous.status).toBe(401);
      expect(anonymous.headers.get("WWW-Authenticate")).toBe('Basic realm="TM1"');
      const cookie = RawTm1.cookieOf(anonymous)!;
      expect(await tm1.allSessionCount(admin), "sessions after one 401").toBe(before + 1);
      const login = await tm1.request("GET", "ActiveSession?$select=ID").withCookie(cookie).withBasic().send();
      expect(login.status).toBe(200);
      expect(RawTm1.cookieOf(login), "no new cookie: the same session was logged in").toBeUndefined();
      expect(await tm1.allSessionCount(admin)).toBe(before + 1);
      await tm1.close(cookie);
      expect(await tm1.allSessionCount(admin)).toBe(before);
    } finally {
      await tm1.close(admin);
    }
  });

  /** `Server` can be read with no credentials, but even that opens a session. So the client never
   * reads anything, the version included, outside its own session. */
  tm1Test("opens a session for an anonymous read of Server", async () => {
    const admin = await tm1.login();
    try {
      const before = await tm1.allSessionCount(admin);
      const server = await tm1.request("GET", "Server?$select=Name").send();
      expect(server.status).toBe(200);
      expect(await tm1.allSessionCount(admin)).toBe(before + 1);
      const cookie = RawTm1.cookieOf(server)!;
      await tm1.request("GET", "ActiveSession?$select=ID").withCookie(cookie).withBasic().send();
      await tm1.close(cookie);
    } finally {
      await tm1.close(admin);
    }
  });

  /** A closed session is refused at once, but TM1 lists it in `Sessions` for several seconds more, so
   * a check that no session is left behind has to wait for the count to settle. */
  tm1Test("refuses a closed session at once but lists it for seconds more", async () => {
    const admin = await tm1.login();
    const cookie = await tm1.login();
    const id = await tm1.sessionId(cookie);
    try {
      const reads = await Promise.all(Array.from({ length: 8 }, () => tm1.request("GET", "Server?$select=Name").withCookie(cookie).send()));
      for (const r of reads) expect(r.status).toBe(200);
      expect((await tm1.request("POST", "ActiveSession/tm1.Close").withCookie(cookie).withBody("{}").send()).status).toBe(204);
      expect((await tm1.request("GET", `Sessions(${id})`).withCookie(admin).send()).status, "still listed just after close").toBe(200);
      expect(await tm1.awaitGone(admin, id), "gone within 30 s").toBe(true);
    } finally {
      await tm1.close(admin);
    }
  }, 60_000);

  /** Closing a session expires its cookie, and later requests with that cookie get a 401. */
  tm1Test("expires the cookie on close and refuses it afterwards", async () => {
    const cookie = await tm1.login();
    const close = await tm1.request("POST", "ActiveSession/tm1.Close").withCookie(cookie).withBody("{}").send();
    expect(close.status).toBe(204);
    expect(close.cookies.some((c) => c.startsWith("TM1SessionId=") && c.includes("Max-Age=0"))).toBe(true);
    const after = await tm1.request("GET", "ActiveSession?$select=ID").withCookie(cookie).send();
    expect(after.status).toBe(401);
    const leftover = RawTm1.cookieOf(after)!;
    await tm1.request("GET", "ActiveSession?$select=ID").withCookie(leftover).withBasic().send();
    await tm1.close(leftover);
  });

  /** Requests on one session run in parallel: three one-second process runs finish in about one
   * second. So one shared session doesn't serialize the client's traffic. */
  tm1Test("runs concurrent requests on one session in parallel", async () => {
    const cookie = await tm1.login();
    const process = scratch();
    try {
      expect((await tm1.request("POST", "Processes").withCookie(cookie).withBody(JSON.stringify({ Name: process, PrologProcedure: "Sleep(1000);" })).send()).status).toBe(201);
      const start = Date.now();
      const runs = await Promise.all(Array.from({ length: 3 }, () => tm1.request("POST", `Processes('${process}')/tm1.ExecuteWithReturn`).withCookie(cookie).withBody("{}").send()));
      for (const r of runs) expect(RawTm1.json(r)["ProcessExecuteStatusCode"]).toBe("CompletedSuccessfully");
      expect(Date.now() - start, "three 1 s runs on one session").toBeLessThan(2_500);
    } finally {
      await tm1.request("DELETE", `Processes('${process}')`).withCookie(cookie).send();
      await tm1.close(cookie);
    }
  });

  /** `TM1-SessionContext` labels the whole session, and the last request to send it wins. So it can't
   * be a per-request setting on views that share one session. */
  tm1Test("labels the session with the last session context sent", async () => {
    const login = await tm1.request("GET", "ActiveSession?$select=Context").withBasic().withHeader("TM1-SessionContext", "first").send();
    const cookie = RawTm1.cookieOf(login)!;
    try {
      expect(RawTm1.json(login)["Context"]).toBe("first");
      await tm1.request("GET", "Server?$select=Name").withCookie(cookie).withHeader("TM1-SessionContext", "second").send();
      expect(RawTm1.json(await tm1.request("GET", "ActiveSession?$select=Context").withCookie(cookie).send())["Context"]).toBe("second");
    } finally {
      await tm1.close(cookie);
    }
  });

  /** A create answers 201 with a relative `Location` and an `ETag`; with `Prefer: return=minimal`,
   * 204 with `Location` and `OData-EntityId`. */
  tm1Test("answers a create with its location", async () => {
    const cookie = await tm1.login();
    const full = scratch();
    const minimal = scratch();
    try {
      const created = await tm1.request("POST", "Dimensions").withCookie(cookie).withBody(dimensionJson(full)).send();
      expect(created.status).toBe(201);
      expect(created.headers.get("Location")).toBe(`Dimensions('${full}')`);
      expect(created.headers.get("ETag")).not.toBeNull();
      const bare = await tm1.request("POST", "Dimensions").withCookie(cookie).withHeader("Prefer", "return=minimal").withBody(dimensionJson(minimal)).send();
      expect(bare.status).toBe(204);
      expect(bare.headers.get("Location")).toBe(`Dimensions('${minimal}')`);
      expect(bare.headers.get("OData-EntityId")).toBe(`Dimensions('${minimal}')`);
    } finally {
      await tm1.request("DELETE", `Dimensions('${full}')`).withCookie(cookie).send();
      await tm1.request("DELETE", `Dimensions('${minimal}')`).withCookie(cookie).send();
      await tm1.close(cookie);
    }
  });

  /** `odata.metadata=none` drops `@odata.etag` but still sends `@odata.context`. */
  tm1Test("drops the ETag but not the context with metadata none", async () => {
    const cookie = await tm1.login();
    const name = scratch();
    try {
      await tm1.request("POST", "Dimensions").withCookie(cookie).withBody(dimensionJson(name)).send();
      const minimal = RawTm1.json(await tm1.request("GET", `Dimensions('${name}')`).withCookie(cookie).send());
      const none = RawTm1.json(await tm1.request("GET", `Dimensions('${name}')`).withCookie(cookie).withHeader("Accept", "application/json;odata.metadata=none").send());
      expect("@odata.etag" in minimal).toBe(true);
      expect("@odata.etag" in none).toBe(false);
      expect("@odata.context" in none).toBe(true);
    } finally {
      await tm1.request("DELETE", `Dimensions('${name}')`).withCookie(cookie).send();
      await tm1.close(cookie);
    }
  });

  /** TM1 closes a kept-alive connection idle for about 90 seconds, and a request sent on it as it
   * closes gets no response at all. Node's `fetch` (undici) keeps an idle connection for 4 seconds
   * unless the server asks for longer, far below that, so `fetchTransport` never reuses one TM1 is
   * closing. Takes up to 150 s. */
  tm1Test("closes a connection idle for ninety seconds", async () => {
    const t = await Live.target();
    const url = new URL(t.url + "Server?$select=Name");
    const secure = url.protocol === "https:";
    const agent = secure ? new HttpsAgent({ keepAlive: true, maxSockets: 1, rejectUnauthorized: process.env["TM1_VERIFY"] !== "false" }) : new HttpAgent({ keepAlive: true, maxSockets: 1 });
    try {
      const socket = await new Promise<Socket>((resolve, reject) => {
        const send = secure ? httpsRequest : httpRequest;
        const req = send(url, { agent, headers: { Authorization: basic(t.user, t.password) } }, (response: IncomingMessage) => {
          expect(response.statusCode).toBe(200);
          response.resume();
          response.on("end", () => resolve(req.socket!));
        });
        req.on("error", reject);
        req.end();
      });
      const used = Date.now();
      // Closed by TM1
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("TM1 didn't close the idle connection within 150 s")), 150_000);
        socket.once("close", () => {
          clearTimeout(timer);
          resolve();
        });
      });
      const idle = (Date.now() - used) / 1000;
      expect(idle, "seconds idle before TM1 closed it").toBeGreaterThanOrEqual(80);
      expect(idle).toBeLessThanOrEqual(100);
    } finally {
      agent.destroy();
    }
  }, 200_000);
});

/** The Java client's `Tm1ImpersonationBehaviorTest`: how TM1 itself treats `TM1-Impersonate`, which
 * the session and pool rely on. */
describe("TM1's impersonation behaviour" + liveReason, () => {
  let tm1: RawTm1;
  let admin: string;
  let alice: string;
  let bob: string;
  const impersonate = (user: string, password: string, target: string): Promise<RawResponse> =>
    tm1.request("GET", "ActiveUser?$select=Name").withHeader("Authorization", basic(user, password)).withHeader("TM1-Impersonate", target).send();
  const createUser = async (name: string, group: string): Promise<void> => {
    await tm1.request("POST", "Users").withCookie(admin).withBody(JSON.stringify({ Name: name, Password: "p", "Groups@odata.bind": [`Groups('${group}')`] })).send();
  };
  const nameOf = async (request: RawRequest): Promise<string> => RawTm1.json(await request.send())["Name"] as string;
  const closeIfSet = async (response: RawResponse): Promise<void> => {
    const cookie = RawTm1.cookieOf(response);
    if (cookie) await tm1.close(cookie);
  };

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await RawTm1.connect();
    admin = await tm1.login();
    const run = randomUUID().replace(/-/g, "").slice(0, 6);
    alice = "zz_alice_" + run;
    bob = "zz_bob_" + run;
    await tm1.createUser(admin, alice, "a");
    await tm1.createUser(admin, bob, "b");
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.deleteUser(admin, alice);
    await tm1.deleteUser(admin, bob);
    await tm1.close(admin);
  });

  /** Impersonation is decided at login, and the header on later requests is ignored. So it's a
   * session setting, and one identity means one session. */
  tm1Test("decides the identity at login and ignores the header afterwards", async () => {
    const login = await tm1.request("GET", "ActiveUser?$select=Name").withBasic().withHeader("TM1-Impersonate", alice).send();
    expect(login.status).toBe(200);
    expect(RawTm1.json(login)["Name"]).toBe(alice);
    const session = RawTm1.cookieOf(login)!;
    try {
      expect(await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(session))).toBe(alice);
      // A different user on a later request
      expect(await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(session).withHeader("TM1-Impersonate", bob))).toBe(alice);
      const plain = await tm1.login();
      expect((await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(plain).withHeader("TM1-Impersonate", alice))).toLowerCase()).toBe("admin");
      await tm1.close(plain);
    } finally {
      await tm1.close(session);
    }
  });

  /** It also works when logging in the session a 401 opened, as the client's re-login does. */
  tm1Test("impersonates on a login that reuses the 401's session", async () => {
    const seed = RawTm1.cookieOf(await tm1.request("GET", "ActiveUser?$select=Name").send())!;
    try {
      expect(await nameOf(tm1.request("GET", "ActiveUser?$select=Name").withCookie(seed).withBasic().withHeader("TM1-Impersonate", alice))).toBe(alice);
    } finally {
      await tm1.close(seed);
    }
  });

  /** Impersonating a user that doesn't exist is a 400, and a non-administrator trying is a 403, both
   * with plain-text bodies, which the client reads as the error message. */
  tm1Test("refuses unknown users and non-administrators with plain-text errors", async () => {
    const unknown = await tm1.request("GET", "ActiveUser?$select=Name").withBasic().withHeader("TM1-Impersonate", "zz_nobody").send();
    expect(unknown.status).toBe(400);
    expect(unknown.body.startsWith("Error: Bad Request")).toBe(true);
    expect(unknown.body).toContain("The user specified in the impersonation request does not exist.");
    const left = RawTm1.cookieOf(unknown)!;
    expect((await tm1.request("GET", "ActiveUser?$select=Name").withCookie(left).send()).status, "the session a refused impersonation leaves").toBe(401);
    const nonAdmin = await impersonate(alice, "a", bob);
    expect(nonAdmin.status).toBe(403);
    expect(nonAdmin.body.startsWith("Error: Forbidden")).toBe(true);
    expect(nonAdmin.body).toContain("You need to be a (data-)administrator to be able to impersonate another user.");
  });

  /** Nobody can impersonate an administrator, not even another administrator. */
  tm1Test("refuses to impersonate any administrator", async () => {
    const otherAdmin = alice + "_adm";
    const dataAdmin = alice + "_data";
    await createUser(otherAdmin, "ADMIN");
    await createUser(dataAdmin, "DataAdmin");
    try {
      for (const [user, password, target] of [
        [tm1.user, tm1.password, otherAdmin],
        [tm1.user, tm1.password, "Admin"],
        [dataAdmin, "p", otherAdmin],
        [dataAdmin, "p", "Admin"],
      ] as const) {
        const refused = await impersonate(user, password, target);
        expect(refused.status, `${user} impersonating ${target}`).toBe(403);
        expect(refused.body).toContain("You have insufficient rights to impersonate the user specified!");
      }
    } finally {
      await tm1.deleteUser(admin, otherAdmin);
      await tm1.deleteUser(admin, dataAdmin);
    }
  });

  /** Data administrators can impersonate ordinary users; security and operations administrators
   * can't, with a different 403 message. */
  tm1Test("lets data administrators impersonate, but not other administrator roles", async () => {
    const dataAdmin = alice + "_data";
    const securityAdmin = alice + "_sec";
    const operationsAdmin = alice + "_ops";
    await createUser(dataAdmin, "DataAdmin");
    await createUser(securityAdmin, "SecurityAdmin");
    await createUser(operationsAdmin, "OperationsAdmin");
    try {
      const allowed = await impersonate(dataAdmin, "p", bob);
      expect(allowed.status).toBe(200);
      await closeIfSet(allowed);
      for (const role of [securityAdmin, operationsAdmin]) {
        const refused = await impersonate(role, "p", bob);
        expect(refused.status, role).toBe(403);
        expect(refused.body).toContain("You need to be a (data-)administrator to be able to impersonate another user.");
      }
    } finally {
      await tm1.deleteUser(admin, dataAdmin);
      await tm1.deleteUser(admin, securityAdmin);
      await tm1.deleteUser(admin, operationsAdmin);
    }
  });

  /** Impersonating a disabled user is a 401 with an empty body: exactly what wrong credentials look
   * like. The client can't tell the two apart, so it reports both. */
  tm1Test("refuses a disabled user like wrong credentials", async () => {
    await tm1.request("PATCH", `Users('${bob}')`).withCookie(admin).withBody('{"Enabled":false}').send();
    try {
      const refused = await impersonate(tm1.user, tm1.password, bob);
      expect(refused.status).toBe(401);
      expect(refused.body).toBe("");
    } finally {
      await tm1.request("PATCH", `Users('${bob}')`).withCookie(admin).withBody('{"Enabled":true}').send();
    }
  });

  /** An empty `TM1-Impersonate` means no impersonation. So the client must never send a blank name. */
  tm1Test("treats an empty header as no impersonation", async () => {
    const login = await impersonate(tm1.user, tm1.password, "");
    try {
      expect(login.status).toBe(200);
      expect((RawTm1.json(login)["Name"] as string).toLowerCase()).toBe("admin");
    } finally {
      await closeIfSet(login);
    }
  });

  /** User names match ignoring case and spaces, so the pool compares identities the same way. */
  tm1Test("matches user names ignoring case and spaces", async () => {
    for (const written of [alice.toUpperCase(), alice.replace(/_/g, "_ ")]) {
      const login = await impersonate(tm1.user, tm1.password, written);
      try {
        expect(login.status, written).toBe(200);
        expect(RawTm1.json(login)["Name"]).toBe(alice);
      } finally {
        await closeIfSet(login);
      }
    }
  });

  /** A user whose name has non-ASCII characters can't be impersonated, however the header is
   * encoded; the client refuses such names before sending anything. */
  tm1Test("can't impersonate users with non-ASCII names", async () => {
    const accented = alice + "_ë";
    await tm1.createUser(admin, accented, "p");
    try {
      // fetch sends a header's characters as Latin-1 bytes; the UTF-8 bytes read as Latin-1 are the other way to write it
      const utf8AsLatin1 = Buffer.from(accented, "utf8").toString("latin1");
      for (const written of [accented, utf8AsLatin1]) {
        const refused = await impersonate(tm1.user, tm1.password, written);
        expect(refused.status).toBe(400);
        expect(refused.body).toContain("The user specified in the impersonation request does not exist.");
      }
      expect(() => Tm1Identity.impersonating(accented)).toThrow(RangeError);
    } finally {
      await tm1.deleteUser(admin, accented);
    }
  });
});

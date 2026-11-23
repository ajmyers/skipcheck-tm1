// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { gzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import {
  Json,
  ODataPath,
  ODataQuery,
  Tm1ApiException,
  Tm1AuthenticationException,
  Tm1Authenticator,
  Tm1BadRequestException,
  Tm1Capability,
  Tm1ForbiddenException,
  Tm1NotFoundException,
  Tm1ProtocolException,
  Tm1Request,
  Tm1ServerException,
  Tm1Session,
  Tm1SessionBatch,
  Tm1SessionClosedException,
  Tm1Timeouts,
  Tm1TransportException,
  Tm1UnsupportedOperationException,
  Tm1Version,
  type Tm1Exception,
} from "../../src/index.js";
import { asyncResult } from "../../src/session/connection.js";
import { Repo } from "../support/repo.js";
import { eventually, Pending, ScriptedTm1, seeded, settle } from "../support/scripted-tm1.js";

const { LOGIN, CLOSE, ROOT } = ScriptedTm1;
const SNAPSHOT = readFileSync(Repo.snapshot, "utf8");

/** What a promise failed with; fails the test if it didn't. */
async function failure(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    (v) => expect.fail("expected a failure, got " + JSON.stringify(v)),
    (e: unknown) => e,
  );
}

/** A Node system error, as `fetch` and sockets raise them. */
function systemError(code: string, message: string): Error {
  return Object.assign(new Error(message), { code });
}

/** The Java client's `Tm1SessionTest`: login, cookie, errors, reading, streaming and closing. */
describe("Tm1Session", () => {
  const SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none().select("Name"));
  const scripted = (): ScriptedTm1 => new ScriptedTm1().on("GET Server?$select=Name", () => ScriptedTm1.json(200, '{"Name":"tm1"}'));

  it("gives up on an authenticator that never answers", async () => {
    const tm1 = scripted();
    let cancelled: AbortSignal | undefined;
    const never = new (class extends Tm1Authenticator {
      authorization(signal?: AbortSignal): Promise<string> {
        cancelled = signal;
        return new Promise<string>(() => undefined);
      }

      describe(): string {
        return "Never";
      }
    })();
    const session = tm1.sessionWithOwnCredentials((b) => b.authenticator(never).timeouts(Tm1Timeouts.builder().login(100).build()));
    const within = <T>(promise: Promise<T>): Promise<T | string> =>
      Promise.race([promise, new Promise<string>((r) => setTimeout(() => r("still waiting"), 3_000))]);

    const e = await within(failure(session.execute(SERVER)));
    expect(e).toBeInstanceOf(Tm1TransportException);
    const t = e as Tm1TransportException;
    expect(t.phase).toBe("Connect");
    expect(t.isRetryable(), "nothing was sent to TM1").toBe(true);
    expect(t.mayHaveApplied()).toBe(false);
    expect(t.message).toContain("Never");
    expect(cancelled?.aborted, "the authenticator is told to stop").toBe(true);
    expect(await within(session.close())).toBeUndefined();
    expect(tm1.requests(LOGIN)).toHaveLength(0);
  });

  it("logs in once for concurrent first requests and sends only the cookie afterwards", async () => {
    const tm1 = scripted();
    const login = new Pending();
    tm1.on(LOGIN, login.reply);
    const session = tm1.session();

    const three = Promise.all([session.execute(SERVER), session.execute(SERVER), session.execute(SERVER)]);
    await settle();
    login.answer(ScriptedTm1.json(200, '{"ID":7}', "TM1SessionId=abc; Path=/api/; HttpOnly"));
    expect(await three).toHaveLength(3);

    expect(tm1.requests(LOGIN)).toHaveLength(1);
    expect(tm1.requests(LOGIN)[0]!.authorization).toBe("Basic YWRtaW46YXBwbGU=");
    const reads = tm1.requests("GET Server?$select=Name");
    expect(reads).toHaveLength(3);
    for (const r of reads) {
      expect(r.cookie).toBe("abc");
      expect(r.authorization, "no credentials after login").toBeUndefined();
      expect(r.header("OData-Version")).toBe("4.0");
      expect(r.header("OData-MaxVersion")).toBe("4.0");
    }
    expect(await session.sessionId()).toBe(7);
  });

  it("finds the session cookie by name among others", async () => {
    const tm1 = scripted().on(LOGIN, () => ScriptedTm1.json(200, '{"ID":3}', "other=1; Path=/", "TM1SessionId=right; Path=/api/; HttpOnly", "more=2"));
    await tm1.session().execute(SERVER);
    expect(tm1.requests("GET Server?$select=Name")[0]!.cookie).toBe("right");
  });

  it("reports refused credentials and can try again on the next request", async () => {
    const tm1 = scripted().onEach(
      LOGIN,
      () => ScriptedTm1.status(401, "TM1SessionId=anon; Path=/api/"),
      () => ScriptedTm1.json(200, '{"ID":2}', "TM1SessionId=ok; Path=/api/"),
    );
    // With the refused-login back-off off; the impersonation tests cover the back-off
    const session = tm1.session((b) => b.timeouts(Tm1Timeouts.builder().refusedLogin(0).build()));

    const e = await failure(session.execute(SERVER));
    expect(e).toBeInstanceOf(Tm1AuthenticationException);
    expect((e as Tm1AuthenticationException).kind).toBe("CredentialRejected");
    expect(tm1.requests("GET Server?$select=Name"), "nothing sent without a session").toHaveLength(0);

    await session.execute(SERVER);
    expect(tm1.requests(LOGIN)).toHaveLength(2);
  });

  it("maps error responses through the classifier", async () => {
    const tm1 = scripted().on("GET Cubes('Nope')", () =>
      ScriptedTm1.json(404, `{"error":{"code":"278","message":"'Nope' can not be found in collection of type 'Cube'."}}`),
    );
    const e = await failure(tm1.session().execute(Tm1Request.get(ODataPath.of("Cubes").key("Nope"), ODataQuery.none())));
    expect(e).toBeInstanceOf(Tm1NotFoundException);
    const n = e as Tm1NotFoundException;
    expect(n.status).toBe(404);
    expect(n.error!.code).toBe("278");
    expect(n.url).toBe(ROOT + "Cubes('Nope')");
  });

  it("reads a plain-text error body as the message", async () => {
    const tm1 = scripted().on("GET Cubes('x')", () => ScriptedTm1.text("Error: Bad Request\r\nSomething TM1 says in plain text.\r\n", 400));
    const e = await failure(tm1.session().execute(Tm1Request.get(ODataPath.of("Cubes").key("x"), ODataQuery.none())));
    expect(e).toBeInstanceOf(Tm1ApiException);
    expect((e as Tm1ApiException).error!.message).toBe("Something TM1 says in plain text.");
  });

  it("reads text and no content and sends JSON bodies", async () => {
    const tm1 = scripted()
      .on("GET Configuration/ProductVersion/$value", () => ScriptedTm1.text("11.8.03500.6"))
      .on("POST Cubes('Sales')/tm1.Lock", () => ScriptedTm1.status(204));
    const session = tm1.session();

    expect(await session.execute(Tm1Request.text(ODataPath.of("Configuration").nav("ProductVersion").value()))).toBe("11.8.03500.6");
    expect(await session.execute(Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Lock"), null))).toBeUndefined();

    expect(tm1.requests("GET Configuration/ProductVersion/$value")[0]!.header("Accept")).toMatch(/^text\/plain/);
    const lock = tm1.requests("POST Cubes('Sales')/tm1.Lock")[0]!;
    expect(lock.header("Content-Type")).toBe("application/json");
    expect(lock.text).toBe("{}");
  });

  it("streams collections across next links", async () => {
    const tm1 = scripted()
      .on("GET Dimensions?$select=Name", () =>
        ScriptedTm1.json(
          200,
          '{"@odata.context":"$metadata#Dimensions(Name)","value":[{"Name":"a"},{"Name":"b"}],"@odata.nextLink":"Dimensions?$skiptoken=2"}',
        ),
      )
      .on("GET Dimensions?$skiptoken=2", () => ScriptedTm1.json(200, '{"value":[{"Name":"c"}]}'));
    const names = await tm1
      .session()
      .stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Dimensions"), ODataQuery.none().select("Name")))
      .map((d) => d.Name)
      .toArray();
    expect(names).toEqual(["a", "b", "c"]);
  });

  it("refuses to stream a single response", () => {
    const tm1 = scripted();
    expect(() => tm1.session().stream(SERVER)).toThrow(TypeError);
    expect(tm1.requests()).toHaveLength(0);
  });

  it("refuses to execute a collection without sending it", async () => {
    const tm1 = scripted();
    await expect(tm1.session().execute(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none()))).rejects.toBeInstanceOf(TypeError);
    expect(tm1.requests()).toHaveLength(0);
  });

  it("reads bytes that aren't UTF-8 as the replacement character", async () => {
    // As TM1's transaction log gives a password's hash, with "é" (two bytes) beside it
    const utf8 = new TextEncoder();
    const body = Uint8Array.from([...utf8.encode('{"Name":"é'), 0xe0, 0xec, 0x72, 0xff, 0xff, ...utf8.encode('"}')]);
    const tm1 = new ScriptedTm1().on("GET Server?$select=Name", () => ScriptedTm1.bytes(200, body, "application/json"));
    const replacement = String.fromCharCode(0xfffd);
    expect(await tm1.session().execute(SERVER)).toEqual({ Name: "é" + replacement.repeat(2) + "r" + replacement.repeat(2) });
  });

  it("reports a body that isn't JSON as the caller's mistake, not a transport failure", async () => {
    const unwritable = {
      get Name(): string {
        throw new Error("not readable");
      },
    };
    const e = await failure(
      scripted()
        .session()
        .execute(Tm1Request.post(ODataPath.of("Cubes"), unwritable)),
    );
    expect(e).toBeInstanceOf(TypeError);
    expect(e).not.toBeInstanceOf(Tm1TransportException);
  });

  it("passes other failures through unwrapped", async () => {
    const bug = new RangeError("a bug, not the network");
    const tm1 = scripted().on("GET Server?$select=Name", () => {
      throw bug;
    });
    expect(await failure(tm1.session().execute(SERVER))).toBe(bug);
  });

  it("maps connect failures and other transport failures", async () => {
    const tm1 = scripted()
      .on("GET Server?$select=Name", () => {
        throw new TypeError("fetch failed", { cause: systemError("ECONNREFUSED", "connect ECONNREFUSED 127.0.0.1:8010") });
      })
      .on("POST Processes('p')/tm1.ExecuteWithReturn", () => {
        throw systemError("ECONNRESET", "Connection reset");
      });
    const session = tm1.session();

    const connect = await failure(session.execute(SERVER));
    expect(connect).toBeInstanceOf(Tm1TransportException);
    expect((connect as Tm1TransportException).phase).toBe("Connect");
    expect((connect as Tm1TransportException).mayHaveApplied()).toBe(false);

    const reset = await failure(session.execute(Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), null)));
    expect(reset).toBeInstanceOf(Tm1TransportException);
    const t = reset as Tm1TransportException;
    expect(t.phase).toBe("Response");
    expect(t.mayHaveApplied()).toBe(true);
    expect(t.isRetryable(), "a POST that may have run").toBe(false);
  });

  it("gives up on a request that gets no answer within its timeout", async () => {
    const tm1 = scripted().on("GET Server?$select=Name", () => new Pending().promise);
    const e = await failure(tm1.session().execute(SERVER.withTimeout(20)));
    expect(e).toBeInstanceOf(Tm1TransportException);
    expect((e as Tm1TransportException).phase).toBe("Response");
    expect((e as Tm1TransportException).isRetryable(), "a GET").toBe(true);
  });

  it("sends nothing on close if it never logged in", async () => {
    const tm1 = scripted();
    const session = tm1.session();
    await session.close();
    expect(tm1.requests()).toHaveLength(0);
    expect(session.isClosed()).toBe(true);
    await expect(session.execute(SERVER)).rejects.toBeInstanceOf(Tm1SessionClosedException);
    expect(tm1.requests()).toHaveLength(0);
  });

  it("closes the session once with a JSON body and refuses requests afterwards", async () => {
    const tm1 = scripted();
    const session = tm1.session();
    await session.execute(SERVER);

    await Promise.all([session.close(), session.close()]);
    await session.close();

    const closes = tm1.requests(CLOSE);
    expect(closes).toHaveLength(1);
    expect(closes[0]!.cookie).toBe("c1");
    expect(closes[0]!.header("Content-Type")).toBe("application/json");
    expect(closes[0]!.text).toBe("{}");
    expect(closes[0]!.closeConnection, "not reused after a close").toBe(true);
    await expect(session.execute(SERVER)).rejects.toBeInstanceOf(Tm1SessionClosedException);
    expect(tm1.requests(LOGIN), "a closed session doesn't log in again").toHaveLength(1);
  });

  it("waits for a login in progress and closes it", async () => {
    const tm1 = scripted();
    const login = new Pending();
    tm1.on(LOGIN, login.reply);
    const session = tm1.session();

    const first = session.execute(SERVER).catch(() => undefined);
    await settle();
    let closed = false;
    const close = session.close().then(() => (closed = true));
    await settle();
    expect(closed).toBe(false);
    expect(tm1.requests(CLOSE)).toHaveLength(0);
    login.answer(ScriptedTm1.json(200, '{"ID":5}', "TM1SessionId=late; Path=/api/"));
    await close;
    await first;

    expect(tm1.requests(CLOSE)).toHaveLength(1);
    expect(tm1.requests(CLOSE)[0]!.cookie).toBe("late");
  });

  it("finishes a login whose request was cancelled, so close can end it", async () => {
    const tm1 = scripted();
    const login = new Pending();
    tm1.on(LOGIN, login.reply);
    const session = tm1.session();

    const cancel = new AbortController();
    const first = session.execute(SERVER, { signal: cancel.signal });
    await settle();
    cancel.abort();
    await expect(first).rejects.toBeDefined();
    login.answer(ScriptedTm1.json(200, '{"ID":6}', "TM1SessionId=orphan; Path=/api/"));
    await session.close();

    expect(tm1.requests(CLOSE)).toHaveLength(1);
    expect(tm1.requests(CLOSE)[0]!.cookie).toBe("orphan");
    expect(tm1.requests("GET Server?$select=Name"), "the cancelled request isn't sent").toHaveLength(0);
  });

  it("treats a 401 on close as already closed", async () => {
    const tm1 = scripted().on(CLOSE, () => ScriptedTm1.status(401));
    const session = await tm1.session().connect();
    await session.close();
  });

  it("refuses a base URL that isn't an absolute URL, and settings without a server or credentials", () => {
    expect(Tm1Session.builder().baseUrl("http://tm1:8010/api/v1").basicAuth("a", "").build().serviceRoot()).toBe("http://tm1:8010/api/v1/");
    for (const bad of ["tm1/api/v1/", "/api/v1/", "http://tm1:8010/api/v1/?x=1", "http://tm1:8010/api/v1/#top"]) {
      expect(() => Tm1Session.builder().baseUrl(bad), bad).toThrow(RangeError);
    }
    expect(() => Tm1Session.builder().basicAuth("a", "").build()).toThrow("baseUrl is required");
    expect(() => Tm1Session.builder().baseUrl(ROOT).build()).toThrow("credentials are required");
  });
});

/** The Java client's `Tm1SessionReloginTest`: logging in again when TM1 drops the session. */
describe("Tm1Session logging in again", () => {
  const CREATE_KEY = "POST Dimensions";
  const CREATE = Tm1Request.post(ODataPath.of("Dimensions"), { Name: "d" }, Json);
  const SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none());

  it("logs in again with the cookie from the 401 and replays the POST once", async () => {
    const tm1 = new ScriptedTm1().onEach(
      CREATE_KEY,
      () => ScriptedTm1.status(401, "TM1SessionId=fresh; Path=/api/; HttpOnly"),
      () => ScriptedTm1.json(201, '{"Name":"d"}'),
    );
    const session = tm1.session();
    await session.connect();
    await session.execute(CREATE);

    const logins = tm1.requests(LOGIN);
    expect(logins).toHaveLength(2);
    expect(logins[1]!.cookie, "the re-login logs in the session TM1 opened for the 401").toBe("fresh");
    expect(logins[1]!.authorization).toBeDefined();
    const creates = tm1.requests(CREATE_KEY);
    expect(creates).toHaveLength(2);
    expect(creates[0]!.cookie).toBe("c1");
    expect(creates[1]!.cookie).toBe("fresh");
    expect(creates[1]!.authorization).toBeUndefined();
    expect(creates[1]!.json).toEqual({ Name: "d" });
    expect(await session.sessionId()).toBe(2);
  });

  it("shares one re-login between requests that saw the same expiry", async () => {
    const relogin = new Pending();
    let anon = 0;
    const tm1 = new ScriptedTm1()
      .onEach(LOGIN, () => ScriptedTm1.json(200, '{"ID":1}', "TM1SessionId=old; Path=/api/"), relogin.reply)
      .on("GET Server", (r) => (r.cookie === "old" ? ScriptedTm1.status(401, "TM1SessionId=anon" + ++anon) : ScriptedTm1.json(200, "{}")));
    const session = tm1.session();
    await session.connect();

    const three = Promise.all([session.execute(SERVER), session.execute(SERVER), session.execute(SERVER)]);
    await settle();
    relogin.answer(ScriptedTm1.json(200, '{"ID":2}', "TM1SessionId=new; Path=/api/"));
    expect(await three).toHaveLength(3);
    expect(tm1.requests(LOGIN)).toHaveLength(2);
  });

  it("gives up if the replay is refused too, without logging in again", async () => {
    const tm1 = new ScriptedTm1().on("GET Server", () => ScriptedTm1.status(401, "TM1SessionId=anon; Path=/api/"));
    const e = await failure(tm1.session().execute(SERVER));
    expect(e).toBeInstanceOf(Tm1AuthenticationException);
    expect((e as Tm1AuthenticationException).kind).toBe("Unauthenticated");
    expect(tm1.requests(LOGIN)).toHaveLength(2);
    expect(tm1.requests("GET Server")).toHaveLength(2);
  });

  it("reports refused credentials on re-login without replaying", async () => {
    const tm1 = new ScriptedTm1()
      .onEach(
        LOGIN,
        () => ScriptedTm1.json(200, '{"ID":1}', "TM1SessionId=c1; Path=/api/"),
        () => ScriptedTm1.status(401),
      )
      .on(CREATE_KEY, () => ScriptedTm1.status(401, "TM1SessionId=anon; Path=/api/"));
    const session = tm1.session();
    await session.connect();
    const e = await failure(session.execute(CREATE));
    expect(e).toBeInstanceOf(Tm1AuthenticationException);
    expect((e as Tm1AuthenticationException).kind).toBe("CredentialRejected");
    expect(tm1.requests(CREATE_KEY), "the POST is sent once").toHaveLength(1);
  });

  it("closes a session logged in again rather than the expired one", async () => {
    const tm1 = new ScriptedTm1().onEach(
      CREATE_KEY,
      () => ScriptedTm1.status(401, "TM1SessionId=fresh; Path=/api/"),
      () => ScriptedTm1.json(201, "{}"),
    );
    const session = tm1.session();
    await session.connect();
    await session.execute(CREATE);
    await session.close();

    expect(tm1.requests(CLOSE)).toHaveLength(1);
    expect(tm1.requests(CLOSE)[0]!.cookie).toBe("fresh");
  });

  it("doesn't log in again once closed", async () => {
    const response = new Pending();
    const tm1 = new ScriptedTm1().on("GET Server", response.reply);
    const session = tm1.session();
    await session.connect();

    const read = session.execute(SERVER);
    await settle();
    await session.close();
    response.answer(ScriptedTm1.status(401, "TM1SessionId=anon; Path=/api/"));
    await expect(read).rejects.toBeInstanceOf(Tm1SessionClosedException);
    expect(tm1.requests(LOGIN)).toHaveLength(1);
  });

  it("asks the authenticator for fresh credentials on each login", async () => {
    let issued = 0;
    const tm1 = new ScriptedTm1().onEach(
      CREATE_KEY,
      () => ScriptedTm1.status(401, "TM1SessionId=fresh; Path=/api/"),
      () => ScriptedTm1.json(201, "{}"),
    );
    const session = tm1.sessionWithOwnCredentials((b) => b.authenticator(Tm1Authenticator.camPassport(() => Promise.resolve("passport" + ++issued))));
    await session.connect();
    await session.execute(CREATE);

    const logins = tm1.requests(LOGIN);
    expect(logins[0]!.authorization).toBe("CAMPassport passport1");
    expect(logins[1]!.authorization).toBe("CAMPassport passport2");
  });

  it("passes an authenticator failure through without sending a login", async () => {
    const noPassport = new Error("Cognos is down");
    const tm1 = new ScriptedTm1();
    const session = tm1.sessionWithOwnCredentials((b) => b.authenticator(Tm1Authenticator.camPassport(() => Promise.reject(noPassport))));
    expect(await failure(session.connect())).toBe(noPassport);
    expect(tm1.requests(LOGIN)).toHaveLength(0);
  });
});

/** The Java client's `Tm1SessionImpersonationTest`: `TM1-Impersonate` at login, and how the session
 * treats a login TM1 refuses. */
describe("Tm1Session impersonating", () => {
  const SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none());
  const scripted = (): ScriptedTm1 => new ScriptedTm1().on("GET Server", () => ScriptedTm1.json(200, "{}"));
  let now = 0;
  const session = (tm1: ScriptedTm1, timeouts: Tm1Timeouts): Tm1Session => tm1.session((b) => b.timeouts(timeouts).clock(() => now));

  it("impersonates at login only", async () => {
    const tm1 = scripted();
    await tm1.session((b) => b.impersonate("alice")).execute(SERVER);
    expect(tm1.requests(LOGIN)[0]!.header("TM1-Impersonate")).toBe("alice");
    expect(tm1.requests("GET Server")[0]!.header("TM1-Impersonate")).toBeUndefined();
  });

  it("refuses names TM1 would misread", () => {
    expect(() => Tm1Session.builder().impersonate("")).toThrow(/no impersonation/);
    expect(() => Tm1Session.builder().impersonate("  ")).toThrow(RangeError);
    expect(() => Tm1Session.builder().impersonate("a\nb")).toThrow(/control character/);
    Tm1Session.builder().impersonate("carol smith");
  });

  it("sends a name outside ASCII as its UTF-8 bytes", async () => {
    const tm1 = scripted();
    await tm1.session((b) => b.impersonate("zoë").sessionContext("café")).execute(SERVER);

    // One character for each UTF-8 byte: fetch writes a header's characters as bytes
    const login = tm1.requests(LOGIN)[0]!;
    expect(Buffer.from(login.header("TM1-Impersonate")!, "latin1")).toEqual(Buffer.from("zoë", "utf8"));
    expect(Buffer.from(login.header("TM1-SessionContext")!, "latin1")).toEqual(Buffer.from("café", "utf8"));
  });

  it("puts those bytes on the wire with Node's fetch", async () => {
    // A real server: Node reads a header's bytes as Latin-1, one character each
    const seen: Record<string, string | undefined>[] = [];
    const server = createServer((request, response) => {
      seen.push({
        impersonate: request.headers["tm1-impersonate"] as string | undefined,
        context: request.headers["tm1-sessioncontext"] as string | undefined,
      });
      request.resume();
      if (request.method === "GET")
        response.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "TM1SessionId=c1; Path=/api/; HttpOnly" }).end('{"ID":1}');
      else response.writeHead(204).end();
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      const root = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/v1/`;
      const session = Tm1Session.builder().baseUrl(root).basicAuth("admin", "").impersonate("zoë_日本").sessionContext("café").build();
      await session.connect();
      await session.close();
      expect(Buffer.from(seen[0]!.impersonate!, "latin1").toString("utf8")).toBe("zoë_日本");
      expect(Buffer.from(seen[0]!.context!, "latin1").toString("utf8")).toBe("café");
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  });

  it("reports an unknown user", async () => {
    const tm1 = scripted().on(LOGIN, () => ScriptedTm1.text("Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n", 400));
    const e = await failure(tm1.session((b) => b.impersonate("nobody")).execute(SERVER));
    expect(e).toBeInstanceOf(Tm1BadRequestException);
    expect((e as Tm1BadRequestException).kind).toBe("UnknownUser");
  });

  it("reports a refused impersonation whatever the wording", async () => {
    const tm1 = scripted().on(LOGIN, () => ScriptedTm1.text("Error: Forbidden\r\nSome other version's words.\r\n", 403));
    const e = await failure(tm1.session((b) => b.impersonate("Admin")).execute(SERVER));
    expect(e).toBeInstanceOf(Tm1ForbiddenException);
    expect((e as Tm1ForbiddenException).kind).toBe("ImpersonationRefused");
  });

  it("names both causes of a 401 when impersonating", async () => {
    const tm1 = scripted().on(LOGIN, () => ScriptedTm1.status(401));
    const e = await failure(tm1.session((b) => b.impersonate("bob")).execute(SERVER));
    expect(e).toBeInstanceOf(Tm1AuthenticationException);
    expect((e as Tm1AuthenticationException).kind).toBe("CredentialRejected");
    expect((e as Error).message).toContain("bob");
    expect((e as Error).message).toContain("disabled");
  });

  it("remembers a refused login and fails fast until the back-off ends", async () => {
    now = 0;
    const tm1 = scripted().onEach(
      LOGIN,
      () => ScriptedTm1.status(401),
      () => ScriptedTm1.status(401),
      () => ScriptedTm1.json(200, '{"ID":9}', "TM1SessionId=ok; Path=/api/"),
    );
    const s = session(tm1, Tm1Timeouts.builder().refusedLogin(30_000).build());

    await expect(s.execute(SERVER)).rejects.toBeInstanceOf(Tm1AuthenticationException);
    now += 29_000;
    await expect(s.execute(SERVER)).rejects.toBeInstanceOf(Tm1AuthenticationException);
    expect(tm1.requests(LOGIN), "within the back-off: no new login").toHaveLength(1);

    now += 2_000;
    await expect(s.execute(SERVER)).rejects.toBeInstanceOf(Tm1AuthenticationException);
    expect(tm1.requests(LOGIN), "after the back-off: tried again").toHaveLength(2);

    now += 31_000;
    await s.execute(SERVER);
  });

  it("retries at once after a failure that isn't a refusal", async () => {
    const tm1 = scripted().onEach(
      LOGIN,
      () => {
        throw systemError("ECONNRESET", "Connection reset");
      },
      () => ScriptedTm1.json(200, '{"ID":9}', "TM1SessionId=ok; Path=/api/"),
    );
    const s = session(tm1, Tm1Timeouts.DEFAULTS);
    await expect(s.execute(SERVER)).rejects.toBeInstanceOf(Tm1TransportException);
    await s.execute(SERVER);
  });

  it("tries again at once when the back-off is zero", async () => {
    const tm1 = scripted().onEach(
      LOGIN,
      () => ScriptedTm1.status(401),
      () => ScriptedTm1.json(200, '{"ID":9}', "TM1SessionId=ok; Path=/api/"),
    );
    const s = session(tm1, Tm1Timeouts.builder().refusedLogin(0).build());
    await expect(s.execute(SERVER)).rejects.toBeInstanceOf(Tm1ApiException);
    await s.execute(SERVER);
  });
});

/** The Java client's `Tm1SessionViewsTest`: sandbox views sharing one login, and the session context. */
describe("Tm1Session views", () => {
  const CUBE = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"));
  const scripted = (): ScriptedTm1 =>
    new ScriptedTm1()
      .on("GET Cubes('Sales')?$select=Name", () => ScriptedTm1.json(200, "{}"))
      .on("GET Cubes('Sales')?$select=Name&!sandbox=What-if%201", () => ScriptedTm1.json(200, "{}"))
      .on("GET Cubes('Sales')?$select=Name&!sandbox=Mine", () => ScriptedTm1.json(200, "{}"));

  it("adds the sandbox to every request of the view and shares the login", async () => {
    const tm1 = scripted();
    const base = tm1.session();
    const whatIf = base.withSandbox("What-if 1");

    await whatIf.execute(CUBE);
    await base.execute(CUBE);
    await whatIf.withoutSandbox().execute(CUBE);

    expect(tm1.requests("GET Cubes('Sales')?$select=Name&!sandbox=What-if%201")).toHaveLength(1);
    expect(tm1.requests("GET Cubes('Sales')?$select=Name")).toHaveLength(2);
    expect(tm1.requests(LOGIN), "views share one login").toHaveLength(1);
    expect(whatIf.sandbox()).toBe("What-if 1");
    expect(base.sandbox()).toBeUndefined();
    expect(whatIf.toString()).toBe(`Tm1Session[${ROOT}, sandbox=What-if 1]`);
  });

  it("keeps a sandbox the request sets itself", async () => {
    const tm1 = scripted();
    await tm1
      .session()
      .withSandbox("What-if 1")
      .execute(CUBE.withQuery(CUBE.query.option("!sandbox", "Mine")));
    expect(tm1.requests("GET Cubes('Sales')?$select=Name&!sandbox=Mine")).toHaveLength(1);
  });

  it("adds the change set, keeping the sandbox", async () => {
    const tm1 = scripted().on("GET Cubes('Sales')?$select=Name&!sandbox=Mine&!ChangeSet=7", () => ScriptedTm1.json(200, "{}"));
    const view = tm1.session().withSandbox("Mine").withChangeSet("7");
    await view.execute(CUBE);
    expect(tm1.requests("GET Cubes('Sales')?$select=Name&!sandbox=Mine&!ChangeSet=7")).toHaveLength(1);
    expect(view.changeSet()).toBe("7");
    expect(view.withoutChangeSet().changeSet()).toBeUndefined();
    expect(view.withoutChangeSet().sandbox()).toBe("Mine");
  });

  it("closing a view closes the shared session", async () => {
    const tm1 = scripted();
    const base = tm1.session();
    const whatIf = base.withSandbox("x");
    await base.connect();
    await whatIf.close();
    expect(base.isClosed()).toBe(true);
    expect(tm1.requests(CLOSE)).toHaveLength(1);
  });

  it("sends the session context with the login only", async () => {
    const tm1 = scripted();
    await tm1.session((b) => b.sessionContext("planning-app")).execute(CUBE);
    expect(tm1.requests(LOGIN)[0]!.header("TM1-SessionContext")).toBe("planning-app");
    expect(tm1.requests("GET Cubes('Sales')?$select=Name")[0]!.header("TM1-SessionContext")).toBeUndefined();
  });

  it("refuses blank sandboxes and overlong contexts", () => {
    expect(() => scripted().session().withSandbox(" ")).toThrow(RangeError);
    expect(() => scripted().session().withChangeSet("")).toThrow(RangeError);
    expect(() => Tm1Session.builder().sessionContext("x".repeat(251))).toThrow(RangeError);
    expect(() => scripted().session().withAsync(0)).toThrow(RangeError);
  });
});

/** The Java client's `Tm1SessionBatchTest`: requests queued, sent as one JSON `$batch`, each with
 * its own result. */
describe("Tm1Session batches", () => {
  const BATCH = "POST $batch";

  it("sends queued requests together and hands each its result", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () =>
      ScriptedTm1.json(
        200,
        `{"responses":[
         {"id":"1","status":200,"body":{"Name":"Sales"}},
         {"id":"2","status":404,"body":{"error":{"code":"278","message":"'Nope' can not be found"}}},
         {"id":"3","status":200,"body":{"value":[{"Name":"a"},{"Name":"b"}]}},
         {"id":"4","status":204}
        ]}`,
      ),
    );
    const batch = tm1.session().withSandbox("s").batch();
    const queued = batch.session;
    const sales = queued.execute(Tm1Request.get<{ Name: string }>(ODataPath.of("Cubes").key("Sales"), ODataQuery.none()));
    const nope = queued.execute(Tm1Request.get(ODataPath.of("Cubes").key("Nope"), ODataQuery.none()));
    const listed = queued.stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Cubes"), ODataQuery.none())).toArray();
    const deleted = queued.execute(Tm1Request.delete(ODataPath.of("Cubes").key("Old")));
    expect(batch.size()).toBe(4);
    await settle();
    expect(tm1.requests(BATCH), "nothing sent before run").toHaveLength(0);

    await batch.run();
    expect((await sales).Name).toBe("Sales");
    await expect(nope).rejects.toBeInstanceOf(Tm1NotFoundException);
    expect(await deleted).toBeUndefined();
    expect((await listed).map((n) => n.Name)).toEqual(["a", "b"]);

    expect(tm1.requests(BATCH), "one $batch").toHaveLength(1);
    const sent = tm1.requests(BATCH)[0]!.json.requests;
    expect(sent.map((r: { id: string; method: string; url: string }) => `${r.id} ${r.method} ${r.url}`)).toEqual([
      "1 GET Cubes('Sales')?!sandbox=s",
      "2 GET Cubes('Nope')?!sandbox=s",
      "3 GET Cubes?!sandbox=s",
      "4 DELETE Cubes('Old')?!sandbox=s",
    ]);
    expect(tm1.requests(BATCH)[0]!.key, "the $batch itself carries no sandbox").toBe(BATCH);
  });

  it("sends a write's body with its content type", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[{"id":"1","status":201,"body":{"Name":"d"}}]}'));
    const batch = tm1.session().batch();
    const created = batch.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: "d" }).ifMatch("*"));
    await batch.run();
    await created;
    expect(tm1.requests(BATCH)[0]!.json.requests[0]).toEqual({
      id: "1",
      method: "POST",
      url: "Dimensions",
      headers: { accept: "application/json", "If-Match": "*", "content-type": "application/json" },
      body: { Name: "d" },
    });
  });

  it("refuses calls after it ran", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[]}'));
    const batch = tm1.session().batch();
    await batch.run();
    await expect(batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("x")))).rejects.toThrow(/already run/);
    await expect(batch.run()).rejects.toThrow(/already run/);
  });

  it("fails a request TM1's answer has no response for", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[]}'));
    const batch = tm1.session().batch();
    const lost = batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("x")));
    await batch.run();
    const e = await failure(lost);
    expect(e).toBeInstanceOf(Tm1ProtocolException);
    expect((e as Error).message).toContain("no answer for request 1");
  });

  it("refuses a call whose body can't be written and still runs the rest", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[{"id":"1","status":204}]}'));
    const batch = tm1.session().batch();
    const deleted = batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("Old")));
    const body = {
      get Name(): string {
        throw new Error("not readable");
      },
    };
    const unwritable = batch.session.execute(Tm1Request.post(ODataPath.of("Cubes"), body, Json));

    // Before the batch runs: the call fails where it was made
    const e = await Promise.race([failure(unwritable), settle().then(() => "still waiting")]);
    expect(e).toBeInstanceOf(TypeError);
    expect(batch.size(), "never queued").toBe(1);
    await batch.run();
    expect(await deleted).toBeUndefined();
    expect(tm1.requests(BATCH)).toHaveLength(1);
  });

  it("sends a body as it was when the call was queued", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[{"id":"1","status":204}]}'));
    const batch = tm1.session().batch();
    const body = { Name: "before" };
    const created = batch.session.execute(Tm1Request.post(ODataPath.of("Dimensions"), body));
    body.Name = "after";
    await batch.run();
    await created;
    expect(tm1.requests(BATCH)[0]!.json.requests[0].body).toEqual({ Name: "before" });
  });

  it("fails every call when TM1 answers the batch with no body", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.status(200));
    const batch = tm1.session().batch();
    const first = batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("a")));
    const second = batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("b")));

    await batch.run();
    expect(await Promise.race([failure(first), settle().then(() => "still waiting")])).toBeInstanceOf(Tm1ProtocolException);
    expect(await Promise.race([failure(second), settle().then(() => "still waiting")])).toBeInstanceOf(Tm1ProtocolException);
  });

  it("follows a next link in an answer", async () => {
    const tm1 = new ScriptedTm1()
      .on(BATCH, () => ScriptedTm1.json(200, '{"responses":[{"id":"1","status":200,"body":{"value":[{"Name":"a"}],"@odata.nextLink":"Cubes?$skiptoken=2"}}]}'))
      .on("GET Cubes?$skiptoken=2", () => ScriptedTm1.json(200, '{"value":[{"Name":"b"}]}'));
    const batch = tm1.session().batch();
    const names = batch.session
      .stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Cubes"), ODataQuery.none()))
      .map((cube) => cube.Name)
      .toArray();

    await batch.run();
    expect(await names).toEqual(["a", "b"]);
    expect(tm1.requests(BATCH), "the pages after it aren't part of the batch").toHaveLength(1);
  });

  it("splits a big queue", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[]}'));
    const batch = tm1.session().batch();
    for (let i = 0; i < 5; i++) batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("c" + i))).catch(() => undefined);
    await batch.run(2);
    expect(tm1.requests(BATCH)).toHaveLength(3);
    expect(tm1.requests(BATCH).map((b) => b.json.requests.map((r: { id: string }) => r.id))).toEqual([["1", "2"], ["3", "4"], ["5"]]);
  });

  it("sends 500 requests to a $batch unless told otherwise, and needs room for one", async () => {
    expect(Tm1SessionBatch.DEFAULT_PER_REQUEST).toBe(500);
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(200, '{"responses":[]}'));
    const batch = tm1.session().batch();
    for (let i = 0; i < 501; i++) batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("c" + i))).catch(() => undefined);
    expect(() => batch.run(0)).toThrow("A batch needs room for at least one request: 0");
    await batch.run();
    expect(tm1.requests(BATCH).map((b) => b.json.requests.length)).toEqual([500, 1]);
  });

  it("fails every request of a $batch TM1 refused", async () => {
    const tm1 = new ScriptedTm1().on(BATCH, () => ScriptedTm1.json(400, '{"error":{"code":"278","message":"The content type specified is not supported."}}'));
    const batch = tm1.session().batch();
    const a = batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("a")));
    const b = batch.session.execute(Tm1Request.delete(ODataPath.of("Cubes").key("b")));
    await batch.run();
    await expect(a).rejects.toBeInstanceOf(Tm1BadRequestException);
    await expect(b).rejects.toBeInstanceOf(Tm1BadRequestException);
  });

  it("refuses raw bytes", async () => {
    const batch = new ScriptedTm1().session().batch();
    await expect(batch.session.execute(Tm1Request.put(ODataPath.of("Contents").key("Blobs"), new Uint8Array([1])))).rejects.toThrow(/Raw bytes/);
  });
});

/** The Java client's `Tm1SessionAsyncTest`: `Prefer: respond-async`, polling the monitor, and
 * deleting it on cancel. */
describe("Tm1Session async", () => {
  const RUN = "POST Processes('p')/tm1.ExecuteWithReturn";
  const MONITOR = "GET _async('a1')";
  const REQUEST = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), null, Json) as Tm1Request<{
    ProcessExecuteStatusCode: string;
  }>;
  const accepted = () => ScriptedTm1.bytes(202, new Uint8Array(), "text/plain", { location: "../_async('a1')" });
  const done = (statusLine: string, body: string) =>
    ScriptedTm1.bytes(
      200,
      new TextEncoder().encode(`HTTP/1.1 ${statusLine}\r\nContent-Type: application/json\r\nOData-Version: 4.0\r\n\r\n${body}`),
      "application/http",
    );

  it("polls the monitor until the result is there", async () => {
    const tm1 = new ScriptedTm1().on(RUN, accepted).onEach(
      MONITOR,
      () => ScriptedTm1.status(202),
      () => ScriptedTm1.status(202),
      () => done("201 Created", '{"ProcessExecuteStatusCode":"CompletedSuccessfully"}'),
    );
    const result = await tm1.session().withAsync(10).execute(REQUEST);
    expect(result.ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
    expect(tm1.requests(RUN)).toHaveLength(1);
    expect(tm1.requests(RUN)[0]!.header("Prefer")).toBe("respond-async");
    expect(tm1.requests(MONITOR)).toHaveLength(3);
    expect(tm1.requests("DELETE _async('a1')"), "not deleted once done").toHaveLength(0);
  });

  it("raises the error the work ended with", async () => {
    const tm1 = new ScriptedTm1()
      .on(RUN, accepted)
      .on(MONITOR, () => done("500 Internal Server Error", '{"error":{"code":"174","message":"TM1UserException: Cancel"}}'));
    const e = await failure(tm1.session().withAsync(10).execute(REQUEST));
    expect(e).toBeInstanceOf(Tm1ServerException);
    expect((e as Error).message).toContain("Cancel");
    expect(tm1.requests("DELETE _async('a1')"), "a failure isn't a cancel").toHaveLength(0);
  });

  it("reads an answer TM1 gave at once", async () => {
    const tm1 = new ScriptedTm1().on(RUN, () => ScriptedTm1.json(201, '{"ProcessExecuteStatusCode":"Aborted"}'));
    const result = await tm1.session().withAsync(10).execute(REQUEST);
    expect(result.ProcessExecuteStatusCode).toBe("Aborted");
    expect(tm1.requests(RUN), "sent once").toHaveLength(1);
  });

  it("reads a result bigger than the codec's buffer limit", async () => {
    // The Java client's codecs refused to buffer more than 256 KB; nothing here has such a limit
    const big = "x".repeat(300_000);
    const tm1 = new ScriptedTm1().on(RUN, accepted).on(MONITOR, () => done("200 OK", `{"Text":"${big}"}`));
    const result = await tm1
      .session()
      .withAsync(10)
      .execute(REQUEST as Tm1Request<unknown>);
    expect((result as { Text: string }).Text).toHaveLength(300_000);
  });

  it("reads a big collection TM1 gave at once", async () => {
    const big = "x".repeat(300_000);
    const tm1 = new ScriptedTm1().on("GET Cubes", () => ScriptedTm1.json(200, `{"value":[{"Name":"${big}"}]}`));
    const cubes = await tm1
      .session()
      .withAsync(10)
      .stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Cubes"), ODataQuery.none()))
      .toArray();
    expect(cubes).toHaveLength(1);
    expect(cubes[0]!.Name).toHaveLength(300_000);
  });

  it("follows a next link in a result", async () => {
    const tm1 = new ScriptedTm1()
      .on("GET Cubes", () => ScriptedTm1.json(200, '{"value":[{"Name":"a"}],"@odata.nextLink":"Cubes?$skiptoken=2"}'))
      .on("GET Cubes?$skiptoken=2", () => ScriptedTm1.json(200, '{"value":[{"Name":"b"}]}'));
    const names = await tm1
      .session()
      .withAsync(10)
      .stream(Tm1Request.list<{ Name: string }>(ODataPath.of("Cubes"), ODataQuery.none()))
      .map((cube) => cube.Name)
      .toArray();
    expect(names).toEqual(["a", "b"]);
    expect(tm1.requests("GET Cubes")[0]!.header("Prefer")).toBe("respond-async");
    expect(tm1.requests("GET Cubes?$skiptoken=2")[0]!.header("Prefer"), "the pages after it are ordinary requests").toBeUndefined();
  });

  it("reads a big error body", async () => {
    const big = "x".repeat(300_000);
    const tm1 = new ScriptedTm1().on(RUN, () => ScriptedTm1.json(500, `{"error":{"code":"174","message":"${big}"}}`));
    const e = await failure(tm1.session().execute(REQUEST));
    expect(e).toBeInstanceOf(Tm1ServerException);
    expect((e as Tm1ServerException).error!.message).toHaveLength(300_000);
  });

  it("reads a big result and a big error body with Node's fetch", async () => {
    // A real server, so the bodies come through fetchTransport, the default, in several chunks
    const big = "x".repeat(300_000);
    const server = createServer((request, response) => {
      request.resume();
      const path = request.url ?? "";
      if (path.includes("ActiveSession?"))
        response.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "TM1SessionId=c1; Path=/api/; HttpOnly" }).end('{"ID":1}');
      else if (path.includes("tm1.ExecuteWithReturn")) response.writeHead(202, { Location: "../_async('a1')" }).end();
      else if (path.includes("_async"))
        response.writeHead(200, { "Content-Type": "application/http" }).end(`HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"Text":"${big}"}`);
      else if (path.includes("Broken")) response.writeHead(500, { "Content-Type": "application/json" }).end(`{"error":{"code":"174","message":"${big}"}}`);
      else response.writeHead(204).end();
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      const root = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/v1/`;
      const session = Tm1Session.builder().baseUrl(root).basicAuth("admin", "").build();
      const result = await session.withAsync(10).execute(REQUEST as Tm1Request<unknown>);
      expect((result as { Text: string }).Text).toHaveLength(300_000);
      const e = await failure(session.execute(Tm1Request.get(ODataPath.of("Broken"), ODataQuery.none())));
      expect(e).toBeInstanceOf(Tm1ServerException);
      expect((e as Tm1ServerException).error!.message).toHaveLength(300_000);
      await session.close();
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  });

  it("deletes the monitor when cancelled", async () => {
    const tm1 = new ScriptedTm1()
      .on(RUN, accepted)
      .on(MONITOR, () => ScriptedTm1.status(202))
      .on("DELETE _async('a1')", () => ScriptedTm1.status(204));
    const cancel = new AbortController();
    const running = tm1.session().withAsync(10).execute(REQUEST, { signal: cancel.signal });
    await eventually(() => tm1.requests(MONITOR).length >= 2, "for the polls");
    cancel.abort();
    await expect(running).rejects.toBeDefined();
    await eventually(() => tm1.requests("DELETE _async('a1')").length === 1, "for the delete");
    await new Promise((r) => setTimeout(r, 50));
    expect(tm1.requests("DELETE _async('a1')")).toHaveLength(1);
    expect(tm1.requests("DELETE _async('a1')")[0]!.cookie, "within the session").toBe("c1");
  });

  it("unwraps an application/http message", async () => {
    const result = await asyncResult(new TextEncoder().encode("HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\n{}"));
    expect(result.status).toBe(201);
    expect(new TextDecoder().decode(result.body)).toBe("{}");
    await expect(asyncResult(new TextEncoder().encode("garbage"))).rejects.toBeInstanceOf(Tm1ProtocolException);
  });

  it("decompresses a gzipped inner body", async () => {
    const head = new TextEncoder().encode("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: application/json\r\n\r\n");
    const zipped = gzipSync('{"Name":"tm1"}');
    const message = new Uint8Array(head.length + zipped.length);
    message.set(head);
    message.set(zipped, head.length);
    expect(new TextDecoder().decode((await asyncResult(message)).body)).toBe('{"Name":"tm1"}');
  });
});

/** The Java client's `Tm1SessionCapabilitiesTest`: the version and `$metadata`, through the session. */
describe("Tm1Session capabilities", () => {
  const VERSION = "GET Server?$select=ProductVersion";
  const METADATA = "GET $metadata";
  const scripted = (): ScriptedTm1 =>
    new ScriptedTm1()
      .on(VERSION, () => ScriptedTm1.json(200, '{"ProductVersion":"11.8.03500.6"}'))
      .on(METADATA, () => ScriptedTm1.xml(SNAPSHOT))
      .on("GET Cubes('Sales')/tm1.DimensionsStorageOrder()", () => ScriptedTm1.json(200, "{}"));

  it("reads the version once inside the session", async () => {
    const tm1 = scripted();
    const session = tm1.session();
    expect((await session.version()).equals(Tm1Version.parse("11.8.03500.6"))).toBe(true);
    await session.version();
    expect(tm1.requests(VERSION)).toHaveLength(1);
    expect(tm1.requests(VERSION)[0]!.cookie, "never an anonymous read").toBeDefined();
  });

  it("fetches $metadata once per server version for every session with the same settings", async () => {
    const tm1 = scripted();
    const settings = tm1.builder().basicAuth("admin", "apple");
    const first = settings.build();
    const second = settings.build();
    const c = await first.capabilities();
    expect(c.schemaVersion).toBe("11.8.35");
    expect(c.productVersion!.equals(Tm1Version.parse("11.8.03500.6"))).toBe(true);
    expect(await second.capabilities()).toBe(c);
    expect(tm1.requests(METADATA)).toHaveLength(1);
    expect(tm1.requests(METADATA)[0]!.header("Accept")).toBe("application/xml");
  });

  it("sends a request whose requirement the server meets", async () => {
    const order = Tm1Request.get(ODataPath.of("Cubes").key("Sales").function("tm1.DimensionsStorageOrder", {}), ODataQuery.none()).requires(
      Tm1Capability.boundOperation("tm1.Cube", "tm1.DimensionsStorageOrder").since("11.4.0"),
    );
    const tm1 = scripted();
    await tm1.session().execute(order);
    expect(tm1.requests("GET Cubes('Sales')/tm1.DimensionsStorageOrder()")).toHaveLength(1);
  });

  it("refuses a request the server can't serve without sending it", async () => {
    const bananas = Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none()).requires(Tm1Capability.entitySet("Bananas").since("99.0"));
    const tm1 = scripted();
    const e = await failure(tm1.session().stream(bananas).toArray());
    expect(e).toBeInstanceOf(Tm1UnsupportedOperationException);
    const u = e as Tm1UnsupportedOperationException;
    expect(u.missing).toBe("entity set Bananas");
    expect(u.message).toContain("added in schema 99.0");
    expect(u.message).toContain("reports schema 11.8.35");
    expect(tm1.requests("GET Bananas")).toHaveLength(0);
  });

  it("refuses a request the server can't serve in a batch and still sends the rest", async () => {
    const tm1 = scripted().on("POST $batch", () => ScriptedTm1.json(200, '{"responses":[{"id":"2","status":200,"body":{}}]}'));
    const batch = tm1.session().batch();
    const bananas = batch.session
      .stream(Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none()).requires(Tm1Capability.entitySet("Bananas").since("99.0")))
      .toArray();
    const server = batch.session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), Json));

    await batch.run();
    expect(await Promise.race([failure(bananas), settle().then(() => "still waiting")])).toBeInstanceOf(Tm1UnsupportedOperationException);
    expect(await server).toEqual({});
    const sent = tm1.requests("POST $batch");
    expect(sent).toHaveLength(1);
    expect(sent[0]!.text).toContain("Server");
    expect(sent[0]!.text).not.toContain("Bananas");
  });

  it("fails the calls that need the capabilities when they can't be read, and sends the rest", async () => {
    const tm1 = scripted()
      .on(METADATA, () => ScriptedTm1.json(503, '{"error":{"code":"1","message":"busy"}}'))
      .on("POST $batch", () => ScriptedTm1.json(200, '{"responses":[{"id":"2","status":200,"body":{}}]}'));
    const batch = tm1.session().batch();
    const bananas = batch.session.execute(
      Tm1Request.get(ODataPath.of("Bananas"), ODataQuery.none(), Json).requires(Tm1Capability.entitySet("Bananas").since("99.0")),
    );
    const server = batch.session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), Json));

    await batch.run();
    expect(await Promise.race([failure(bananas), settle().then(() => "still waiting")])).toBeInstanceOf(Tm1ServerException);
    expect(await server).toEqual({});
    expect(tm1.requests("POST $batch")[0]!.text).not.toContain("Bananas");
  });

  it("doesn't check in a batch what a session told not to check needs", async () => {
    const tm1 = scripted().on("POST $batch", () => ScriptedTm1.json(200, '{"responses":[{"id":"1","status":200,"body":{}}]}'));
    const batch = tm1.session((b) => b.checkCapabilities(false)).batch();
    const bananas = batch.session.execute(
      Tm1Request.get(ODataPath.of("Bananas"), ODataQuery.none(), Json).requires(Tm1Capability.entitySet("Bananas").since("99.0")),
    );
    await batch.run();
    expect(await bananas).toEqual({});
    expect(tm1.requests(METADATA)).toHaveLength(0);
  });

  it("doesn't check what a session told not to check needs", async () => {
    const bananas = Tm1Request.get(ODataPath.of("Bananas"), ODataQuery.none()).requires(Tm1Capability.entitySet("Bananas").since("99.0"));
    const tm1 = scripted().on("GET Bananas", () => ScriptedTm1.json(200, "{}"));
    await tm1.session((b) => b.checkCapabilities(false)).execute(bananas);
    expect(tm1.requests(METADATA)).toHaveLength(0);
  });

  it("reads the version again after logging in again", async () => {
    const tm1 = scripted().onEach(
      "GET Server",
      () => ScriptedTm1.status(401, "TM1SessionId=fresh; Path=/api/"),
      () => ScriptedTm1.json(200, "{}"),
    );
    const session = tm1.session();
    await session.version();
    await session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none()));
    await session.version();
    expect(tm1.requests(VERSION)).toHaveLength(2);
  });

  it("reports $metadata that isn't CSDL", async () => {
    const tm1 = scripted().on(METADATA, () => ScriptedTm1.xml("<html>Not metadata</html>"));
    await expect(tm1.session().capabilities()).rejects.toBeInstanceOf(Tm1ProtocolException);
  });

  it("reports $metadata it can't model", async () => {
    // A bound operation with no binding parameter, which the model refuses
    const tm1 = scripted().on(METADATA, () =>
      ScriptedTm1.xml(
        '<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices>' +
          '<Schema Namespace="ibm.tm1.api.v1" xmlns="http://docs.oasis-open.org/odata/ns/edm"><Action Name="Reserve" IsBound="true"/></Schema>' +
          "</edmx:DataServices></edmx:Edmx>",
      ),
    );
    const e = await failure(tm1.session().capabilities());
    expect(e).toBeInstanceOf(Tm1ProtocolException);
    expect((e as Tm1ProtocolException).url).toBe(ROOT + "$metadata");
  });

  it("doesn't keep a failed read", async () => {
    const tm1 = scripted().onEach(
      VERSION,
      () => ScriptedTm1.json(503, '{"error":{"code":"1","message":"busy"}}'),
      () => ScriptedTm1.json(200, '{"ProductVersion":"11.8.03500.6"}'),
    );
    const session = tm1.session();
    await expect(session.version()).rejects.toBeInstanceOf(Tm1ServerException);
    expect((await session.version()).toString()).toBe("11.8.03500.6");
  });
});

/** The Java client's `ODataCollectionDecoderTest`, as far as it applies: TypeScript reads each page
 * whole, so what's left is what a page holds, and what isn't a collection. */
describe("Tm1Session reading collections", () => {
  const ELEMENTS = Tm1Request.list<{ Name: string; Type: string }>(ODataPath.of("Elements"), ODataQuery.none());
  const BODY = `{"@odata.context":"$metadata#Elements","@odata.count":3,"value":[
{"@odata.etag":"W/\\"1\\"","Name":"Q1","Type":"Numeric"},
{"Name":"Q'2 {x}","Type":"Numeric","Extra":{"nested":[1,[2,{"a":null}]]}},
{"Name":"Total","Type":"Consolidated"}],"@odata.nextLink":"Elements?$skiptoken=3"}`;
  const read = (body: string, status = 200): Promise<unknown[]> =>
    new ScriptedTm1()
      .on("GET Elements", () => ScriptedTm1.json(status, body))
      .session()
      .stream(Tm1Request.list(ODataPath.of("Elements"), ODataQuery.none()))
      .toArray();

  it("follows next links as given", async () => {
    const tm1 = new ScriptedTm1()
      .on("GET Elements", () => ScriptedTm1.json(200, BODY))
      .on("GET Elements?$skiptoken=3", () => ScriptedTm1.json(200, `{"value":[{"Name":"p2"}],"@odata.nextLink":"Elements?$skiptoken=4&x='a b'"}`))
      .on("GET Elements?$skiptoken=4&x='a b'", () => ScriptedTm1.json(200, '{"value":[{"Name":"p3"}]}'));
    const all = await tm1.session().stream(ELEMENTS).toArray();
    expect(all.map((e) => e.Name)).toEqual(["Q1", "Q'2 {x}", "Total", "p2", "p3"]);
    expect(all[0]!.Type).toBe("Numeric");
    expect(
      tm1
        .requests()
        .filter((r) => r.key.startsWith("GET Elements?"))
        .map((r) => r.key),
    ).toEqual(["GET Elements?$skiptoken=3", "GET Elements?$skiptoken=4&x='a b'"]);
  });

  it("asks for buffers only as items are taken", async () => {
    // Java's decoder reads a page buffer by buffer; here a page is read whole, so what's read no
    // faster than the items are taken is the pages
    const tm1 = new ScriptedTm1()
      .on("GET Elements", () => ScriptedTm1.json(200, BODY))
      .on("GET Elements?$skiptoken=3", () => ScriptedTm1.json(200, '{"value":[{"Name":"p2"}]}'));
    const session = tm1.session();

    expect((await session.stream(ELEMENTS).first())!.Name).toBe("Q1");
    let taken = 0;
    for await (const element of session.stream(ELEMENTS)) {
      expect(element.Name).toBeDefined();
      if (++taken === 3) break;
    }
    await settle();
    expect(tm1.requests("GET Elements"), "one request for each time it was iterated").toHaveLength(2);
    expect(tm1.requests("GET Elements?$skiptoken=3"), "no more than the items taken need").toHaveLength(0);
  });

  /** A real server whose `Elements` sends the start of a collection and then nothing more, and which
   * records when the connection carrying it has gone. */
  async function stalling(use: (root: string, state: { asked: number; released: number }) => Promise<void>): Promise<void> {
    const state = { asked: 0, released: 0 };
    const server = createServer((request, response) => {
      request.resume();
      const path = request.url ?? "";
      if (path.includes("ActiveSession?"))
        response.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "TM1SessionId=c1; Path=/api/; HttpOnly" }).end('{"ID":1}');
      else if (path.includes("Elements")) {
        state.asked++;
        response.on("close", () => {
          if (!response.writableEnded) state.released++;
        });
        response.writeHead(200, { "Content-Type": "application/json" });
        response.write(BODY.slice(0, BODY.indexOf("Total")));
      } else response.writeHead(204).end();
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      await use(`http://127.0.0.1:${(server.address() as AddressInfo).port}/api/v1/`, state);
    } finally {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    }
  }

  it("releases buffers it hasn't read when cancelled", async () => {
    // What's held here isn't Java's pooled buffers but the response and its connection, which
    // Node's fetch gives up when aborted
    await stalling(async (root, state) => {
      const session = Tm1Session.builder().baseUrl(root).basicAuth("admin", "").build();
      const cancel = new AbortController();
      const reading = session.stream(ELEMENTS, { signal: cancel.signal }).toArray();
      await eventually(() => state.asked === 1, "for the request");
      await settle();
      cancel.abort();
      await expect(reading).rejects.toBeDefined();
      await eventually(() => state.released === 1, "for the response to be released");
      await session.close();
    });
  });

  it("releases a response it hasn't read when the request fails", async () => {
    await stalling(async (root, state) => {
      const session = Tm1Session.builder().baseUrl(root).basicAuth("admin", "").build();
      const e = await failure(session.stream(ELEMENTS.withTimeout(200)).toArray());
      expect(e).toBeInstanceOf(Tm1TransportException);
      expect((e as Tm1TransportException).phase).toBe("Response");
      await eventually(() => state.released === 1, "for the response to be released");
      await session.close();
    });
  });

  /** A real server whose `Elements` sends `parts` one after the other, each in a chunk of its own,
   * as the network may split a body: Node's fetch puts them together. A next link reads as empty. */
  async function splitting(use: (read: (...parts: Uint8Array[]) => Promise<unknown[]>) => Promise<void>): Promise<void> {
    let parts: Uint8Array[] = [];
    const server = createServer((request, response) => {
      request.resume();
      const path = request.url ?? "";
      if (path.includes("ActiveSession?"))
        response.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "TM1SessionId=c1; Path=/api/; HttpOnly" }).end('{"ID":1}');
      else if (path.includes("skiptoken")) response.writeHead(200, { "Content-Type": "application/json" }).end('{"value":[]}');
      else if (path.includes("Elements")) {
        response.writeHead(200, { "Content-Type": "application/json" });
        const send = async (): Promise<void> => {
          for (const part of parts) {
            response.write(part);
            await new Promise((resolve) => setImmediate(resolve));
          }
          response.end();
        };
        void send();
      } else response.writeHead(204).end();
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const session = Tm1Session.builder()
      .baseUrl(`http://127.0.0.1:${(server.address() as AddressInfo).port}/api/v1/`)
      .basicAuth("admin", "")
      .build();
    try {
      await use((...p) => {
        parts = p;
        return session.stream(ELEMENTS).toArray();
      });
    } finally {
      await session.close();
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    }
  }

  /** `bytes` cut in two at `at`. */
  const cut = (bytes: Uint8Array, at: number): Uint8Array[] => [bytes.subarray(0, at), bytes.subarray(at)];

  it("gives the same result however the bytes are split", async () => {
    const bytes = new TextEncoder().encode(BODY);
    const expected = (JSON.parse(BODY) as { value: unknown[] }).value;
    await splitting(async (read) => {
      for (let at = 1; at < bytes.length; at++) expect(await read(...cut(bytes, at)), `split at ${at}`).toEqual(expected);
      const random = seeded(7);
      for (let i = 0; i < 200; i++) {
        const parts: Uint8Array[] = [];
        for (let from = 0; from < bytes.length;) {
          const to = Math.min(bytes.length, from + 1 + random(40));
          parts.push(bytes.subarray(from, to));
          from = to;
        }
        expect(await read(...parts)).toEqual(expected);
      }
    });
  });

  it("handles multibyte characters split across buffers", async () => {
    const bytes = new TextEncoder().encode('{"value":[{"Name":"Région 日本 😀","Type":"String"}]}');
    await splitting(async (read) => {
      for (let at = 1; at < bytes.length; at++) expect(await read(...cut(bytes, at)), `split at ${at}`).toEqual([{ Name: "Région 日本 😀", Type: "String" }]);
    });
  });

  it("reads bytes that aren't UTF-8 as the replacement character, however they are split", async () => {
    // As TM1's transaction log gives a password's hash, with "é" (two bytes) beside it. Java's test
    // goes on to its Utf8Repair, which mends a character cut between buffers; Node's fetch reads the
    // body whole before it's decoded, so there's none here
    const utf8 = new TextEncoder();
    const bytes = Uint8Array.from([...utf8.encode('{"value":[{"Name":"é'), 0xe0, 0xec, 0x72, 0xff, 0xff, ...utf8.encode('","Type":"é"}]}')]);
    const replacement = String.fromCharCode(0xfffd);
    await splitting(async (read) => {
      for (let at = 1; at < bytes.length; at++) {
        expect(await read(...cut(bytes, at)), `split at ${at}`).toEqual([{ Name: "é" + replacement.repeat(2) + "r" + replacement.repeat(2), Type: "é" }]);
      }
    });
  });

  it("decodes collections of primitives", async () => {
    // Nulls dropped, as Java's items(); its decode(), which shows them, is the decoder's own
    expect(await read('{"value":["a",null,"b"]}')).toEqual(["a", "b"]);
  });

  it("decodes an empty collection", async () => {
    expect(await read('{"@odata.context":"x","value":[]}')).toEqual([]);
  });

  it("fails on an error instead of a collection", async () => {
    const e = await failure(read('{"error":{"code":"278","message":"Boom"}}'));
    expect(e).toBeInstanceOf(Tm1ProtocolException);
    expect((e as Error).message).toContain("Boom");
    expect((e as Tm1ProtocolException).error!.code).toBe("278");
  });

  it("fails when the response is cut short after some items", async () => {
    await expect(read(BODY.slice(0, BODY.indexOf("Total")))).rejects.toBeInstanceOf(Tm1ProtocolException);
  });

  it("fails on malformed or unexpected bodies", async () => {
    await expect(read('{"value":[{"Name":}]}')).rejects.toBeInstanceOf(Tm1ProtocolException);
    await expect(read("[1,2]")).rejects.toBeInstanceOf(Tm1ProtocolException);
    await expect(read("")).rejects.toThrow(/empty/);
    await expect(read('{"value":[]} {}')).rejects.toBeInstanceOf(Tm1ProtocolException);
    await expect(read('{"Name":"x"}')).rejects.toBeInstanceOf(Tm1ProtocolException);
  });
});

/** The Java client's `Tm1TimeoutsTest`. */
describe("Tm1Timeouts", () => {
  it("fills in defaults", () => {
    expect(Tm1Timeouts.DEFAULTS.equals(new Tm1Timeouts(10_000, 30_000, 600_000, 5_000, 30_000))).toBe(true);
    expect(Tm1Timeouts.defaults()).toBe(Tm1Timeouts.DEFAULTS);
    const longRequests = Tm1Timeouts.builder().request(3_600_000).build();
    expect(longRequests.request).toBe(3_600_000);
    expect(longRequests.login).toBe(30_000);
    expect(longRequests.toBuilder().close(1_000).build().close).toBe(1_000);
    expect(longRequests.equals(Tm1Timeouts.DEFAULTS)).toBe(false);
  });

  it("refuses timeouts that aren't positive", () => {
    expect(() => Tm1Timeouts.builder().login(0).build()).toThrow(RangeError);
    expect(() => Tm1Timeouts.builder().connect(-1_000).build()).toThrow("connect timeout must be positive");
    expect(() => Tm1Timeouts.builder().refusedLogin(-1).build()).toThrow(RangeError);
    Tm1Timeouts.builder().refusedLogin(0).build();
  });

  it("gives the login its own timeout", async () => {
    const tm1 = new ScriptedTm1().on(LOGIN, () => new Pending().promise);
    const e = (await failure(tm1.session((b) => b.timeouts(Tm1Timeouts.builder().login(20).build())).connect())) as Tm1Exception;
    expect(e).toBeInstanceOf(Tm1TransportException);
    expect(e.url).toBe(ROOT + "ActiveSession?$select=ID");
  });
});

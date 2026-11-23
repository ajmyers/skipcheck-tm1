// SPDX-License-Identifier: Apache-2.0

import { connect as netConnect } from "node:net";
import { connect as tlsConnect } from "node:tls";
import { randomUUID } from "node:crypto";
import { expect } from "vitest";
import { Json, ODataPath, ODataQuery, Tm1Authenticator, Tm1Request, Tm1Session, type Tm1SessionBuilder } from "../../src/index.js";
import { Live } from "./live.js";

// What the session tests share: TM1 over plain HTTP, and a few requests and waits.

export const WAIT = 30_000;
export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
export const scratch = (prefix = "zz_session_"): string => prefix + randomUUID().replace(/-/g, "").slice(0, 8);
export const basic = (user: string, password: string): string => "Basic " + Buffer.from(`${user}:${password}`, "utf8").toString("base64");

/** Settings for a session of the TM1 under test, logging in as `Live.client()` does. */
export async function sessions(): Promise<Tm1SessionBuilder> {
  const t = await Live.target();
  // Node's fetch takes no per-request certificate options: TM1_VERIFY=false turns checking off for the run
  if (process.env["TM1_VERIFY"] === "false") process.env["NODE_TLS_REJECT_UNAUTHORIZED"] = "0";
  return Tm1Session.builder()
    .baseUrl(t.url)
    .authenticator(t.namespace ? Tm1Authenticator.camNamespace(t.user, t.password, t.namespace) : Tm1Authenticator.basic(t.user, t.password));
}

/** A response: status, headers (all `Set-Cookie`s apart) and body. */
export interface RawResponse {
  status: number;
  headers: Headers;
  cookies: string[];
  body: string;
}

/** What `getWithHeaderBytes` got: the status, the whole response as text, and the session cookie TM1
 * set, if it opened a session. */
export interface RawBytes {
  status: number;
  text: string;
  cookie: string | undefined;
}

/** One request: method, path relative to the service root, and optional credentials, cookie,
 * headers and JSON body. */
export class RawRequest {
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
export class RawTm1 {
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
    const response = await fetch(this.root + request.path, {
      method: request.method,
      headers,
      body: request.body,
      redirect: "manual",
      signal: AbortSignal.timeout(60_000),
    });
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
    const created = await this.request("POST", "Users")
      .withCookie(admin)
      .withBody(JSON.stringify({ Name: name, Password: password }))
      .send();
    if (created.status !== 201) throw new Error(`Couldn't create user ${name}: ${created.status} ${created.body}`);
  }

  /** A GET over a bare socket, with one header whose value goes out as exactly `value`'s bytes, as
   * the Java tests' `getWithHeaderBytes`: the status, and the whole response as text. */
  getWithHeaderBytes(path: string, authorization: string, header: string, value: Uint8Array): Promise<RawBytes> {
    const url = new URL(this.root + path);
    const port = Number(url.port || (url.protocol === "https:" ? 443 : 80));
    const head = `GET ${url.pathname}${url.search} HTTP/1.1\r\nHost: ${url.host}\r\nAuthorization: ${authorization}\r\nAccept: application/json\r\nConnection: close\r\n${header}: `;
    return new Promise((resolve, reject) => {
      const socket = url.protocol === "https:" ? tlsConnect({ host: url.hostname, port, servername: url.hostname }) : netConnect({ host: url.hostname, port });
      const chunks: Buffer[] = [];
      const finish = (): void => {
        socket.destroy();
        const text = Buffer.concat(chunks).toString("utf8");
        resolve({ status: Number(text.slice(9, 12)), text, cookie: /TM1SessionId=[^;\r\n]*/.exec(text)?.[0] });
      };
      socket.setTimeout(30_000, () => socket.destroy(new Error("TM1 didn't answer in 30 s")));
      socket.on("error", reject);
      socket.on("end", finish);
      socket.on("data", (chunk: Buffer) => {
        chunks.push(chunk);
        // One response and no further: TM1 keeps the connection open whatever Connection says
        const soFar = Buffer.concat(chunks);
        const headEnd = soFar.indexOf("\r\n\r\n");
        if (headEnd < 0) return;
        const headers = soFar.subarray(0, headEnd).toString("latin1").toLowerCase();
        const length = /content-length:\s*(\d+)/.exec(headers);
        const done = headers.includes("transfer-encoding: chunked")
          ? soFar.subarray(-5).toString("latin1") === "0\r\n\r\n"
          : !length || soFar.length >= headEnd + 4 + Number(length[1]);
        if (done) finish();
      });
      socket.write(Buffer.concat([Buffer.from(head, "latin1"), value, Buffer.from("\r\n\r\n", "latin1")]));
    });
  }

  async deleteUser(admin: string, name: string): Promise<void> {
    await this.request("DELETE", `Users('${encodeURIComponent(name.replace(/'/g, "''"))}')`)
      .withCookie(admin)
      .send();
  }
}

/** A label unique to one test, for `sessionCount`. */
export const uniqueContext = (): string => "test-" + randomUUID();

export const ACTIVE_USER = Tm1Request.get<{ Name: string }>(ODataPath.of("ActiveUser"), ODataQuery.none().select("Name"));
export const SERVER = Tm1Request.get<{ Name: string; ProductVersion: string }>(ODataPath.of("Server"), ODataQuery.none().select("Name", "ProductVersion"));

/** What a promise failed with; fails the test if it didn't. */
export async function failure(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    () => expect.fail("expected a failure"),
    (e: unknown) => e,
  );
}

export const createProcess = (name: string, prolog: string): Tm1Request<void> =>
  Tm1Request.post(ODataPath.of("Processes"), { Name: name, PrologProcedure: prolog });
export const execute = (process: string): Tm1Request<{ ProcessExecuteStatusCode: string }> =>
  Tm1Request.post(ODataPath.of("Processes").key(process).action("tm1.ExecuteWithReturn"), null, Json) as Tm1Request<{ ProcessExecuteStatusCode: string }>;

// SPDX-License-Identifier: Apache-2.0

import type { Tm1Authenticator } from "../auth/index.js";
import { headerText } from "./header-text.js";
import {
  ODataError,
  Tm1ApiException,
  Tm1ProtocolException,
  Tm1SessionClosedException,
  Tm1TransportException,
  type Tm1ErrorClassifier,
} from "../errors/index.js";
import { Tm1Request } from "../request.js";
import { Tm1Capabilities, type Tm1Dialect, Tm1Version } from "../version/index.js";
import type { Tm1Timeouts } from "./timeouts.js";
import type { Tm1HttpResponse, Tm1Transport } from "./transport.js";

/** @internal An answer, with its text. */
export interface Reply extends Tm1HttpResponse {}

/** @internal The cookie TM1 names a session by. */
export const COOKIE = "TM1SessionId";
/** @internal The header naming the user to act as, read at login only. */
export const IMPERSONATE = "TM1-Impersonate";
/** @internal The header labelling the session. */
export const SESSION_CONTEXT = "TM1-SessionContext";
/** @internal What every request accepts. */
export const JSON_ACCEPT = "application/json";
const LOGIN_PATH = "ActiveSession?$select=ID";
const utf8 = new TextEncoder();
// Not fatal: bytes that aren't UTF-8 read as U+FFFD, as the Java client's Utf8Repair. TM1 (11.8)
// sends such bytes in the transaction log, for a password's hash.
const decoder = new TextDecoder();

/** @internal A body as text, read as UTF-8. */
export function textOf(body: Uint8Array): string {
  return decoder.decode(body).replace(/^\uFEFF/, "");
}

interface Login {
  cookie: string | undefined;
  id: number;
}

/** One login, shared by every request that needs it while it runs. */
class Attempt {
  login!: Promise<Login>;
  refusedAt: number | undefined;
  settled = false;

  constructor(
    private readonly connection: Connection,
    private readonly seedCookie: string | undefined,
  ) {}

  /** Starts the login, once the attempt is the connection's current one (so a quick failure finds it there). */
  start(): this {
    this.login = (async () => {
      await Promise.resolve();
      return this.connection.sendLogin(this.seedCookie);
    })().then(
      (l) => {
        this.settled = true;
        return l;
      },
      (e: unknown) => {
        this.settled = true;
        this.connection.failed(this, e);
        throw e;
      },
    );
    // Whoever needs the login awaits it; a failure nobody awaits isn't an unhandled rejection
    this.login.catch(() => undefined);
    return this;
  }
}

/** Capabilities read from `$metadata`, once per server and version, shared by the sessions built
 * from one set of settings. A failed read isn't kept. */
export class CapabilitiesCache {
  private readonly byServer = new Map<string, Promise<Tm1Capabilities>>();

  /** The capabilities of the server at `serviceRoot` on `version`: read with `fetch` the first
   * time, then kept. */
  get(serviceRoot: string, version: Tm1Version, fetch: () => Promise<Tm1Capabilities>): Promise<Tm1Capabilities> {
    const key = serviceRoot + " " + version.toString();
    let entry = this.byServer.get(key);
    if (!entry) {
      entry = fetch();
      this.byServer.set(key, entry);
      const e = entry;
      e.catch(() => {
        if (this.byServer.get(key) === e) this.byServer.delete(key);
      });
    }
    return entry;
  }
}

function abortError(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException("This operation was aborted", "AbortError");
}

/** The login, cookie and transport every view of a session shares, as the Java client's
 * `Connection`: it logs in lazily with `GET ActiveSession` and the authenticator's header (and
 * `TM1-Impersonate`, at login only), then sends only the `TM1SessionId` cookie; when TM1 answers 401
 * it logs in again once and resends. A login TM1 refused is remembered for
 * {@link Tm1Timeouts.refusedLogin}, so requests meanwhile fail at once rather than pile up sessions
 * on TM1. */
export class Connection {
  /** The service root, ending in `/`. */
  readonly root: string;
  private readonly closeUrl: string;
  private readonly authSessionUrl: string | undefined;
  private readonly applicationUser: string | undefined;
  private attempt: Attempt | undefined;
  private version: { read: Promise<Tm1Version>; for: Attempt } | undefined;
  private closed = false;
  private closing: Promise<void> | undefined;
  /** Requests running now: see {@link isBusy}. */
  private running = 0;

  /** @internal A connection to the service root `root`, logging in with `authenticator`. */
  constructor(
    root: string,
    dialect: Tm1Dialect,
    readonly authenticator: Tm1Authenticator,
    readonly impersonateUser: string | undefined,
    readonly sessionContext: string | undefined,
    readonly timeouts: Tm1Timeouts,
    readonly classifier: Tm1ErrorClassifier,
    readonly transport: Tm1Transport,
    readonly checkCapabilities: boolean,
    private readonly clock: () => number,
    private readonly capabilitiesCache: CapabilitiesCache,
  ) {
    this.root = root.endsWith("/") ? root : root + "/";
    this.closeUrl = dialect.closeUrl(this.root);
    this.authSessionUrl = dialect.authSessionUrl(this.root);
    this.applicationUser = authenticator.applicationUser();
    if (this.applicationUser !== undefined) {
      if (!this.applicationUser.trim()) throw new RangeError("application user must not be blank");
      // eslint-disable-next-line no-control-regex
      if (/[\x00-\x1f\x7f-\x9f]/.test(this.applicationUser)) throw new RangeError("application user must not contain control characters");
      if (impersonateUser !== undefined) {
        throw new Error(
          "TM1 12 application authentication doesn't support TM1-Impersonate (TM1 ignores the header): pass the user to Tm1Authenticator.application(clientId, clientSecret, user) instead",
        );
      }
      if (this.authSessionUrl === undefined) {
        throw new Error("TM1 application authentication requires a TM1 12 dialect (Tm1Dialect.v12)");
      }
    }
  }

  /** Whether the session was closed. */
  get isClosed(): boolean {
    return this.closed;
  }

  /** Whether a request is running: sent, or waiting for the login, and not yet answered in full,
   * failed or cancelled. An async request counts until its result is read, and a stream from its
   * first page until it is finished or stopped. */
  get isBusy(): boolean {
    return this.running > 0;
  }

  /** @internal Counts one more request as running, until the function it returns is called. */
  hold(): () => void {
    this.running++;
    let held = true;
    return () => {
      if (held) this.running--;
      held = false;
    };
  }

  /** @internal Runs `work`, counted as a running request until it settles. */
  async busy<T>(work: () => Promise<T>): Promise<T> {
    const release = this.hold();
    try {
      return await work();
    } finally {
      release();
    }
  }

  /** The URL of a path relative to the service root (or of a link TM1 sent), as sent: not
   * re-encoded, as `new URL` would (it escapes `'` in a query). */
  url(relative: string): string {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(relative)) return relative;
    if (relative.startsWith("/")) return /^[a-z][a-z0-9+.-]*:\/\/[^/]+/i.exec(this.root)![0] + relative;
    return this.root + relative;
  }

  /** The ID of the session on TM1, logging in first if needed. */
  async loginId(signal?: AbortSignal): Promise<number> {
    return (await this.wait(this.current().login, signal)).id;
  }

  private refusedRecently(a: Attempt): boolean {
    return a.refusedAt !== undefined && this.clock() - a.refusedAt < this.timeouts.refusedLogin;
  }

  private current(): Attempt {
    if (this.closed) throw new Tm1SessionClosedException(this.root);
    if (!this.attempt || (this.attempt.refusedAt !== undefined && !this.refusedRecently(this.attempt))) {
      this.attempt = new Attempt(this, undefined);
      this.attempt.start();
    }
    return this.attempt;
  }

  /** A new login after `expired`'s session was refused with 401, unless another request already
   * started one. It sends the cookie TM1 set with the 401, so TM1 logs in the session it opened for
   * it rather than opening another. */
  private relogin(expired: Attempt, seedCookie: string | undefined): Attempt {
    if (this.closed) throw new Tm1SessionClosedException(this.root);
    if (this.attempt && this.refusedRecently(this.attempt)) return this.attempt;
    if (this.attempt === expired || !this.attempt || this.attempt.refusedAt !== undefined) {
      this.attempt = new Attempt(this, seedCookie);
      this.attempt.start();
    }
    return this.attempt;
  }

  /** @internal */
  failed(failed: Attempt, error: unknown): void {
    if (this.attempt !== failed) return;
    if (error instanceof Tm1ApiException && [400, 401, 403].includes(error.status) && this.timeouts.refusedLogin > 0) {
      failed.refusedAt = this.clock();
    } else {
      this.attempt = undefined;
    }
  }

  /** @internal */
  async sendLogin(seedCookie: string | undefined): Promise<Login> {
    if (this.applicationUser !== undefined) return this.sendApplicationLogin();
    const url = this.root + LOGIN_PATH;
    // The authenticator's own failures (say, a passport it couldn't get) reach the caller unchanged
    const authorization = await this.credentials("GET", url);
    if (!authorization) throw new Error(`The authenticator ${this.authenticator.describe()} supplied no credentials`);
    const headers: Record<string, string> = { ...this.baseHeaders(JSON_ACCEPT), Authorization: authorization };
    // At login only: TM1 decides the identity then and ignores the header later
    if (this.impersonateUser !== undefined) headers[IMPERSONATE] = headerText(this.impersonateUser);
    if (this.sessionContext !== undefined) headers[SESSION_CONTEXT] = headerText(this.sessionContext);
    // Log in the session TM1 opened for a 401, rather than opening another
    if (seedCookie !== undefined && !this.transport.keepsCookies) headers["Cookie"] = `${COOKIE}=${seedCookie}`;
    const reply = await this.sendRaw("GET", url, headers, undefined, this.timeouts.login, true, undefined);
    if (reply.status === 401) {
      // TM1 answers a disabled impersonated user exactly like wrong credentials
      const why =
        this.impersonateUser === undefined
          ? ODataError.parse(reply.body)
          : new ODataError("", `The credentials were refused, or the user being impersonated (${this.impersonateUser}) is disabled`);
      throw Tm1ApiException.of(401, "CredentialRejected", why, "GET", url);
    }
    if (reply.status >= 400) {
      const error = Tm1ApiException.fromBody(reply.status, reply.body, "GET", url, this.classifier);
      // A login that impersonates and gets a 400 or 403 was refused for the impersonation
      if (this.impersonateUser !== undefined && reply.status === 400 && error.kind !== "UnknownUser")
        throw Tm1ApiException.of(400, "UnknownUser", error.error, "GET", url);
      if (this.impersonateUser !== undefined && reply.status === 403 && error.kind !== "ImpersonationRefused")
        throw Tm1ApiException.of(403, "ImpersonationRefused", error.error, "GET", url);
      throw error;
    }
    const cookie = sessionCookie(reply) ?? seedCookie;
    if (cookie === undefined && !this.transport.keepsCookies)
      throw new Tm1ProtocolException(`TM1 accepted the login but set no ${COOKIE} cookie`, undefined, "GET", url);
    return { cookie, id: this.parseSessionId(reply.body, url) };
  }

  private async sendApplicationLogin(): Promise<Login> {
    const authUrl = this.authSessionUrl!;
    const authorization = await this.credentials("POST", authUrl);
    if (!authorization) throw new Error(`The authenticator ${this.authenticator.describe()} supplied no credentials`);
    const authHeaders: Record<string, string> = {
      ...this.baseHeaders(JSON_ACCEPT),
      Authorization: authorization,
      "Content-Type": JSON_ACCEPT,
    };
    const authBody = utf8.encode(JSON.stringify({ User: this.applicationUser! }));
    const authReply = await this.sendRaw("POST", authUrl, authHeaders, authBody, this.timeouts.login, true, undefined);
    if (authReply.status === 401) {
      throw Tm1ApiException.of(401, "CredentialRejected", ODataError.parse(authReply.body), "POST", authUrl);
    }
    if (authReply.status >= 400) {
      throw Tm1ApiException.fromBody(authReply.status, authReply.body, "POST", authUrl, this.classifier);
    }
    const cookie = sessionCookie(authReply);
    if (cookie === undefined && !this.transport.keepsCookies) {
      throw new Tm1ProtocolException(`TM1 accepted the application login but set no ${COOKIE} cookie`, undefined, "POST", authUrl);
    }

    const activeUrl = this.root + LOGIN_PATH;
    const activeHeaders: Record<string, string> = { ...this.baseHeaders(JSON_ACCEPT) };
    if (cookie !== undefined && !this.transport.keepsCookies) activeHeaders["Cookie"] = `${COOKIE}=${cookie}`;
    if (this.sessionContext !== undefined) activeHeaders[SESSION_CONTEXT] = headerText(this.sessionContext);
    const activeReply = await this.sendRaw("GET", activeUrl, activeHeaders, undefined, this.timeouts.login, true, undefined);
    if (activeReply.status === 401) {
      throw Tm1ApiException.of(401, "CredentialRejected", ODataError.parse(activeReply.body), "GET", activeUrl);
    }
    if (activeReply.status >= 400) {
      throw Tm1ApiException.fromBody(activeReply.status, activeReply.body, "GET", activeUrl, this.classifier);
    }
    const effective = sessionCookie(activeReply) ?? cookie;
    return { cookie: effective, id: this.parseSessionId(activeReply.body, activeUrl) };
  }

  private parseSessionId(body: Uint8Array, url: string): number {
    let parsed: unknown;
    try {
      parsed = JSON.parse(textOf(body));
    } catch (e) {
      throw new Tm1ProtocolException("TM1 accepted the login but its response isn't JSON", undefined, "GET", url, e);
    }
    const id = typeof parsed === "object" && parsed !== null ? (parsed as Record<string, unknown>)["ID"] : undefined;
    const n = typeof id === "number" ? id : typeof id === "string" && /^-?\d+$/.test(id) ? Number(id) : NaN;
    if (!Number.isFinite(n)) throw new Tm1ProtocolException("TM1 accepted the login but didn't return the session ID", undefined, "GET", url);
    return n;
  }

  /** The authenticator's credentials, given up on after the login timeout. The authenticator may ask
   * another server (Cognos, for a passport), and one that never answers would otherwise hold every
   * request on the session, and `close()`, for ever. The failure is a transport one in the connect
   * phase: nothing has been sent to TM1, so it is safe to retry. The authenticator's signal is
   * aborted then, so it can stop what it was doing. */
  private async credentials(method: string, url: string): Promise<string> {
    const within = this.timeouts.login;
    const deadline = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const timedOut = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        const why = new Error(`The authenticator ${this.authenticator.describe()} supplied no credentials within ${within} ms`);
        reject(new Tm1TransportException("Connect", true, method, url, why));
        deadline.abort(why);
      }, within);
    });
    try {
      return await Promise.race([this.authenticator.authorization(deadline.signal), timedOut]);
    } finally {
      clearTimeout(timer);
    }
  }

  /** Sends a request with the session cookie, logging in again once on 401; a second 401 is a
   * `Tm1AuthenticationException` of kind `Unauthenticated`. The session is busy ({@link isBusy})
   * until it has its answer. */
  send(
    method: string,
    url: string,
    body: Uint8Array | undefined,
    contentType: string | undefined,
    headers: Readonly<Record<string, string>>,
    accept: string,
    timeout: number | undefined,
    signal: AbortSignal | undefined,
  ): Promise<Reply> {
    return this.busy(() => this.exchange(method, url, body, contentType, headers, accept, timeout, signal));
  }

  private async exchange(
    method: string,
    url: string,
    body: Uint8Array | undefined,
    contentType: string | undefined,
    headers: Readonly<Record<string, string>>,
    accept: string,
    timeout: number | undefined,
    signal: AbortSignal | undefined,
  ): Promise<Reply> {
    let used = this.current();
    let session = await this.wait(used.login, signal);
    for (let attempt = 1; ; attempt++) {
      const all: Record<string, string> = this.baseHeaders(accept);
      for (const [name, value] of Object.entries(headers)) if (name.toLowerCase() !== "content-type") all[name] = value;
      if (session.cookie !== undefined && !this.transport.keepsCookies) all["Cookie"] = `${COOKIE}=${session.cookie}`;
      if (body !== undefined) all["Content-Type"] = contentType ?? "application/json";
      const reply = await this.sendRaw(method, url, all, body, timeout ?? this.timeouts.request, Tm1Request.isIdempotentMethod(method), signal);
      if (reply.status === 401) {
        if (attempt > 1) throw Tm1ApiException.of(401, "Unauthenticated", undefined, method, url);
        used = this.relogin(used, sessionCookie(reply));
        session = await this.wait(used.login, signal);
        continue;
      }
      return reply;
    }
  }

  private wait<T>(promise: Promise<T>, signal: AbortSignal | undefined): Promise<T> {
    if (!signal) return promise;
    if (signal.aborted) return Promise.reject(abortError(signal));
    return new Promise<T>((resolve, reject) => {
      const onAbort = (): void => reject(abortError(signal));
      signal.addEventListener("abort", onAbort, { once: true });
      promise.then(
        (v) => {
          signal.removeEventListener("abort", onAbort);
          resolve(v);
        },
        (e: unknown) => {
          signal.removeEventListener("abort", onAbort);
          reject(e);
        },
      );
    });
  }

  private async sendRaw(
    method: string,
    url: string,
    headers: Record<string, string>,
    body: Uint8Array | undefined,
    timeout: number,
    idempotent: boolean,
    signal: AbortSignal | undefined,
    closeConnection = false,
  ): Promise<Reply> {
    if (signal?.aborted) throw abortError(signal);
    // An idle timeout, as Java's (Netty's response timeout): it restarts each time the transport
    // says data arrived, so a large body that keeps arriving isn't cut off
    const deadline = new AbortController();
    const expire = (): void => deadline.abort(new Error(`nothing received for ${timeout} ms`));
    let timer = setTimeout(expire, timeout);
    const received = (): void => {
      clearTimeout(timer);
      if (!deadline.signal.aborted) timer = setTimeout(expire, timeout);
    };
    const onAbort = (): void => deadline.abort(abortError(signal!));
    signal?.addEventListener("abort", onAbort, { once: true });
    try {
      return await this.transport.send({
        method,
        url,
        headers,
        body,
        signal: deadline.signal,
        closeConnection,
        connectTimeout: this.timeouts.connect,
        received,
      });
    } catch (e) {
      if (signal?.aborted) throw abortError(signal);
      if (deadline.signal.aborted) throw new Tm1TransportException("Response", idempotent, method, url, deadline.signal.reason);
      throw transport(e, method, url, idempotent);
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
    }
  }

  private baseHeaders(accept: string): Record<string, string> {
    return { "OData-Version": "4.0", "OData-MaxVersion": "4.0", Accept: accept };
  }

  /** The server's version, read once per login (TM1 may have been upgraded between them). */
  versionOf(signal?: AbortSignal): Promise<Tm1Version> {
    const now = this.current();
    if (!this.version || this.version.for !== now) {
      const read = this.readVersion();
      this.version = { read, for: now };
      read.catch(() => {
        if (this.version?.read === read) this.version = undefined;
      });
    }
    return this.wait(this.version.read, signal);
  }

  private async readVersion(): Promise<Tm1Version> {
    await Promise.resolve();
    const url = this.root + "Server?$select=ProductVersion";
    const reply = this.check("GET", url, await this.send("GET", url, undefined, undefined, {}, JSON_ACCEPT, undefined, undefined));
    let text: unknown;
    try {
      text = (JSON.parse(textOf(reply.body)) as Record<string, unknown>)?.["ProductVersion"];
    } catch (e) {
      throw new Tm1ProtocolException("TM1 didn't report its ProductVersion", undefined, "GET", url, e);
    }
    if (typeof text !== "string") throw new Tm1ProtocolException("TM1 didn't report its ProductVersion", undefined, "GET", url);
    return Tm1Version.parse(text);
  }

  /** What the server's `$metadata` defines, read once per server and version for every session
   * built from the same settings. */
  async capabilities(signal?: AbortSignal): Promise<Tm1Capabilities> {
    const version = await this.versionOf(signal);
    return this.wait(
      this.capabilitiesCache.get(this.root, version, async () => {
        const url = this.root + "$metadata";
        const reply = this.check("GET", url, await this.send("GET", url, undefined, undefined, {}, "application/xml", undefined, undefined));
        try {
          return Tm1Capabilities.parse(textOf(reply.body), version);
        } catch (e) {
          if (e instanceof Tm1ProtocolException) throw new Tm1ProtocolException("TM1 sent $metadata that isn't valid CSDL", undefined, "GET", url, e);
          throw e;
        }
      }),
      signal,
    );
  }

  /** The reply, or the exception for its error status. */
  check(method: string, url: string, reply: Reply): Reply {
    if (reply.status >= 400) throw Tm1ApiException.fromBody(reply.status, reply.body, method, url, this.classifier);
    return reply;
  }

  /** Closes the session on TM1 (waiting for a login under way) and refuses further requests;
   * closing twice closes once. A session TM1 already dropped (401) is fine. */
  close(): Promise<void> {
    if (!this.closing) {
      this.closed = true;
      const last = this.attempt;
      this.attempt = undefined;
      this.closing = this.sendClose(last);
    }
    return this.closing;
  }

  private async sendClose(last: Attempt | undefined): Promise<void> {
    await Promise.resolve();
    if (!last) return;
    let session: Login;
    try {
      session = await last.login;
    } catch {
      // Never logged in: nothing to close
      return;
    }
    const url = this.closeUrl;
    const headers: Record<string, string> = { ...this.baseHeaders(JSON_ACCEPT), "Content-Type": "application/json" };
    if (session.cookie !== undefined && !this.transport.keepsCookies) headers["Cookie"] = `${COOKIE}=${session.cookie}`;
    // TM1 sometimes drops the connection that carried a close: don't reuse it
    const reply = await this.sendRaw("POST", url, headers, utf8.encode("{}"), this.timeouts.close, true, undefined, true);
    // 401: the session was already gone (killed, or timed out), which is what close wants
    if (reply.status >= 400 && reply.status !== 401) throw Tm1ApiException.fromBody(reply.status, reply.body, "POST", url, this.classifier);
  }
}

const CONNECT_CODES = new Set([
  "ECONNREFUSED",
  "ENOTFOUND",
  "EHOSTUNREACH",
  "ENETUNREACH",
  "EAI_AGAIN",
  "UND_ERR_CONNECT_TIMEOUT",
  "CERT_HAS_EXPIRED",
  "DEPTH_ZERO_SELF_SIGNED_CERT",
  "SELF_SIGNED_CERT_IN_CHAIN",
  "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
  "ERR_TLS_CERT_ALTNAME_INVALID",
]);

/** Whether `failure` is one below HTTP, as Java's `Transport.isTransport`: what `fetch` throws for a
 * network failure (a `TypeError`, "fetch failed" in Node, "Failed to fetch" and the like in
 * browsers), a system or TLS error with its code (`ECONNRESET`, `UND_ERR_SOCKET`,
 * `CERT_HAS_EXPIRED`), or a DOM network or timeout error. Anything else, such as a bug in a
 * transport, isn't, so it isn't mistaken for a network failure. */
function isTransport(failure: unknown): boolean {
  if (failure instanceof Tm1TransportException) return true;
  if (typeof failure !== "object" || failure === null) return false;
  const code = (failure as { code?: unknown }).code;
  if (typeof code === "string" && /^(E[A-Z0-9_]+|UND_ERR_[A-Z_]+|ERR_TLS_[A-Z_]+|ERR_SSL_[A-Z_]+|[A-Z_]*CERT[A-Z_]*)$/.test(code)) return true;
  const name = (failure as { name?: unknown }).name;
  if (name === "NetworkError" || name === "TimeoutError") return true;
  if (failure instanceof TypeError) {
    return /fetch|network|load failed/i.test(failure.message) || isTransport((failure as { cause?: unknown }).cause);
  }
  return false;
}

/** A failure to get a response, as Java's `Transport.map`: before sending (refused, unknown host,
 * unreachable, TLS handshake) is the connect phase; anything later, the response phase. A failure
 * that isn't below HTTP is returned unchanged. */
export function transport(failure: unknown, method: string, url: string, idempotent: boolean): unknown {
  if (failure instanceof Tm1TransportException || !isTransport(failure)) return failure;
  let connect = false;
  for (let t: unknown = failure, depth = 0; t && !connect && depth < 32; t = (t as { cause?: unknown }).cause, depth++) {
    const code = (t as { code?: unknown }).code;
    connect = typeof code === "string" && CONNECT_CODES.has(code);
  }
  return new Tm1TransportException(connect ? "Connect" : "Response", idempotent, method, url, failure);
}

/** The `TM1SessionId` a response sets, unless it expires it (`Max-Age=0`). */
export function sessionCookie(reply: Tm1HttpResponse): string | undefined {
  const all = reply.headers["set-cookie"];
  if (!all) return undefined;
  for (const header of all.split("\n")) {
    const parts = header.split(";").map((p) => p.trim());
    const first = parts[0] ?? "";
    const eq = first.indexOf("=");
    if (eq < 0 || first.slice(0, eq).trim() !== COOKIE) continue;
    if (parts.slice(1).some((p) => p.replace(/ /g, "").toLowerCase() === "max-age=0")) continue;
    const value = first
      .slice(eq + 1)
      .trim()
      .replace(/^"|"$/g, "");
    if (value) return value;
  }
  return undefined;
}

/** Unwraps a completed async monitor response: TM1 11 wraps the inner HTTP response in an
 * `application/http` message and drops the monitor once read, while TM1 12 returns the raw body
 * directly with an `Asyncresult` status header and keeps the monitor cached until explicitly
 * deleted (`shouldDeleteMonitor: true`). */
export async function asyncReply(polled: Reply): Promise<{ result: Reply; shouldDeleteMonitor: boolean }> {
  const ct = polled.headers["content-type"] ?? "";
  const mediaType = ct.split(";", 1)[0]!.trim().toLowerCase();
  const header = polled.headers["asyncresult"];
  if (mediaType !== "application/http" && header !== undefined) {
    const trimmed = header.trim();
    if (!/^[1-5]\d\d$/.test(trimmed)) {
      throw new Tm1ProtocolException("TM1's async monitor sent an invalid Asyncresult status: " + header, undefined, "GET", "_async");
    }
    return { result: { status: Number(trimmed), headers: polled.headers, body: polled.body }, shouldDeleteMonitor: true };
  }
  return { result: await asyncResult(polled.body), shouldDeleteMonitor: false };
}

/** Merges `respond-async` (and `wait=<N>` when given) into `headers`, preserving unrelated `Prefer`
 * tokens and avoiding duplicate case-variant `Prefer` keys. */
export function mergePrefer(headers: Readonly<Record<string, string>>, waitMs: number | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  const kept: string[] = [];
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === "prefer") {
      for (const raw of splitPreferTokens(v)) {
        const semi = raw.indexOf(";");
        const main = (semi < 0 ? raw : raw.slice(0, semi)).trim();
        const eq = main.indexOf("=");
        const name = (eq < 0 ? main : main.slice(0, eq)).trim().toLowerCase();
        if (name && name !== "respond-async" && name !== "wait") kept.push(raw);
      }
    } else {
      out[k] = v;
    }
  }
  kept.push("respond-async");
  if (waitMs !== undefined) kept.push(`wait=${waitMs / 1000}`);
  out["Prefer"] = kept.join(", ");
  return out;
}

function splitPreferTokens(header: string): string[] {
  const tokens: string[] = [];
  let current = "";
  let inQuotes = false;
  let escaped = false;
  for (let i = 0; i < header.length; i++) {
    const c = header[i]!;
    if (escaped) {
      current += c;
      escaped = false;
    } else if (c === "\\" && inQuotes) {
      current += c;
      escaped = true;
    } else if (c === '"') {
      current += c;
      inQuotes = !inQuotes;
    } else if (c === "," && !inQuotes) {
      const token = current.trim();
      if (token) tokens.push(token);
      current = "";
    } else {
      current += c;
    }
  }
  const last = current.trim();
  if (last) tokens.push(last);
  return tokens;
}

/** The response an async monitor holds once done: an `application/http` message, whose body TM1
 * gzips when the request accepted gzip. */
export async function asyncResult(message: Uint8Array): Promise<Reply> {
  let end = -1;
  for (let i = 0; i + 3 < message.length; i++) {
    if (message[i] === 13 && message[i + 1] === 10 && message[i + 2] === 13 && message[i + 3] === 10) {
      end = i;
      break;
    }
  }
  const head = new TextDecoder("latin1").decode(end < 0 ? message : message.subarray(0, end));
  const lines = head.split("\r\n");
  const status = (lines[0] ?? "").split(" ");
  const code = Number(status[1]);
  if (status.length < 2 || !status[0]!.startsWith("HTTP/") || !Number.isInteger(code)) {
    throw new Tm1ProtocolException("TM1's async result isn't an HTTP message: " + lines[0], undefined, "GET", "_async");
  }
  const headers: Record<string, string> = {};
  for (const line of lines.slice(1)) {
    const colon = line.indexOf(":");
    if (colon > 0) headers[line.slice(0, colon).trim().toLowerCase()] = line.slice(colon + 1).trim();
  }
  let body = end < 0 ? new Uint8Array() : message.slice(end + 4);
  if (headers["content-encoding"]?.toLowerCase() === "gzip" && body.length > 0) {
    const stream = new Blob([body]).stream().pipeThrough(new DecompressionStream("gzip"));
    body = new Uint8Array(await new Response(stream).arrayBuffer());
  }
  return { status: code, headers, body };
}

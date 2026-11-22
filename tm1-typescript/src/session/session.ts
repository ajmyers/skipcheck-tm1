import { Tm1Authenticator } from "../auth/index.js";
import { Tm1ErrorClassifier, Tm1Exception, Tm1ProtocolException } from "../errors/index.js";
import { readAs, writeJson } from "../json.js";
import { Json } from "../model.js";
import { ODataEncoding, ODataPath, ODataQuery } from "../odata/index.js";
import { Tm1Request } from "../request.js";
import { Tm1Stream } from "../stream.js";
import { delay } from "../util.js";
import { Tm1Dialect, type Tm1Capabilities, type Tm1Capability, type Tm1Version } from "../version/index.js";
import { asyncResult, CapabilitiesCache, Connection, JSON_ACCEPT, textOf, type Reply } from "./connection.js";
import { Tm1Timeouts } from "./timeouts.js";
import { fetchTransport, type Tm1Transport } from "./transport.js";

/** What every call takes last: a signal that cancels it (Java's cancelling a subscription). */
export interface Tm1CallOptions {
  readonly signal?: AbortSignal;
}

const utf8 = new TextEncoder();

interface Page<T> {
  items: T[];
  nextLink: string | undefined;
}

/** A session with one TM1 server, and views of it, as the Java client's `Tm1Session`. A view
 * shares the login and connections, and changes how requests are sent: {@link withSandbox} (TM1's
 * `!sandbox`), {@link withChangeSet} (`!ChangeSet`), {@link withAsync} (OData's `Prefer:
 * respond-async`, polled until done; cancelling deletes the monitor, which stops the work on TM1)
 * and {@link batch} (JSON `$batch`). Views are derived rather than set, so one caller's sandbox
 * can't leak into another's requests. */
export class Tm1Session implements AsyncDisposable {
  /** @internal */
  constructor(
    /** @internal */ readonly connection: Connection,
    private readonly sandboxName: string | undefined,
    private readonly changeSetId: string | undefined,
    private readonly asyncEvery: number | undefined,
    private readonly queue: BatchQueue | undefined,
  ) {}

  /** The settings one by one, as the Java client's `Tm1Session.builder()`. */
  static builder(): Tm1SessionBuilder {
    return new Tm1SessionBuilder();
  }

  /** The REST API's root, such as `http://tm1:8010/api/v1/`. */
  serviceRoot(): string {
    return this.connection.root;
  }

  sandbox(): string | undefined {
    return this.sandboxName;
  }

  changeSet(): string | undefined {
    return this.changeSetId;
  }

  /** How often this view polls an async request's monitor, in milliseconds, or undefined. */
  async(): number | undefined {
    return this.asyncEvery;
  }

  isClosed(): boolean {
    return this.connection.isClosed;
  }

  // Views

  withSandbox(name: string): Tm1Session {
    if (typeof name !== "string" || !name.trim()) throw new RangeError("sandbox name is blank");
    return new Tm1Session(this.connection, name, this.changeSetId, this.asyncEvery, this.queue);
  }

  withoutSandbox(): Tm1Session {
    return this.sandboxName === undefined ? this : new Tm1Session(this.connection, undefined, this.changeSetId, this.asyncEvery, this.queue);
  }

  withChangeSet(id: string): Tm1Session {
    if (typeof id !== "string" || !id.trim()) throw new RangeError("change set ID is blank");
    return new Tm1Session(this.connection, this.sandboxName, id, this.asyncEvery, this.queue);
  }

  withoutChangeSet(): Tm1Session {
    return this.changeSetId === undefined ? this : new Tm1Session(this.connection, this.sandboxName, undefined, this.asyncEvery, this.queue);
  }

  /** @param every how often to poll, in milliseconds */
  withAsync(every: number): Tm1Session {
    if (!(every > 0)) throw new RangeError("The poll interval must be positive: " + every);
    return new Tm1Session(this.connection, this.sandboxName, this.changeSetId, every, this.queue);
  }

  withoutAsync(): Tm1Session {
    return this.asyncEvery === undefined ? this : new Tm1Session(this.connection, this.sandboxName, this.changeSetId, undefined, this.queue);
  }

  /** Starts a JSON `$batch`: its {@link Tm1SessionBatch.session} is a view of this one whose
   * requests are queued, and `run()` sends them. */
  batch(): Tm1SessionBatch {
    // The $batch itself carries no sandbox: each request in it does
    const queue = new BatchQueue(new Tm1Session(this.connection, undefined, undefined, undefined, undefined));
    return new Tm1SessionBatch(queue, new Tm1Session(this.connection, this.sandboxName, this.changeSetId, undefined, queue));
  }

  // Lifecycle

  /** Logs in now, rather than on the first request. */
  async connect(options?: Tm1CallOptions): Promise<Tm1Session> {
    await this.connection.loginId(options?.signal);
    return this;
  }

  /** The session's ID on TM1 (`ActiveSession`), logging in if needed. */
  sessionId(options?: Tm1CallOptions): Promise<number> {
    return this.connection.loginId(options?.signal);
  }

  /** Ends the session on the server, for every view. */
  close(): Promise<void> {
    return this.connection.close();
  }

  [Symbol.asyncDispose](): Promise<void> {
    return this.close();
  }

  version(options?: Tm1CallOptions): Promise<Tm1Version> {
    return this.connection.versionOf(options?.signal);
  }

  capabilities(options?: Tm1CallOptions): Promise<Tm1Capabilities> {
    return this.connection.capabilities(options?.signal);
  }

  // Sending

  /** Sends a request and reads its answer. Collections are read with {@link stream}. In a batch, the
   * promise settles once the batch has run. */
  async execute<T>(request: Tm1Request<T>, options?: Tm1CallOptions): Promise<T> {
    if (!(request instanceof Tm1Request)) throw new TypeError("execute() takes a Tm1Request");
    if (request.response === "Collection") throw new TypeError("Collection requests are streamed: use stream() for " + request.toString());
    if (this.queue) {
      const queue = this.queue;
      const entry = queue.add(this, request, (r) => this.read(request, r));
      await this.check(request.requirement(), options);
      return (await entry) as T;
    }
    await this.check(request.requirement(), options);
    const reply = await this.send(request, this.target(request.relativeUri()), options?.signal);
    return this.read(request, reply);
  }

  /** The items of a collection, fetching pages as they're needed (Java's `stream`). In a batch, its
   * first page, once the batch has run: the request joins the batch at once. */
  stream<T>(request: Tm1Request<T>, options?: Tm1CallOptions): Tm1Stream<T> {
    if (!(request instanceof Tm1Request)) throw new TypeError("stream() takes a Tm1Request");
    if (request.response !== "Collection") throw new TypeError("Only collection requests are streamed: use execute() for " + request.toString());
    const signal = options?.signal;
    if (this.queue) {
      const page = this.queue.add(this, request, (r) => readPage(request, r)) as Promise<Page<T>>;
      const checked = this.check(request.requirement(), options);
      checked.catch(() => undefined);
      return Tm1Stream.from(async function* () {
        await checked;
        const p = await page;
        if (p.nextLink !== undefined) throw new Tm1ProtocolException(`${request.toString()}: TM1 sent more than one page, which a batch can't follow; ask for fewer with top()`);
        yield* p.items;
      });
    }
    const self = this;
    return Tm1Stream.from(async function* () {
      await self.check(request.requirement(), options);
      let url = self.target(request.relativeUri());
      let current: Tm1Request<T> = request;
      for (;;) {
        const reply = await self.send(current, url, signal);
        const page = readPage(current, reply);
        yield* page.items;
        if (page.nextLink === undefined) return;
        // The link carries the query and TM1's options already
        url = self.connection.url(page.nextLink);
        current = current.withQuery(ODataQuery.none());
      }
    });
  }

  private async check(requirement: Tm1Capability | undefined, options: Tm1CallOptions | undefined): Promise<void> {
    if (!requirement || !this.connection.checkCapabilities) return;
    const refused = (await this.connection.capabilities(options?.signal)).check(requirement, this.connection.root);
    if (refused) throw refused;
  }

  /** @internal The URL of a path and query, with the view's `!sandbox` and `!ChangeSet` unless the
   * request sets its own. */
  target(relative: string): string {
    return this.connection.url(this.contextual(relative));
  }

  /** @internal */
  contextual(relative: string): string {
    const options: string[] = [];
    if (this.sandboxName !== undefined && !relative.includes("!sandbox=")) options.push("!sandbox=" + ODataEncoding.queryValue(this.sandboxName));
    if (this.changeSetId !== undefined && !relative.includes("!ChangeSet=")) options.push("!ChangeSet=" + ODataEncoding.queryValue(this.changeSetId));
    return options.length === 0 ? relative : relative + (relative.includes("?") ? "&" : "?") + options.join("&");
  }

  private async send<T>(request: Tm1Request<T>, url: string, signal: AbortSignal | undefined): Promise<Reply> {
    const [content, contentType] = encode(request);
    let reply: Reply;
    // Raw bytes (document content) aren't sent async
    if (this.asyncEvery !== undefined && request.response !== "Bytes" && !(request.body instanceof Uint8Array)) {
      reply = await this.sendRespondAsync(request.method, url, content, contentType, request.headers, request.accept(), request.timeout, this.asyncEvery, signal);
    } else {
      reply = await this.connection.send(request.method, url, content, contentType, request.headers, request.accept(), request.timeout, signal);
    }
    return this.connection.check(request.method, url, reply);
  }

  private async sendRespondAsync(method: string, url: string, content: Uint8Array | undefined, contentType: string | undefined, headers: Readonly<Record<string, string>>, accept: string, timeout: number | undefined, every: number, signal: AbortSignal | undefined): Promise<Reply> {
    const reply = await this.connection.send(method, url, content, contentType, { ...headers, Prefer: "respond-async" }, accept, timeout, signal);
    if (reply.status !== 202) return reply; // TM1 answered at once
    const location = reply.headers["location"];
    if (!location) throw new Tm1ProtocolException("TM1 accepted an async request without a Location", undefined, method, url);
    // The Location is relative to the request's URL: ../_async('id')
    const monitor = new URL(location, url).toString();
    let finished = false;
    try {
      for (;;) {
        await delay(every, signal);
        const polled = this.connection.check("GET", monitor, await this.connection.send("GET", monitor, undefined, undefined, {}, "*/*", undefined, signal));
        if (polled.status !== 202) {
          finished = true;
          return await asyncResult(polled.body);
        }
      }
    } finally {
      if (!finished) {
        // Deleting the monitor is what stops the work on TM1
        try {
          await this.connection.send("DELETE", monitor, undefined, undefined, {}, JSON_ACCEPT, undefined, undefined);
        } catch (e) {
          // The work may already be gone
          if (!(e instanceof Tm1Exception)) throw e;
        }
      }
    }
  }

  /** @internal */
  read<T>(request: Tm1Request<T>, reply: Reply): T {
    switch (request.response) {
      case "None":
        return undefined as T;
      case "Bytes":
        return reply.body as T;
      case "Text":
        return textOf(reply.body) as T;
      default:
        if (reply.status === 204 || reply.body.length === 0) return undefined as T;
        return readAs(request.type, parse(request, reply));
    }
  }

  toString(): string {
    return `Tm1Session[${this.connection.root}${this.sandboxName === undefined ? "" : ", sandbox=" + this.sandboxName}${this.changeSetId === undefined ? "" : ", changeSet=" + this.changeSetId}]`;
  }
}

function parse(request: Tm1Request<unknown>, reply: Reply): unknown {
  try {
    return JSON.parse(textOf(reply.body));
  } catch (e) {
    throw new Tm1ProtocolException(`TM1 answered ${reply.status} to ${request.toString()} with something this client can't read: ${(e as Error).message}`, undefined, undefined, undefined, e);
  }
}

function encode(request: Tm1Request<unknown>): [Uint8Array | undefined, string | undefined] {
  if (request.body === undefined) return [undefined, undefined];
  if (request.body instanceof Uint8Array) {
    const ct = Object.entries(request.headers).find(([k]) => k.toLowerCase() === "content-type")?.[1];
    return [request.body, ct ?? "application/octet-stream"];
  }
  return [utf8.encode(writeJson(request.body)), "application/json; charset=utf-8"];
}

function readPage<T>(request: Tm1Request<T>, reply: Reply): Page<T> {
  const doc = parse(request, reply) as Record<string, unknown> | null;
  const items = doc && typeof doc === "object" ? doc["value"] : undefined;
  if (!Array.isArray(items)) throw new Tm1ProtocolException(`${request.toString()}: expected a collection`);
  const next = doc!["@odata.nextLink"];
  return { items: items.map((i) => readAs(request.type, i)), nextLink: typeof next === "string" ? next : undefined };
}

/** A JSON `$batch`, as the Java client's `Tm1SessionBatch`: requests through {@link session} are
 * queued, and `run()` sends them together. TM1 (11.8) runs them in order (a later one sees an
 * earlier one's writes) and carries on past failed ones: each request's promise gets its own result
 * or error. */
export class Tm1SessionBatch {
  static readonly DEFAULT_PER_REQUEST = 500;

  /** @internal */
  constructor(
    private readonly queue: BatchQueue,
    /** The view whose requests join this batch. */
    readonly session: Tm1Session,
  ) {}

  size(): number {
    return this.queue.count();
  }

  /** Sends the queued requests, at most `perRequest` to each `$batch`. */
  run(perRequest: number = Tm1SessionBatch.DEFAULT_PER_REQUEST, options?: Tm1CallOptions): Promise<void> {
    return this.queue.run(perRequest, options?.signal);
  }
}

interface Entry {
  method: string;
  url: string;
  headers: Readonly<Record<string, string>>;
  body: unknown;
  accept: string;
  read: (reply: Reply) => unknown;
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

/** @internal */
export class BatchQueue {
  private readonly entries: Entry[] = [];
  private ran = false;

  constructor(private readonly sender: Tm1Session) {}

  count(): number {
    return this.entries.length;
  }

  add<T>(view: Tm1Session, request: Tm1Request<T>, read: (reply: Reply) => unknown): Promise<unknown> {
    if (request.response === "Bytes" || request.body instanceof Uint8Array) throw new TypeError("Raw bytes can't go in a JSON batch: " + request.toString());
    if (this.ran) throw new Error("The batch has already run, so this call can't join it: a call whose request depends on another's result can't be batched. " + request.toString());
    let resolve!: (v: unknown) => void;
    let reject!: (e: unknown) => void;
    const result = new Promise<unknown>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    // Each caller awaits its own result; one nobody awaits isn't an unhandled rejection
    result.catch(() => undefined);
    this.entries.push({ method: request.method, url: view.contextual(request.relativeUri()), headers: request.headers, body: request.body, accept: request.accept(), read, resolve, reject });
    return result;
  }

  async run(perRequest: number, signal: AbortSignal | undefined): Promise<void> {
    if (this.ran) throw new Error("The batch has already run");
    if (!(perRequest > 0)) throw new RangeError("perRequest must be positive: " + perRequest);
    this.ran = true;
    const all = [...this.entries];
    const connection = this.sender.connection;
    for (let start = 0; start < all.length; start += perRequest) {
      const chunk = all.slice(start, start + perRequest);
      const requests = chunk.map((e, i) => {
        const headers: Record<string, string> = { ...e.headers, Accept: e.accept };
        const item: Record<string, unknown> = { id: String(i + 1), method: e.method, url: e.url };
        if (e.body !== undefined) {
          headers["Content-Type"] = "application/json";
          item["body"] = e.body;
        }
        item["headers"] = headers;
        return item;
      });
      try {
        const reply = (await this.sender.execute(Tm1Request.post(ODataPath.batch(), { requests }, Json), { signal })) as Record<string, unknown> | undefined;
        const byId = new Map<string, Record<string, unknown>>();
        if (reply && Array.isArray(reply["responses"])) for (const r of reply["responses"] as Record<string, unknown>[]) byId.set(String(r["id"]), r);
        chunk.forEach((e, i) => {
          const url = connection.url(e.url);
          const item = byId.get(String(i + 1));
          if (!item) {
            e.reject(new Tm1ProtocolException(`TM1's batch response has no answer for ${e.method} ${url}`));
            return;
          }
          const status = typeof item["status"] === "number" ? item["status"] : 0;
          const b = item["body"];
          const body = b === undefined || b === null ? new Uint8Array() : utf8.encode(typeof b === "string" ? b : JSON.stringify(b));
          try {
            e.resolve(e.read(connection.check(e.method, url, { status, headers: {}, body })));
          } catch (error) {
            e.reject(error);
          }
        });
      } catch (error) {
        for (const e of chunk) e.reject(error);
      }
    }
  }
}

/** A session's settings one by one, as the Java client's `Tm1Session.Builder`. */
export class Tm1SessionBuilder {
  private root: string | undefined;
  private addressValue: string | undefined;
  private dialectValue: Tm1Dialect = Tm1Dialect.V11;
  private authenticatorValue: Tm1Authenticator | undefined;
  private impersonated: string | undefined;
  private context: string | undefined;
  private capabilitiesCache = new CapabilitiesCache();
  private timeoutsValue: Tm1Timeouts = Tm1Timeouts.DEFAULTS;
  private classifier: Tm1ErrorClassifier = Tm1ErrorClassifier.defaults();
  private transportValue: Tm1Transport | undefined;
  private check = true;
  private clockValue: (() => number) | undefined;

  /** The REST API's root, such as `http://tm1:8010/api/v1/`. */
  baseUrl(baseUrl: string): this {
    if (typeof baseUrl !== "string") throw new TypeError("baseUrl is required");
    this.root = baseUrl;
    this.addressValue = undefined;
    return this;
  }

  /** The server's address, read by the dialect: `http://tm1:8010`. */
  address(address: string): this {
    if (typeof address !== "string") throw new TypeError("address is required");
    this.addressValue = address;
    this.root = undefined;
    return this;
  }

  dialect(dialect: Tm1Dialect): this {
    this.dialectValue = dialect;
    return this;
  }

  basicAuth(user: string, password?: string | null): this {
    return this.authenticator(Tm1Authenticator.basic(user, password));
  }

  authenticator(authenticator: Tm1Authenticator): this {
    if (!(authenticator instanceof Tm1Authenticator)) throw new TypeError("authenticator is a Tm1Authenticator");
    this.authenticatorValue = authenticator;
    return this;
  }

  /** Logs in as the service account but acts as `user` (`TM1-Impersonate`). */
  impersonate(user: string): this {
    this.impersonated = checkImpersonated(user);
    return this;
  }

  withoutImpersonation(): this {
    this.impersonated = undefined;
    return this;
  }

  /** A label TM1 shows for the session (`TM1-SessionContext`, sent with the login), such as the
   * application's name: in TM1's thread and session lists and its audit log. At most 250
   * characters, which is all TM1 keeps. */
  sessionContext(sessionContext: string): this {
    if (typeof sessionContext !== "string") throw new TypeError("sessionContext is required");
    if (sessionContext.length > 250) throw new RangeError("TM1 keeps at most 250 characters of session context, got " + sessionContext.length);
    this.context = sessionContext;
    return this;
  }

  timeouts(timeouts: Tm1Timeouts): this {
    this.timeoutsValue = timeouts;
    return this;
  }

  errorClassifier(classifier: Tm1ErrorClassifier): this {
    this.classifier = classifier;
    return this;
  }

  /** What sends the requests (Java's `webClient` and `connector`): {@link fetchTransport} by default. */
  transport(transport: Tm1Transport): this {
    if (!transport || typeof transport.send !== "function") throw new TypeError("transport has a send(request) method");
    this.transportValue = transport;
    return this;
  }

  /** Whether requests check the server's `$metadata` for what they need. */
  checkCapabilities(check: boolean): this {
    this.check = check;
    return this;
  }

  /** The clock refused logins are timed by, in milliseconds (for tests). */
  clock(clock: () => number): this {
    this.clockValue = clock;
    return this;
  }

  /** A copy of these settings, for a pool to build one session per identity. */
  copy(): Tm1SessionBuilder {
    return Object.assign(Object.create(Tm1SessionBuilder.prototype) as Tm1SessionBuilder, this);
  }

  /** @internal The service root, or a placeholder if it isn't set yet, for the pool's messages. */
  baseUrlOrUnset(): string {
    return this.root ?? (this.addressValue !== undefined ? this.dialectValue.serviceRoot(this.addressValue) : "(no base URL)");
  }

  build(): Tm1Session {
    const root = this.root ?? (this.addressValue !== undefined ? this.dialectValue.serviceRoot(this.addressValue) : undefined);
    if (root === undefined) throw new Error("Give the server: baseUrl or address");
    if (!this.authenticatorValue) throw new Error("Give the login: basicAuth or authenticator");
    const connection = new Connection(
      root,
      this.authenticatorValue,
      this.impersonated,
      this.context,
      this.timeoutsValue,
      this.classifier,
      this.transportValue ?? fetchTransport(),
      this.check,
      this.clockValue ?? Date.now,
      this.capabilitiesCache,
    );
    return new Tm1Session(connection, undefined, undefined, undefined, undefined);
  }
}

/** @internal */
export function checkImpersonated(user: string): string {
  if (typeof user !== "string") throw new TypeError("user is required");
  if (!user.trim()) throw new RangeError("The user to impersonate is blank, which TM1 would take as no impersonation at all");
  for (const c of user) {
    const code = c.codePointAt(0)!;
    if (code < 0x20 || code > 0x7e) throw new RangeError(`TM1 can't impersonate '${user}': it doesn't match user names with characters other than printable ASCII through TM1-Impersonate`);
  }
  return user;
}

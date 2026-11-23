import { expect } from "vitest";
import { Tm1Session, type Tm1Clock, type Tm1HttpRequest, type Tm1HttpResponse, type Tm1SessionBuilder, type Tm1Transport } from "../../src/index.js";

const utf8 = new TextEncoder();

/** A request as the session sent it: its key (`METHOD path?query`, relative to the root), URL,
 * headers (lower-case names), body and whether it asked not to reuse its connection. */
export class ScriptedRequest {
  constructor(
    readonly method: string,
    readonly key: string,
    readonly url: string,
    readonly headers: Readonly<Record<string, string>>,
    readonly body: Uint8Array,
    readonly closeConnection: boolean,
  ) {}

  header(name: string): string | undefined {
    return this.headers[name.toLowerCase()];
  }

  /** The `TM1SessionId` cookie it sends. */
  get cookie(): string | undefined {
    return this.header("cookie")
      ?.split(";")
      .map((c) => c.trim())
      .find((c) => c.startsWith("TM1SessionId="))
      ?.slice("TM1SessionId=".length);
  }

  get authorization(): string | undefined {
    return this.header("authorization");
  }

  get text(): string {
    return new TextDecoder().decode(this.body);
  }

  get json(): any {
    return JSON.parse(this.text);
  }
}

/** An answer: the response, or a promise of it (a pending one stands for TM1 thinking). Throwing is
 * a broken connection. */
export type ScriptedAnswer = (request: ScriptedRequest) => Tm1HttpResponse | Promise<Tm1HttpResponse>;

/** A scripted TM1, as the Java client's test `FakeTm1` (and .NET's `ScriptedTm1`): answers each
 * request by its key with the answer set for it, and records every request, logins and closes
 * included. A login logs in a new session with cookie `c1`, `c2`…, or, like TM1, the session whose
 * cookie it sends (setting no cookie); a close answers 204; anything else unscripted answers 404. */
export class ScriptedTm1 implements Tm1Transport {
  static readonly ROOT = "http://tm1.test/api/v1/";
  static readonly LOGIN = "GET ActiveSession?$select=ID";
  static readonly CLOSE = "POST ActiveSession/tm1.Close";

  private readonly answers = new Map<string, ScriptedAnswer[]>();
  private readonly recorded: ScriptedRequest[] = [];
  private logins = 0;

  constructor() {
    this.on(ScriptedTm1.LOGIN, (r) => {
      const n = ++this.logins;
      return r.cookie !== undefined ? ScriptedTm1.json(200, `{"ID":${n}}`) : ScriptedTm1.json(200, `{"ID":${n}}`, `TM1SessionId=c${n}; Path=/api/; HttpOnly`);
    });
    this.on(ScriptedTm1.CLOSE, () => ScriptedTm1.status(204));
  }

  /** Answers `key` with `answer` from now on. */
  on(key: string, answer: ScriptedAnswer): this {
    this.answers.set(key, [answer]);
    return this;
  }

  /** Answers `key` with each answer once, in order, then the last one from then on. */
  onEach(key: string, ...answers: ScriptedAnswer[]): this {
    this.answers.set(key, [...answers]);
    return this;
  }

  /** A session with Basic `admin`/`apple`, from `configure`'s settings. */
  session(configure?: (b: Tm1SessionBuilder) => Tm1SessionBuilder): Tm1Session {
    return this.sessionWithOwnCredentials((b) => (configure?.(b) ?? b).basicAuth("admin", "apple"));
  }

  /** A session from settings that set their own credentials. */
  sessionWithOwnCredentials(configure: (b: Tm1SessionBuilder) => Tm1SessionBuilder): Tm1Session {
    return this.builder(configure).build();
  }

  /** Settings pointed at this fake. */
  builder(configure?: (b: Tm1SessionBuilder) => Tm1SessionBuilder): Tm1SessionBuilder {
    const b = Tm1Session.builder();
    return (configure?.(b) ?? b).baseUrl(ScriptedTm1.ROOT).transport(this);
  }

  requests(key?: string): ScriptedRequest[] {
    return key === undefined ? [...this.recorded] : this.recorded.filter((r) => r.key === key);
  }

  async send(request: Tm1HttpRequest): Promise<Tm1HttpResponse> {
    if (request.signal.aborted) throw request.signal.reason;
    const key = request.method + " " + (request.url.startsWith(ScriptedTm1.ROOT) ? request.url.slice(ScriptedTm1.ROOT.length) : request.url);
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(request.headers)) headers[k.toLowerCase()] = v;
    const recorded = new ScriptedRequest(request.method, key, request.url, headers, request.body ?? new Uint8Array(), request.closeConnection === true);
    this.recorded.push(recorded);
    const answers = this.answers.get(key);
    if (!answers) return ScriptedTm1.json(404, JSON.stringify({ error: { code: "278", message: "No fake for " + key } }));
    const answer = answers.length > 1 ? answers.shift()! : answers[0]!;
    // As a connection does: cancelling stops waiting for the answer
    return new Promise<Tm1HttpResponse>((resolve, reject) => {
      const onAbort = (): void => reject(request.signal.reason);
      request.signal.addEventListener("abort", onAbort, { once: true });
      Promise.resolve()
        .then(() => answer(recorded))
        .then(
          (r) => {
            request.signal.removeEventListener("abort", onAbort);
            resolve(r);
          },
          (e: unknown) => {
            request.signal.removeEventListener("abort", onAbort);
            reject(e);
          },
        );
    });
  }

  static json(status: number, body: string, ...setCookies: string[]): Tm1HttpResponse {
    return ScriptedTm1.response(status, body, "application/json", setCookies);
  }

  static status(status: number, ...setCookies: string[]): Tm1HttpResponse {
    return ScriptedTm1.response(status, undefined, undefined, setCookies);
  }

  static text(body: string, status = 200): Tm1HttpResponse {
    return ScriptedTm1.response(status, body, "text/plain", []);
  }

  static xml(body: string): Tm1HttpResponse {
    return ScriptedTm1.response(200, body, "application/xml", []);
  }

  static bytes(status: number, body: Uint8Array, contentType: string, headers: Record<string, string> = {}): Tm1HttpResponse {
    return { status, headers: { "content-type": contentType, ...headers }, body };
  }

  private static response(status: number, body: string | undefined, contentType: string | undefined, setCookies: string[]): Tm1HttpResponse {
    const headers: Record<string, string> = {};
    if (contentType) headers["content-type"] = contentType;
    if (setCookies.length) headers["set-cookie"] = setCookies.join("\n");
    return { status, headers, body: body === undefined ? new Uint8Array() : utf8.encode(body) };
  }
}

/** An answer that hasn't come yet (Java's `Sinks.one()`): {@link answer} sends it. */
export class Pending {
  readonly promise: Promise<Tm1HttpResponse>;
  private resolve!: (r: Tm1HttpResponse) => void;

  constructor() {
    this.promise = new Promise((resolve) => (this.resolve = resolve));
  }

  readonly reply: ScriptedAnswer = () => this.promise;

  answer(response: Tm1HttpResponse): void {
    this.resolve(response);
  }
}

/** Waits up to five seconds for `condition`. */
export async function eventually(condition: () => boolean, because = ""): Promise<void> {
  const deadline = Date.now() + 5000;
  while (!condition()) {
    if (Date.now() > deadline) expect.fail("Timed out waiting " + because);
    await new Promise((r) => setTimeout(r, 5));
  }
}

/** Lets pending promise callbacks run. */
export async function settle(): Promise<void> {
  for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 0));
}

/** A clock and timers a test moves by hand (Java's `VirtualTimeScheduler`). */
export class ManualClock implements Tm1Clock {
  private time = 0;
  private nextId = 1;
  private readonly timers = new Map<number, { due: number; callback: () => void }>();

  now(): number {
    return this.time;
  }

  setTimeout(callback: () => void, ms: number): unknown {
    const id = this.nextId++;
    this.timers.set(id, { due: this.time + ms, callback });
    return id;
  }

  clearTimeout(handle: unknown): void {
    this.timers.delete(handle as number);
  }

  /** Moves the clock on, firing each timer due on the way, in order. */
  advance(ms: number): void {
    const end = this.time + ms;
    for (;;) {
      let next: [number, { due: number; callback: () => void }] | undefined;
      for (const t of this.timers) if (t[1].due <= end && (!next || t[1].due < next[1].due)) next = t;
      if (!next) break;
      this.timers.delete(next[0]);
      this.time = next[1].due;
      next[1].callback();
    }
    this.time = end;
  }
}

/** A seeded random number generator (mulberry32), for fuzzing the same cases every run. */
export function seeded(seed: number): (bound: number) => number {
  let a = seed >>> 0;
  return (bound) => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return Math.floor((((t ^ (t >>> 14)) >>> 0) / 4294967296) * bound);
  };
}

import { readFileSync } from "node:fs";
import { Tm1Client, Tm1Session, type Tm1HttpRequest, type Tm1HttpResponse, type Tm1SessionBuilder, type Tm1Transport } from "../../src/index.js";
import { Repo } from "./repo.js";

const SNAPSHOT = readFileSync(Repo.snapshot);
const utf8 = new TextEncoder();

/** A request the fake TM1 recorded: method, target (path and query relative to the service root),
 * headers (lower-case names) and body. */
export class Recorded {
  constructor(
    readonly method: string,
    readonly target: string,
    readonly headers: Record<string, string>,
    readonly body: Uint8Array,
  ) {}

  get text(): string {
    return new TextDecoder().decode(this.body);
  }

  get json(): any {
    return JSON.parse(this.text);
  }
}

/** An answer the fake gives: a status, headers and a body (JSON for an object). */
export interface Answer {
  status: number;
  headers?: Record<string, string>;
  body?: unknown;
}

/** An answer: JSON (any object), a string (text), null (204), or an {@link Answer} made by {@link FakeTm1.reply}. */
export type Respond = (request: Recorded) => unknown;

const ANSWER = Symbol("answer");

/** A fake TM1, as the .NET tests' FakeTm1: answers logins with a session cookie, `Server`'s
 * `ProductVersion` and `$metadata` with the snapshot, records every other request, and answers it
 * with {@link respond}. */
export class FakeTm1 implements Tm1Transport {
  static readonly BASE = "http://tm1:8010/api/v1/";

  readonly requests: Recorded[] = [];
  logins = 0;
  metadata: Uint8Array = SNAPSHOT;
  productVersion = "11.8.03500.6";
  respond: Respond = () => ({});

  get last(): Recorded {
    const r = this.requests[this.requests.length - 1];
    if (!r) throw new Error("no requests recorded");
    return r;
  }

  session(configure?: (b: Tm1SessionBuilder) => Tm1SessionBuilder): Tm1Session {
    const builder = Tm1Session.builder().baseUrl(FakeTm1.BASE).basicAuth("admin", "apple").transport(this);
    return (configure?.(builder) ?? builder).build();
  }

  client(configure?: (b: Tm1SessionBuilder) => Tm1SessionBuilder): Tm1Client {
    return Tm1Client.of(this.session(configure));
  }

  async send(request: Tm1HttpRequest): Promise<Tm1HttpResponse> {
    if (request.signal.aborted) throw request.signal.reason;
    // Not new URL: it would re-encode the query
    const path = request.url.replace(/^[a-z]+:\/\/[^/]+/i, "");
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(request.headers)) headers[k.toLowerCase()] = v;
    if (path === "/api/v1/ActiveSession?$select=ID" && headers["authorization"]) {
      this.logins++;
      return FakeTm1.toResponse(FakeTm1.reply(200, { ID: this.logins }, { "set-cookie": `TM1SessionId=s${this.logins}; Path=/api/; HttpOnly` }));
    }
    if (path === "/api/v1/Server?$select=ProductVersion") return FakeTm1.toResponse(FakeTm1.reply(200, { ProductVersion: this.productVersion }));
    if (path === "/api/v1/$metadata") return { status: 200, headers: {}, body: this.metadata };
    const recorded = new Recorded(request.method, path.startsWith("/api/v1/") ? path.slice("/api/v1/".length) : path, headers, request.body ?? new Uint8Array());
    this.requests.push(recorded);
    const answer = await this.respond(recorded);
    return FakeTm1.toResponse(answer);
  }

  /** An answer with a status, a body (JSON unless a string or bytes) and headers. */
  static reply(status: number, body?: unknown, headers: Record<string, string> = {}): Answer {
    return { [ANSWER]: true, status, body, headers } as Answer;
  }

  static collection(...items: unknown[]): Record<string, unknown> {
    return { value: items };
  }

  private static toResponse(answer: unknown): Tm1HttpResponse {
    if (answer !== null && typeof answer === "object" && ANSWER in answer) {
      const a = answer as unknown as Answer;
      return { status: a.status, headers: a.headers ?? {}, body: FakeTm1.bytes(a.body) };
    }
    if (answer === null || answer === undefined) return { status: 204, headers: {}, body: new Uint8Array() };
    if (typeof answer === "string") return { status: 200, headers: { "content-type": "text/plain" }, body: utf8.encode(answer) };
    return { status: 200, headers: { "content-type": "application/json" }, body: utf8.encode(JSON.stringify(answer)) };
  }

  private static bytes(body: unknown): Uint8Array {
    if (body === undefined || body === null) return new Uint8Array();
    if (body instanceof Uint8Array) return body;
    return utf8.encode(typeof body === "string" ? body : JSON.stringify(body));
  }
}

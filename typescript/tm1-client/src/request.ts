// SPDX-License-Identifier: Apache-2.0

import type { Tm1Model } from "./model.js";
import { ODataQuery, type ODataPath } from "./odata/index.js";
import type { Tm1Capability } from "./version/index.js";

/** How a request's answer is read: one JSON value, a collection (`{"value": […]}`, maybe paged)
 * streamed item by item, text, bytes, or nothing. */
export type Tm1Response = "Single" | "Collection" | "Text" | "Bytes" | "None";

/** How a request's answer is read, as values: `Tm1Response.Collection`. */
export const Tm1Response = {
  Single: "Single",
  Collection: "Collection",
  Text: "Text",
  Bytes: "Bytes",
  None: "None",
} as const satisfies Record<Tm1Response, Tm1Response>;

interface Parts<T> {
  method: string;
  path: ODataPath;
  query: ODataQuery;
  headers: Readonly<Record<string, string>>;
  body: unknown;
  response: Tm1Response;
  type: Tm1Model<T> | undefined;
  timeout: number | undefined;
  requirement: Tm1Capability | undefined;
}

/** A request of the REST API, and how its answer is read: what services send through the session,
 * and what callers can send for anything without a service, as the Java client's `Tm1Request`.
 * Immutable: each `with…` returns a new request. */
export class Tm1Request<T> {
  /** The HTTP method. */
  readonly method: string;
  /** The resource path, relative to the service root. */
  readonly path: ODataPath;
  /** The query options, possibly {@link ODataQuery.none}. */
  readonly query: ODataQuery;
  /** The headers this request adds, in the order they were added. */
  readonly headers: Readonly<Record<string, string>>;
  /** The body, serialized as JSON when sent, or null for none. */
  readonly body: unknown;
  /** How the response body is read. */
  readonly response: Tm1Response;
  /** What reads the answer (Java's `Class<T>`); undefined reads JSON as is. */
  readonly type: Tm1Model<T> | undefined;
  /** How long to wait for the answer, in milliseconds, instead of the session's request timeout. */
  readonly timeout: number | undefined;
  private readonly requirementValue: Tm1Capability | undefined;

  private constructor(parts: Parts<T>) {
    this.method = parts.method;
    this.path = parts.path;
    this.query = parts.query;
    this.headers = Object.freeze({ ...parts.headers });
    this.body = parts.body;
    this.response = parts.response;
    this.type = parts.type;
    this.timeout = parts.timeout;
    this.requirementValue = parts.requirement;
  }

  private static make<T>(
    method: string,
    path: ODataPath,
    query: ODataQuery | null | undefined,
    body: unknown,
    response: Tm1Response,
    type?: Tm1Model<T>,
  ): Tm1Request<T> {
    if (!path) throw new TypeError("path is required");
    return new Tm1Request<T>({
      method,
      path,
      query: query ?? ODataQuery.none(),
      headers: {},
      body,
      response,
      type,
      timeout: undefined,
      requirement: undefined,
    });
  }

  /** `GET` of one entity or value, read as `type`. */
  static get<T = unknown>(path: ODataPath, query?: ODataQuery | null, type?: Tm1Model<T>): Tm1Request<T> {
    return Tm1Request.make("GET", path, query, undefined, "Single", type);
  }

  /** `GET` of a collection, read item by item as `itemType`. */
  static list<T = unknown>(path: ODataPath, query?: ODataQuery | null, itemType?: Tm1Model<T>): Tm1Request<T> {
    return Tm1Request.make("GET", path, query, undefined, "Collection", itemType);
  }

  /** `GET` of raw bytes, such as a document's `Content`. */
  static bytes(path: ODataPath): Tm1Request<Uint8Array> {
    return Tm1Request.make<Uint8Array>("GET", path, undefined, undefined, "Bytes");
  }

  /** `PUT` of raw bytes, such as a document's content. */
  static put(path: ODataPath, content: Uint8Array, contentType = "application/octet-stream"): Tm1Request<void> {
    if (!(content instanceof Uint8Array)) throw new TypeError("content is a Uint8Array");
    return Tm1Request.make<void>("PUT", path, undefined, content, "None").withHeader("Content-Type", contentType);
  }

  /** `GET` of plain text, such as `Configuration/ProductVersion/$value`. */
  static text(path: ODataPath): Tm1Request<string> {
    return Tm1Request.make<string>("GET", path, undefined, undefined, "Text");
  }

  /** `POST` whose response is a collection, read item by item as `itemType`: an action returning
   * `Collection(...)`, such as `tm1.CheckRules`. The body is the parameters, written as JSON. */
  static postList<T = unknown>(path: ODataPath, body: unknown, itemType?: Tm1Model<T>): Tm1Request<T> {
    if (body === undefined || body === null) throw new TypeError("body is required");
    return Tm1Request.make("POST", path, undefined, body, "Collection", itemType);
  }

  /** `POST`, reading the answer as `type`; with no type, reading nothing (`Mono<Void>`). */
  static post<T = void>(path: ODataPath, body?: unknown, type?: Tm1Model<T>): Tm1Request<T> {
    return Tm1Request.make("POST", path, undefined, body ?? {}, type ? "Single" : "None", type);
  }

  /** `PATCH`: change the properties in `body`, leaving the rest as they are. */
  static patch(path: ODataPath, body: unknown): Tm1Request<void> {
    if (body === undefined || body === null) throw new TypeError("body is required");
    return Tm1Request.make<void>("PATCH", path, undefined, body, "None");
  }

  /** `DELETE` of the entity at `path`. */
  static delete(path: ODataPath): Tm1Request<void> {
    return Tm1Request.make<void>("DELETE", path, undefined, undefined, "None");
  }

  /** @internal GET, PUT, DELETE and HEAD: sending one again does what sending it once did. */
  static isIdempotentMethod(method: string): boolean {
    return ["GET", "PUT", "DELETE", "HEAD"].includes(method.toUpperCase());
  }

  private copy(change: Partial<Parts<T>>): Tm1Request<T> {
    return new Tm1Request<T>({
      method: this.method,
      path: this.path,
      query: this.query,
      headers: this.headers,
      body: this.body,
      response: this.response,
      type: this.type,
      timeout: this.timeout,
      requirement: this.requirementValue,
      ...change,
    });
  }

  /** A copy with these query options instead. */
  withQuery(query: ODataQuery | null | undefined): Tm1Request<T> {
    return this.copy({ query: query ?? ODataQuery.none() });
  }

  /** A copy with a header added or replaced, such as `If-Match`. */
  withHeader(name: string, value: string): Tm1Request<T> {
    if (typeof name !== "string" || typeof value !== "string") throw new TypeError("a header is a name and a value");
    return this.copy({ headers: { ...this.headers, [name]: value } });
  }

  /** @param timeout milliseconds */
  withTimeout(timeout: number): Tm1Request<T> {
    if (!(timeout > 0)) throw new RangeError("timeout must be positive: " + timeout);
    return this.copy({ timeout });
  }

  /** The request, checked against the server's `$metadata` before it's sent. */
  requires(capability: Tm1Capability): Tm1Request<T> {
    if (!capability) throw new TypeError("capability is required");
    return this.copy({ requirement: capability });
  }

  /** What the server must define for the request, if anything. */
  requirement(): Tm1Capability | undefined {
    return this.requirementValue;
  }

  /** A copy sent with `If-Match: etag`, failing with 412 if the entity has changed since. */
  ifMatch(etag: string): Tm1Request<T> {
    return this.withHeader("If-Match", etag);
  }

  /** Whether sending the request again does what sending it once did (GET, PUT, DELETE, HEAD), so
   * it may be retried after a response was lost. */
  isIdempotent(): boolean {
    return Tm1Request.isIdempotentMethod(this.method);
  }

  /** The path and query, relative to the service root. */
  relativeUri(): string {
    const q = this.query.toQueryString();
    return this.path.toString() + (q.length === 0 ? "" : "?" + q);
  }

  /** @internal */
  accept(): string {
    switch (this.response) {
      case "Bytes":
        return "*/*";
      case "Text":
        return "text/plain, application/json";
      default:
        return "application/json";
    }
  }

  /** `METHOD relative-uri`, unencoded, for logs. */
  toString(): string {
    return `${this.method} ${this.relativeUri()}`;
  }
}

// SPDX-License-Identifier: Apache-2.0

/** A request as the session sends it. */
export interface Tm1HttpRequest {
  /** The HTTP method. */
  readonly method: string;
  /** The whole URL, already encoded. */
  readonly url: string;
  /** Header names as given; `Cookie` carries `TM1SessionId` unless the transport keeps cookies. */
  readonly headers: Readonly<Record<string, string>>;
  /** The body, or undefined for none. */
  readonly body: Uint8Array | undefined;
  /** Aborted at the request's timeout or when the caller cancels. */
  readonly signal: AbortSignal;
  /** Asks not to reuse the connection afterwards (TM1 sometimes drops the one that carried a close). */
  readonly closeConnection?: boolean;
  /** For the transport to call as data arrives: when the headers come, and after each piece of the
   * body. The request timeout is an idle timeout ({@link Tm1Timeouts.request}) and restarts at each
   * call; a transport that never calls it gets the timeout over the whole exchange. */
  readonly received?: () => void;
  /** How long to wait for a connection, in milliseconds ({@link Tm1Timeouts.connect}), for a
   * transport that can tell connecting from waiting; `fetch` can't, and ignores it. */
  readonly connectTimeout?: number;
}

/** An answer: a status, headers (lower-case names; `set-cookie` values joined by newlines) and a body. */
export interface Tm1HttpResponse {
  /** The HTTP status. */
  readonly status: number;
  /** The headers, by lower-case name. */
  readonly headers: Readonly<Record<string, string>>;
  /** The whole body; empty for none. */
  readonly body: Uint8Array;
}

/** What sends a session's requests (Java's `WebClient`, .NET's `HttpMessageHandler`). A failure to
 * get any response is thrown; an error status is an answer. */
export interface Tm1Transport {
  /** Sends a request and resolves with TM1's answer, whatever its status. A header value's
   * characters go out as one byte each, as `fetch` sends them. */
  send(request: Tm1HttpRequest): Promise<Tm1HttpResponse>;
  /** True when the transport keeps cookies itself, as a browser does (`credentials: "include"`):
   * the session then never sends `Cookie` nor needs to read `Set-Cookie`. */
  readonly keepsCookies?: boolean;
}

/** Options for {@link fetchTransport}. */
export interface FetchTransportOptions {
  /** The `fetch` to send with; the global one by default. */
  fetch?: typeof globalThis.fetch;
  /** Leave the session cookie to the browser (`credentials: "include"`), which won't let a page
   * read `Set-Cookie`. The default in a browser. */
  browserCookies?: boolean;
  /** Extra options for each `fetch`, such as Node's `dispatcher` for a proxy or a self-signed certificate. */
  init?: Record<string, unknown>;
}

const isBrowser = typeof (globalThis as { document?: unknown }).document !== "undefined";

/** The default transport: `fetch`. In Node it sends the `TM1SessionId` cookie itself; in a browser
 * (or with `browserCookies`) it leaves the cookie to the browser.
 *
 * Idle connections: TM1 (11.8) closes a kept-alive connection after about 90 seconds idle, and a
 * request sent on it as it closes gets no response at all, so the Java client retires its idle
 * connections after 60 seconds. `fetch` already does better: Node's (undici) keeps an idle
 * connection for 4 seconds unless the server asks for longer, and browsers manage their own. A
 * `dispatcher` of your own (in `init`) should keep its `keepAliveTimeout` under 90 seconds too. */
export function fetchTransport(options: FetchTransportOptions = {}): Tm1Transport {
  const browserCookies = options.browserCookies ?? isBrowser;
  return {
    keepsCookies: browserCookies,
    async send(request: Tm1HttpRequest): Promise<Tm1HttpResponse> {
      const send = options.fetch ?? globalThis.fetch;
      const headers = new Headers(request.headers);
      if (request.closeConnection && !isBrowser) headers.set("Connection", "close");
      const response = await send(request.url, {
        ...options.init,
        method: request.method,
        headers,
        body: request.body as BodyInit | undefined,
        signal: request.signal,
        redirect: "manual",
        credentials: browserCookies ? "include" : "omit",
      } as RequestInit);
      const out: Record<string, string> = {};
      response.headers.forEach((value, name) => {
        out[name.toLowerCase()] = value;
      });
      const cookies = typeof response.headers.getSetCookie === "function" ? response.headers.getSetCookie() : [];
      if (cookies.length) out["set-cookie"] = cookies.join("\n");
      request.received?.();
      return { status: response.status, headers: out, body: await readBody(response, request.received) };
    },
  };
}

/** A response's whole body, read piece by piece, calling `received` after each piece. */
async function readBody(response: Response, received: (() => void) | undefined): Promise<Uint8Array> {
  if (!response.body) return new Uint8Array(await response.arrayBuffer());
  const reader = response.body.getReader();
  const pieces: Uint8Array[] = [];
  let length = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    received?.();
    pieces.push(value);
    length += value.length;
  }
  const body = new Uint8Array(length);
  let at = 0;
  for (const piece of pieces) {
    body.set(piece, at);
    at += piece.length;
  }
  return body;
}

/** A request as the session sends it. */
export interface Tm1HttpRequest {
  readonly method: string;
  readonly url: string;
  /** Header names as given; `Cookie` carries `TM1SessionId` unless the transport keeps cookies. */
  readonly headers: Readonly<Record<string, string>>;
  readonly body: Uint8Array | undefined;
  /** Aborted at the request's timeout or when the caller cancels. */
  readonly signal: AbortSignal;
  /** Asks not to reuse the connection afterwards (TM1 sometimes drops the one that carried a close). */
  readonly closeConnection?: boolean;
  /** How long to wait for a connection, in milliseconds ({@link Tm1Timeouts.connect}), for a
   * transport that can tell connecting from waiting; `fetch` can't, and ignores it. */
  readonly connectTimeout?: number;
}

/** An answer: a status, headers (lower-case names; `set-cookie` values joined by newlines) and a body. */
export interface Tm1HttpResponse {
  readonly status: number;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: Uint8Array;
}

/** What sends a session's requests (Java's `WebClient`, .NET's `HttpMessageHandler`). A failure to
 * get any response is thrown; an error status is an answer. */
export interface Tm1Transport {
  send(request: Tm1HttpRequest): Promise<Tm1HttpResponse>;
  /** True when the transport keeps cookies itself, as a browser does (`credentials: "include"`):
   * the session then never sends `Cookie` nor needs to read `Set-Cookie`. */
  readonly keepsCookies?: boolean;
}

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
 * (or with `browserCookies`) it leaves the cookie to the browser. */
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
      return { status: response.status, headers: out, body: new Uint8Array(await response.arrayBuffer()) };
    },
  };
}

// SPDX-License-Identifier: Apache-2.0

import { IllegalStateException } from "./settings.js";

const REQUEST_TIMEOUT = 30_000;
const TOKEN = /"access_token"\s*:\s*"([^"]+)"/;

/** A user's login to a TM1 v12 instance, through one of the instance's applications
 * ("service-to-service"), as the Java `Tm1Login`: TM1 v12 has no passwords of its own, so the
 * application's client ID and secret vouch for the user's name.
 *
 * A login belongs to an **instance**: its session serves every database there. Whether the user
 * may do anything in a database depends on the user existing in it.
 *
 * Get one from {@link Tm1Database.login}, then either let it log in for you ({@link authorize} or
 * {@link cookie}), or hand {@link getClientId}, {@link getClientSecret} and {@link getUser} to a
 * client that logs in itself. */
export class Tm1Login {
  readonly #authUrl: string;
  readonly #instance: string;
  readonly #clientId: string;
  readonly #clientSecret: string;
  readonly #user: string;
  #cookie: Promise<string> | undefined;

  /** A login of `user` through the application whose credentials these are.
   * @internal */
  constructor(authUrl: string, instance: string, clientId: string, clientSecret: string, user: string) {
    if (typeof user !== "string") throw new TypeError("user must be a string");
    this.#authUrl = authUrl;
    this.#instance = instance;
    this.#clientId = clientId;
    this.#clientSecret = clientSecret;
    this.#user = user;
  }

  /** The instance this login is for. */
  getInstance(): string {
    return this.#instance;
  }

  /** The TM1 user this logs in as. */
  getUser(): string {
    return this.#user;
  }

  /** The application's client ID. */
  getClientId(): string {
    return this.#clientId;
  }

  /** The application's client secret. */
  getClientSecret(): string {
    return this.#clientSecret;
  }

  /** Where a session or a token is asked for, as seen from the host, e.g.
   * `http://localhost:54321/tm1/auth/v1/`: the instance's authentication root, ending in `/`. */
  getAuthUrl(): string {
    return this.#authUrl;
  }

  /** The session cookie, `TM1SessionId=...`, for a `Cookie` header. Logs in the first time, with
   * the application's credentials as Basic authentication, and keeps the session.
   * @throws IllegalStateException if TM1 refuses the login */
  cookie(): Promise<string> {
    this.#cookie ??= this.session("Basic " + Buffer.from(this.#clientId + ":" + this.#clientSecret, "utf8").toString("base64")).catch((e: unknown) => {
      this.#cookie = undefined;
      throw e;
    });
    return this.#cookie;
  }

  /** A bearer token for the application, from TM1's token endpoint (the OAuth 2.0
   * client-credentials grant). It names no user: send it as `Authorization: Bearer` with the
   * user's name to get a session, as {@link session} does.
   * @returns the access token, a JWT
   * @throws IllegalStateException if TM1 refuses the application's credentials */
  async token(): Promise<string> {
    const response = await this.#send(
      "token",
      { "Content-Type": "application/json" },
      {
        grant_type: "client_credentials",
        client_id: this.#clientId,
        client_secret: this.#clientSecret,
      },
    );
    const token = TOKEN.exec(response.body);
    if (Math.floor(response.status / 100) !== 2 || !token) {
      throw new IllegalStateException(`TM1 gave no token for the application of instance '${this.#instance}' (HTTP ${response.status}): ${response.body}`);
    }
    return token[1]!;
  }

  /** Logs the user in with an `Authorization` header of your own, such as
   * `"Bearer " + (await login.token())`, and returns the new session's cookie. Each call makes a
   * session; {@link cookie} keeps one.
   * @param authorization the `Authorization` header's value
   * @returns the session cookie, `TM1SessionId=...`
   * @throws IllegalStateException if TM1 refuses the login */
  async session(authorization: string): Promise<string> {
    if (typeof authorization !== "string") throw new TypeError("authorization must be a string");
    const response = await this.#send("session", { Authorization: authorization, "Content-Type": "application/json" }, { User: this.#user });
    const found = response.cookies.map((value) => value.split(";", 1)[0]!.trim()).find((value) => value.startsWith("TM1SessionId="));
    if (Math.floor(response.status / 100) !== 2 || found === undefined) {
      throw new IllegalStateException(`TM1 refused the login of '${this.#user}' to instance '${this.#instance}' (HTTP ${response.status}): ${response.body}`);
    }
    return found;
  }

  /** Adds this login's session cookie to a request: what `fetch` takes as its second argument.
   *
   * ```ts
   * const response = await fetch(db.getRestUrl() + "Cubes", await db.login().authorize());
   * ```
   * @param request the request being built; none for a plain GET
   * @returns `request` with the `Cookie` header, as a new object */
  async authorize(request: RequestInit = {}): Promise<RequestInit> {
    const headers = new Headers(request.headers);
    headers.set("Cookie", await this.cookie());
    return { ...request, headers };
  }

  /** The user and the instance, never the secret. */
  toString(): string {
    return "Tm1Login[" + this.#user + " in " + this.#instance + "]";
  }

  async #send(path: string, headers: Record<string, string>, json: unknown): Promise<{ status: number; body: string; cookies: string[] }> {
    try {
      const response = await fetch(this.#authUrl + path, {
        method: "POST",
        headers,
        body: JSON.stringify(json),
        // As Java's HttpClient, which follows no redirect: the answer itself carries the cookie
        redirect: "manual",
        signal: AbortSignal.timeout(REQUEST_TIMEOUT),
      });
      return { status: response.status, body: await response.text(), cookies: response.headers.getSetCookie() };
    } catch (e) {
      throw new Error("Couldn't reach TM1 at " + this.#authUrl, { cause: e });
    }
  }
}

// SPDX-License-Identifier: Apache-2.0

import type { KeycloakContainer } from "./KeycloakContainer.js";
import { IllegalStateException } from "./settings.js";
import type { Tm1Database } from "./Tm1Database.js";

const REQUEST_TIMEOUT = 30_000;
const TOKEN = /"access_token"\s*:\s*"([^"]+)"/;

/** What makes a login an OAuth 2.0 one: where its tokens come from, the user's password there, and
 * a database whose address a token is turned into a session at.
 * @internal */
export interface OAuth2 {
  /** The Keycloak the tokens come from. */
  keycloak: KeycloakContainer;
  /** The realm of it the user is in. */
  realm: string;
  /** The client of the realm a token is asked for as. */
  client: string;
  /** A database of the instance, asked for the engine's addresses when they're needed. */
  database: Tm1Database;
  /** The user's password at Keycloak. */
  password: string;
}

/** A user's login to a TM1 v12 instance, through one of the instance's applications
 * ("service-to-service"), as the Java `Tm1Login`: TM1 v12 has no passwords of its own, so the
 * application's client ID and secret vouch for the user's name.
 *
 * A login belongs to an **instance**: its session serves every database there. Whether the user
 * may do anything in a database depends on the user existing in it.
 *
 * Get one from {@link Tm1Database.login}, then either let it log in for you ({@link authorize} or
 * {@link cookie}), or hand {@link getClientId}, {@link getClientSecret} and {@link getUser} to a
 * client that logs in itself.
 *
 * A login from {@link Tm1Database.oauth2Login} is through OAuth 2.0 instead ({@link isOAuth2}): its
 * token comes from the engine's identity provider, with the user's password there, and TM1 takes
 * it on any request ({@link authorizeWithToken}). */
export class Tm1Login {
  readonly #authUrl: string;
  readonly #instance: string;
  readonly #clientId: string;
  readonly #clientSecret: string;
  readonly #user: string;
  #cookie: Promise<string> | undefined;
  readonly #oauth2: OAuth2 | undefined;

  /** A login of `user` through the application whose credentials these are; or, with `oauth2`,
   * through OAuth 2.0, with tokens from the engine's Keycloak.
   * @internal */
  constructor(authUrl: string, instance: string, clientId: string, clientSecret: string, user: string, oauth2?: OAuth2) {
    if (typeof user !== "string") throw new TypeError("user must be a string");
    this.#authUrl = authUrl;
    this.#instance = instance;
    this.#clientId = clientId;
    this.#clientSecret = clientSecret;
    this.#user = user;
    this.#oauth2 = oauth2;
  }

  /** A login through OAuth 2.0. Addresses are asked of the database when they're needed: a login
   * can be made before the engine has started.
   * @internal */
  static oauth2(user: string, oauth2: OAuth2): Tm1Login {
    return new Tm1Login("", oauth2.database.getInstance(), oauth2.client, "", user, oauth2);
  }

  /** Whether this is a login through OAuth 2.0 ({@link Tm1Database.oauth2Login}), rather than
   * through the instance's application. */
  isOAuth2(): boolean {
    return this.#oauth2 !== undefined;
  }

  /** The user's password at the identity provider, for an OAuth 2.0 login; empty otherwise, as
   * TM1 v12 keeps none. */
  getPassword(): string {
    return this.#oauth2?.password ?? "";
  }

  /** Where a token is asked for: the identity provider's token endpoint for an OAuth 2.0 login,
   * as seen from the host, and TM1's own otherwise. */
  getTokenUrl(): string {
    const from = this.#oauth2;
    return from === undefined ? this.#authUrl + "token" : from.keycloak.getUrl() + "/realms/" + from.realm + "/protocol/openid-connect/token";
  }

  /** The instance this login is for. */
  getInstance(): string {
    return this.#instance;
  }

  /** The TM1 user this logs in as. */
  getUser(): string {
    return this.#user;
  }

  /** The application's client ID; for an OAuth 2.0 login, the provider's client a token is asked
   * for as. */
  getClientId(): string {
    return this.#clientId;
  }

  /** The application's client secret; empty for an OAuth 2.0 login, whose client has none. */
  getClientSecret(): string {
    return this.#clientSecret;
  }

  /** Where a session or a token is asked for, as seen from the host, e.g.
   * `http://localhost:54321/tm1/auth/v1/`: the instance's authentication root, ending in `/`. */
  getAuthUrl(): string {
    const from = this.#oauth2;
    return from === undefined ? this.#authUrl : from.database.getEngine().getUrl() + this.#instance + "/auth/v1/";
  }

  /** The session cookie, `TM1SessionId=...`, for a `Cookie` header. Logs in the first time, with
   * the application's credentials as Basic authentication, and keeps the session. An OAuth 2.0
   * login sends the provider's token on a request instead, and keeps the session TM1 gives it.
   * @throws IllegalStateException if TM1 refuses the login */
  cookie(): Promise<string> {
    const from = this.#oauth2;
    const login =
      from === undefined
        ? (): Promise<string> => this.session("Basic " + Buffer.from(this.#clientId + ":" + this.#clientSecret, "utf8").toString("base64"))
        : async (): Promise<string> => {
            // TM1 gives a session to the first request that carries the provider's token
            const token = await this.token();
            return this.#sessionCookie(
              await this.#request(from.database.getRestUrl() + "ActiveUser?$select=Name", { headers: { Authorization: "Bearer " + token } }),
            );
          };
    this.#cookie ??= login().catch((e: unknown) => {
      this.#cookie = undefined;
      throw e;
    });
    return this.#cookie;
  }

  /** A bearer token for the application, from TM1's token endpoint (the OAuth 2.0
   * client-credentials grant). It names no user: send it as `Authorization: Bearer` with the
   * user's name to get a session, as {@link session} does.
   *
   * For an OAuth 2.0 login it's the user's own token, from the identity provider, by the password
   * grant: TM1 takes it on any request ({@link authorizeWithToken}).
   * @returns the access token, a JWT
   * @throws IllegalStateException if TM1 refuses the application's credentials, or the provider
   * the user's */
  async token(): Promise<string> {
    const from = this.#oauth2;
    if (from !== undefined) return from.keycloak.accessToken(from.realm, this.#clientId, this.#user, from.password);
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
   * @throws IllegalStateException if TM1 refuses the login, or for an OAuth 2.0 login, which has
   * no session to ask for */
  async session(authorization: string): Promise<string> {
    if (this.#oauth2 !== undefined) {
      throw new IllegalStateException(
        "An OAuth 2.0 login has no session to ask for: TM1 takes its token on any request (authorizeWithToken), and cookie() keeps the session that gives",
      );
    }
    if (typeof authorization !== "string") throw new TypeError("authorization must be a string");
    return this.#sessionCookie(await this.#send("session", { Authorization: authorization, "Content-Type": "application/json" }, { User: this.#user }));
  }

  #sessionCookie(response: { status: number; body: string; cookies: string[] }): string {
    const found = response.cookies.map((value) => value.split(";", 1)[0]!.trim()).find((value) => value.startsWith("TM1SessionId="));
    if (Math.floor(response.status / 100) !== 2 || found === undefined) {
      throw new IllegalStateException(`TM1 refused the login of '${this.#user}' to instance '${this.#instance}' (HTTP ${response.status}): ${response.body}`);
    }
    return found;
  }

  /** Adds this login's session cookie to a request: what `fetch` takes as its second argument, as
   * in `fetch(url, await db.login().authorize())`.
   * @param request the request being built; none for a plain GET
   * @returns `request` with the `Cookie` header, as a new object */
  async authorize(request: RequestInit = {}): Promise<RequestInit> {
    const headers = new Headers(request.headers);
    headers.set("Cookie", await this.cookie());
    return { ...request, headers };
  }

  /** Adds this login's token to a request as `Authorization: Bearer`, as a program with a token
   * of its own would send it: what `fetch` takes as its second argument. For an OAuth 2.0 login,
   * a new token from the provider each time; TM1 answers with a session cookie.
   * @param request the request being built; none for a plain GET
   * @returns `request` with the `Authorization` header, as a new object
   * @throws IllegalStateException for a login through the application, whose token TM1 takes
   * only at its session endpoint ({@link session}) */
  async authorizeWithToken(request: RequestInit = {}): Promise<RequestInit> {
    if (this.#oauth2 === undefined) {
      throw new IllegalStateException(
        'TM1 takes its own token only where a session is asked for: use session("Bearer " + token()), or authorize for the cookie',
      );
    }
    const headers = new Headers(request.headers);
    headers.set("Authorization", "Bearer " + (await this.token()));
    return { ...request, headers };
  }

  /** The user and the instance, never the secret. */
  toString(): string {
    return "Tm1Login[" + this.#user + " in " + this.#instance + "]";
  }

  /** POSTs JSON to a path of the instance's authentication root. */
  #send(path: string, headers: Record<string, string>, json: unknown): Promise<{ status: number; body: string; cookies: string[] }> {
    return this.#request(this.#authUrl + path, { method: "POST", headers, body: JSON.stringify(json) });
  }

  async #request(url: string, request: RequestInit): Promise<{ status: number; body: string; cookies: string[] }> {
    try {
      // As Java's HttpClient, which follows no redirect: the answer itself carries the cookie
      const response = await fetch(url, { ...request, redirect: "manual", signal: AbortSignal.timeout(REQUEST_TIMEOUT) });
      return { status: response.status, body: await response.text(), cookies: response.headers.getSetCookie() };
    } catch (e) {
      throw new Error("Couldn't reach TM1 at " + this.getAuthUrl(), { cause: e });
    }
  }
}

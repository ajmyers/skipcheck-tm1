// SPDX-License-Identifier: Apache-2.0

import type { Tm1Version } from "@skipcheck/tm1-client";
import type { Tm1EngineContainer } from "./Tm1EngineContainer.js";
import type { Tm1Login } from "./Tm1Login.js";

/** A database of a {@link Tm1EngineContainer}: what a test of TM1 v12 uses, as the Java
 * `Tm1Database`.
 *
 * An engine holds instances, and an instance holds databases. The REST API of one database is at
 * {@link getRestUrl}; a user logs in to its instance ({@link login}), and the session serves every
 * database there. */
export class Tm1Database {
  readonly #engine: Tm1EngineContainer;
  readonly #instance: string;
  readonly #name: string;

  /** The database `name` of `instance` in `engine`; get one from the engine.
   * @internal */
  constructor(engine: Tm1EngineContainer, instance: string, name: string) {
    this.#engine = engine;
    this.#instance = instance;
    this.#name = name;
  }

  /** The engine this database is in. */
  getEngine(): Tm1EngineContainer {
    return this.#engine;
  }

  /** The instance this database is in; `tm1` unless one was named. */
  getInstance(): string {
    return this.#instance;
  }

  /** The database's name. */
  getName(): string {
    return this.#name;
  }

  /** The REST API's service root as seen from the host, ending in `/`, e.g.
   * `http://localhost:54321/tm1/api/v1/Databases('planning')/`. */
  getRestUrl(): string {
    return this.#engine.getUrl() + this.#instance + "/api/v1/Databases('" + this.#name.replaceAll("'", "''") + "')/";
  }

  /** The version of TM1 the engine runs. Only available once it has started. */
  getVersion(): Tm1Version {
    return this.#engine.getTm1Version();
  }

  /** A login as `user`; as `admin`, the administrator every database has, when none is named. TM1
   * makes a session for any name; a user who isn't in this database is then refused here (403).
   * @param user the user's name */
  login(user: string = "admin"): Tm1Login {
    return this.#engine.login(this.#instance, user);
  }

  /** A login through OAuth 2.0: as a user the engine's Keycloak knows the password of
   * ({@link Tm1EngineContainer.withLogin}). The login gets a token from Keycloak with that password
   * and sends it to TM1, as a program acting for the user would.
   * @param user the user's name
   * @throws IllegalStateException if the engine has no {@link Tm1EngineContainer.withOAuth2}
   * @throws IllegalArgumentException if the user has no password */
  oauth2Login(user: string): Tm1Login {
    return this.#engine.oauth2Login(this, user);
  }

  /** A pass-through login as the TM1 11 server's own user (its `getUsername()`):
   * the administrator of a server with its own users, or the first administrator of one in Cognos
   * (CAM) security. The login sends TM1 v12 what that server takes, and TM1 v12 asks it who that
   * is.
   * @throws IllegalStateException if the engine has no {@link Tm1EngineContainer.withPassThrough} */
  passThroughLogin(): Tm1Login;
  /** A pass-through login as a user whose password the containers know: one of Cognos's test users
   * when the TM1 11 server is in CAM security, and otherwise that server's own user only.
   * @param user the user's name on the TM1 11 server, or in Cognos
   * @throws IllegalStateException if the engine has no {@link Tm1EngineContainer.withPassThrough}
   * @throws IllegalArgumentException if the user's password isn't known */
  passThroughLogin(user: string): Tm1Login;
  /** A pass-through login with a name and password of your own: a user you created on the TM1 11
   * server, or a wrong password to see refused. They go as `Basic`, or as `CAMNamespace` when the
   * server is in CAM security.
   * @param user the user's name on the TM1 11 server, or in Cognos
   * @param password the password
   * @throws IllegalStateException if the engine has no {@link Tm1EngineContainer.withPassThrough} */
  passThroughLogin(user: string, password: string): Tm1Login;
  passThroughLogin(user?: string, password?: string): Tm1Login {
    if (user !== undefined && typeof user !== "string") throw new TypeError("user must be a string");
    if (password !== undefined && typeof password !== "string") throw new TypeError("password must be a string");
    if (user === undefined && password !== undefined) throw new TypeError("user must be a string");
    return this.#engine.passThroughLogin(this, user, password);
  }

  /** A pass-through login with an `Authorization` header of your own, which TM1 v12 passes on as
   * it is: `"CAMPassport " + passport` from a Cognos logon, say.
   * @param authorization the header's value
   * @returns the login, with no user name or password
   * @throws IllegalStateException if the engine has no {@link Tm1EngineContainer.withPassThrough} */
  passThroughLoginWith(authorization: string): Tm1Login {
    return this.#engine.passThroughLoginWith(this, authorization);
  }

  /** Whether `other` is the same database: this name, in this instance of this engine. */
  equals(other: unknown): boolean {
    return other instanceof Tm1Database && this.#engine === other.#engine && this.#instance === other.#instance && this.#name === other.#name;
  }

  /** The instance and the name, for logs: `Tm1Database[tm1/planning]`. */
  toString(): string {
    return "Tm1Database[" + this.#instance + "/" + this.#name + "]";
  }
}

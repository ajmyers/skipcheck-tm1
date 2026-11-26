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

  /** Whether `other` is the same database: this name, in this instance of this engine. */
  equals(other: unknown): boolean {
    return other instanceof Tm1Database && this.#engine === other.#engine && this.#instance === other.#instance && this.#name === other.#name;
  }

  /** The instance and the name, for logs: `Tm1Database[tm1/planning]`. */
  toString(): string {
    return "Tm1Database[" + this.#instance + "/" + this.#name + "]";
  }
}

// SPDX-License-Identifier: Apache-2.0

import type { Tm1Database, Tm1Login } from "../../src/index.js";

/** What TM1 answered: the status, and the body as it sent it. */
export interface Tm1EngineResponse {
  status: number;
  body: string;
  /** Each `Set-Cookie` header TM1 sent. */
  cookies: string[];
}

/** Plain HTTP against a TM1 v12 database, for the tests, as Java's test `Tm1EngineRest`: no client
 * library, so a client bug can't break the tests of the containers. */
export const Tm1EngineRest = {
  /** GETs a path of the database's REST API, logged in. */
  async get(database: Tm1Database, login: Tm1Login, path: string): Promise<Tm1EngineResponse> {
    return Tm1EngineRest.send(database.getRestUrl() + path, await login.authorize());
  },

  /** POSTs JSON to a path of the database's REST API, logged in. */
  async post(database: Tm1Database, login: Tm1Login, path: string, json: string): Promise<Tm1EngineResponse> {
    return Tm1EngineRest.send(
      database.getRestUrl() + path,
      await login.authorize({ method: "POST", headers: { "Content-Type": "application/json" }, body: json }),
    );
  },

  /** DELETEs a path of the database's REST API, logged in. */
  async delete(database: Tm1Database, login: Tm1Login, path: string): Promise<Tm1EngineResponse> {
    return Tm1EngineRest.send(database.getRestUrl() + path, await login.authorize({ method: "DELETE" }));
  },

  /** Sends a request of your own making. */
  async send(url: string, request: RequestInit = {}): Promise<Tm1EngineResponse> {
    const response = await fetch(url, { ...request, redirect: "manual", signal: AbortSignal.timeout(60_000) });
    return { status: response.status, body: await response.text(), cookies: response.headers.getSetCookie() };
  },
};

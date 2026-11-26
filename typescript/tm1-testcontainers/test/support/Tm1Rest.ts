// SPDX-License-Identifier: Apache-2.0

import type { Tm1ServerContainer } from "../../src/index.js";

/** A minimal TM1 REST session for the examples' tests, as Java's test `Tm1Rest`: it sends the
 * `Authorization` it's given (a Cognos user's `CAMNamespace` or `CAMPassport` logon too), and
 * answers with the body as TM1 sent it. In your own tests you'd build a `Tm1Client` instead.
 *
 * It logs in once, reuses the session cookie, and closes the session when done, since TM1 opens a
 * new session for every request that doesn't send one back. */
export class Tm1Rest implements AsyncDisposable {
  readonly #baseUrl: string;
  readonly #authorization: string;
  #cookie: string | undefined;

  /** A session with the container's login, or its own, e.g. `CognosContainer.camAuthorization`. */
  constructor(tm1: Tm1ServerContainer, authorization: string = tm1.getAuthorization()) {
    this.#baseUrl = tm1.getRestUrl();
    this.#authorization = authorization;
  }

  /** GETs a path under the service root and returns the body. Fails on anything but 200. */
  async get(path: string): Promise<string> {
    const headers: Record<string, string> = this.#cookie === undefined ? { Authorization: this.#authorization } : { Cookie: this.#cookie };
    const response = await fetch(this.#baseUrl + path, { headers });
    const body = await response.text();
    if (response.status !== 200) throw new Error(`GET ${path} returned ${response.status}: ${body}`);
    this.#cookie ??= response.headers
      .getSetCookie()
      .map((c) => c.split(";", 2)[0]!.trim())
      .find((c) => c.startsWith("TM1SessionId="));
    return body;
  }

  /** POSTs a JSON body to a path under the service root and returns the response, whatever its
   * status. */
  async post(path: string, json: string): Promise<{ status: number; body: string }> {
    if (this.#cookie === undefined) await this.get("ActiveUser?$select=Name"); // logs in and keeps the session cookie
    const response = await fetch(this.#baseUrl + path, { method: "POST", headers: this.#session(), body: json });
    return { status: response.status, body: await response.text() };
  }

  /** The number of cubes, excluding control cubes (whose names start with '}'). */
  async modelCubeCount(): Promise<number> {
    const count = (await this.get("Cubes/$count?$filter=not%20startswith(Name,'%7D')")).trim();
    if (!/^\d+$/.test(count)) throw new Error("Not a count: " + count);
    return Number.parseInt(count, 10);
  }

  async close(): Promise<void> {
    if (this.#cookie === undefined) return;
    const response = await fetch(this.#baseUrl + "ActiveSession/tm1.Close", { method: "POST", headers: this.#session(), body: "{}" });
    await response.text();
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }

  #session(): Record<string, string> {
    if (this.#cookie === undefined) throw new Error("TM1 sent no TM1SessionId cookie at logon");
    return { Cookie: this.#cookie, "Content-Type": "application/json" };
  }
}

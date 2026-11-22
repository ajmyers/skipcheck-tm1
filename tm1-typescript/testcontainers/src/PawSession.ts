const REQUEST_TIMEOUT = 30_000;
const NAME = /"Name"\s*:\s*"((?:[^"\\]|\\.)*)"/g;

/** An HTTP answer: its status and body. */
export interface PawResponse {
  status: number;
  body: string;
}

/** A user's session with PAW, as a browser has one: PAW's logon form in TM1-security mode, then
 * calls to TM1 through PAW's proxy. Package-private in Java; exported here for the tests, not
 * from the package.
 * @internal */
export class PawSession {
  readonly #url: string;
  readonly #cookies = new Map<string, string>();

  constructor(url: string) {
    this.#url = url;
  }

  /** Logs on with PAW's form.
   * @throws Error if PAW refused */
  async login(username: string, password: string): Promise<this> {
    const page = await this.#send(this.#url + "login", { method: "GET" });
    if (page.status !== 200) throw new Error("PAW's logon page answered " + page.status);
    const headers: Record<string, string> = { "Content-Type": "application/x-www-form-urlencoded" };
    const csrf = this.#cookies.get("ba-sso-csrf");
    if (csrf !== undefined) headers["ba-sso-authenticity"] = csrf;
    const response = await this.#send(this.#url + "login/form/", { method: "POST", headers, body: new URLSearchParams({ mode: "basic", username, password }).toString() });
    if (response.status !== 200 || response.body.includes('error = "')) {
      throw new Error(`PAW refused the logon of ${username} (${response.status})`);
    }
    return this;
  }

  /** The TM1 servers PAW lists. */
  async servers(): Promise<string[]> {
    const response = await this.get("tm1/api/v1/Servers");
    if (response.status !== 200) throw new Error(`PAW couldn't list TM1 servers (${response.status}): ${response.body}`);
    return [...response.body.matchAll(NAME)].map((m) => m[1]!);
  }

  /** A GET of `path` on `server`'s REST API, through PAW. */
  tm1Get(server: string, path: string): Promise<PawResponse> {
    return this.get("tm1/" + encodeURIComponent(server) + "/api/v1/" + path);
  }

  /** A GET of `path` on PAW. */
  get(path: string): Promise<PawResponse> {
    return this.#send(this.#url + path, { method: "GET" });
  }

  async #send(url: string, init: RequestInit): Promise<PawResponse> {
    const headers = new Headers(init.headers);
    if (this.#cookies.size > 0) headers.set("Cookie", [...this.#cookies].map(([k, v]) => k + "=" + v).join("; "));
    const response = await fetch(url, { ...init, headers, redirect: "manual", signal: AbortSignal.timeout(REQUEST_TIMEOUT) });
    for (const header of response.headers.getSetCookie()) {
      const pair = header.split(";", 1)[0]!;
      const eq = pair.indexOf("=");
      if (eq > 0) this.#cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim().replace(/^"(.*)"$/, "$1"));
    }
    return { status: response.status, body: await response.text() };
  }
}

import { randomUUID } from "node:crypto";
import type { Tm1WebContainer } from "../../src/index.js";

// One line per callback: "." stops at the line end, so the match ends at its ");"
const CALLBACK = /handleCallback\("\d+","0",(.*)\);/;
const ERROR = /handle(?:Batch)?Exception\(([\s\S]*?)\);/;
const NAME = /name:"([^"]*)"/g;
const SESSION_ID = /tm1SessionId:"([^"]*)"/;

interface Cookie {
  value: string;
  path: string;
}

/** A browser's session with TM1 Web, as Java's test `Tm1WebSession`: TM1 Web's DWR calls (list
 * servers, log on) and its evalservice. Keeps cookies by path, as a browser does. */
export class Tm1WebSession {
  readonly #base: URL;
  readonly #cookies = new Map<string, Cookie>();
  #scriptSessionId = "";
  #batch = 0;

  private constructor(web: Tm1WebContainer) {
    this.#base = new URL(web.getUrl());
  }

  static async open(web: Tm1WebContainer): Promise<Tm1WebSession> {
    const session = new Tm1WebSession(web);
    await session.#send(session.#base, { method: "GET" });
    const id = (await session.#call("__System", "generateId")).trim().replace(/^"|"$/g, "");
    session.#cookies.set("DWRSESSIONID", { value: id, path: session.#base.pathname.replace(/\/$/, "") });
    session.#scriptSessionId = id + "/" + randomUUID();
    return session;
  }

  async servers(adminHost: string): Promise<string[]> {
    return [...(await this.#call("TM1Service", "getTM1Servers", "string:" + adminHost)).matchAll(NAME)].map((m) => m[1]!);
  }

  async login(adminHost: string, server: string, username: string, password: string): Promise<string> {
    const result = await this.#call("TM1Service", "login", "string:" + adminHost, "string:" + server, "string:" + username, "string:" + password);
    const m = SESSION_ID.exec(result);
    if (!m) throw new Error("TM1 Web logged on but sent no TM1 session ID: " + result);
    return m[1]!;
  }

  async evaluateMdx(serviceRoot: string, server: string, tm1SessionId: string, mdx: string): Promise<string> {
    const json = JSON.stringify;
    const token = `{"host":${json(serviceRoot)},"type":"tm1","id":${json(tm1SessionId)}}`;
    const mapping = `{"server":${json(server)},"host":${json(serviceRoot)},"datasource":${json(server)}}`;
    const entry = `{"type":"mdx","id":"1","server":${json(server)},"args":[${json(mdx)}]}`;
    const body = `{"tokens":[${token}],"mappings":[${mapping}],"workEntries":[${entry}]}`;
    return (await this.#send(new URL("/evalservice/batch", this.#base), { method: "POST", headers: { "Content-Type": "application/json" }, body })).body;
  }

  async #call(script: string, method: string, ...params: string[]): Promise<string> {
    this.#batch++;
    let body = "callCount=1\nnextReverseAjaxIndex=0\n";
    body += `c0-scriptName=${script}\nc0-methodName=${method}\nc0-id=0\n`;
    params.forEach((p, i) => (body += `c0-param${i}=${p}\n`));
    body += `batchId=${this.#batch}\ninstanceId=0\npage=${this.#base.pathname}\n`;
    body += `httpSessionId=${this.#cookies.get("JSESSIONID")?.value ?? ""}\n`;
    body += `scriptSessionId=${this.#scriptSessionId}\n`;
    const response = await this.#send(new URL(`dwr/call/plaincall/${script}.${method}.dwr`, this.#base), {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body,
    });
    const result = CALLBACK.exec(response.body);
    if (response.status === 200 && result) return result[1]!;
    const error = ERROR.exec(response.body);
    throw new Error(`${script}.${method} failed (HTTP ${response.status}): ${error ? error[1] : response.body}`);
  }

  async #send(url: URL, init: RequestInit): Promise<{ status: number; body: string }> {
    const headers = new Headers(init.headers);
    const cookies = [...this.#cookies].filter(([, c]) => url.pathname.startsWith(c.path)).map(([k, c]) => k + "=" + c.value);
    if (cookies.length > 0) headers.set("Cookie", cookies.join("; "));
    // As Java's HttpClient: no redirects followed
    const response = await fetch(url, { ...init, headers, redirect: "manual", signal: AbortSignal.timeout(60_000) });
    for (const header of response.headers.getSetCookie()) {
      const [pair, ...attributes] = header.split(";");
      const eq = pair!.indexOf("=");
      if (eq <= 0) continue;
      const path =
        attributes
          .map((a) => a.trim())
          .find((a) => a.toLowerCase().startsWith("path="))
          ?.slice(5) ??
        url.pathname.replace(/\/[^/]*$/, "") ??
        "/";
      this.#cookies.set(pair!.slice(0, eq).trim(), { value: pair!.slice(eq + 1).trim(), path: path || "/" });
    }
    return { status: response.status, body: await response.text() };
  }
}

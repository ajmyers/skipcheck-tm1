// SPDX-License-Identifier: Apache-2.0

import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { inspect } from "node:util";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { IllegalStateException, Tm1Login } from "../src/index.js";

interface Asked {
  method: string | undefined;
  path: string | undefined;
  authorization: string | undefined;
  contentType: string | undefined;
  cookie: string | undefined;
  body: string;
}

/** A login to TM1 v12, without TM1: a local server stands in for an instance's auth/v1, answers as
 * it's told to, and keeps what it was asked. What the real TM1 answers is
 * engine-containers.test.ts's. */
describe("Tm1Login", () => {
  const asked: Asked[] = [];
  let answer: { status: number; headers: Record<string, string | string[]>; body: string };
  let server: Server;
  let url: string;

  beforeAll(async () => {
    server = createServer((request: IncomingMessage, response) => {
      let body = "";
      request.on("data", (chunk: Buffer) => (body += chunk.toString()));
      request.on("end", () => {
        const { authorization, cookie } = request.headers;
        asked.push({ method: request.method, path: request.url, authorization, contentType: request.headers["content-type"], cookie, body });
        response.writeHead(answer.status, answer.headers).end(answer.body);
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    url = "http://127.0.0.1:" + (server.address() as AddressInfo).port + "/tm1/auth/v1/";
  });

  afterAll(async () => {
    await new Promise((resolve) => server.close(resolve));
  });

  const login = (user = "analyst"): Tm1Login => new Tm1Login(url, "tm1", "the-id", 'the "secret"', user);
  const session = { status: 201, headers: { "Set-Cookie": ["other=1; Path=/", "TM1SessionId=abc123; Path=/tm1; HttpOnly"] }, body: "" };

  it("logs in with the application's credentials as Basic, once", async () => {
    asked.length = 0;
    answer = session;
    const analyst = login();
    expect(await analyst.cookie()).toBe("TM1SessionId=abc123");
    expect(await analyst.cookie()).toBe("TM1SessionId=abc123");
    expect(asked).toEqual([
      {
        method: "POST",
        path: "/tm1/auth/v1/session",
        authorization: "Basic " + Buffer.from('the-id:the "secret"').toString("base64"),
        contentType: "application/json",
        cookie: undefined,
        body: '{"User":"analyst"}',
      },
    ]);
  });

  it("adds the session cookie to a request, and keeps what the request had", async () => {
    answer = session;
    const analyst = login();
    const request = await analyst.authorize({ method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    expect(request.method).toBe("POST");
    expect(request.body).toBe("{}");
    asked.length = 0;
    answer = { status: 200, headers: {}, body: "ok" };
    expect((await fetch(url + "anything", request)).status).toBe(200);
    expect(asked[0]).toMatchObject({ method: "POST", cookie: "TM1SessionId=abc123", contentType: "application/json", body: "{}" });
    // None to start from is a plain GET
    expect(new Headers((await analyst.authorize()).headers).get("Cookie")).toBe("TM1SessionId=abc123");
  });

  it("asks for a token with the client-credentials grant", async () => {
    asked.length = 0;
    answer = {
      status: 200,
      headers: { "Content-Type": "application/json" },
      body: '{"token_type":"Bearer", "access_token" : "aaa.bbb.ccc","expires_in":3600}',
    };
    expect(await login().token()).toBe("aaa.bbb.ccc");
    expect(asked).toHaveLength(1);
    expect(asked[0]).toMatchObject({ method: "POST", path: "/tm1/auth/v1/token", authorization: undefined, contentType: "application/json" });
    expect(JSON.parse(asked[0]!.body)).toEqual({ grant_type: "client_credentials", client_id: "the-id", client_secret: 'the "secret"' });
  });

  it("logs in with an Authorization of your own, making a session each time", async () => {
    asked.length = 0;
    answer = session;
    const analyst = login();
    expect(await analyst.session("Bearer aaa.bbb.ccc")).toBe("TM1SessionId=abc123");
    expect(await analyst.session("Bearer aaa.bbb.ccc")).toBe("TM1SessionId=abc123");
    expect(asked.map((a) => a.authorization)).toEqual(["Bearer aaa.bbb.ccc", "Bearer aaa.bbb.ccc"]);
  });

  it("says what TM1 answered when it refuses", async () => {
    answer = { status: 401, headers: {}, body: "no such application" };
    const analyst = login();
    await expect(analyst.cookie()).rejects.toThrow(IllegalStateException);
    await expect(analyst.cookie()).rejects.toThrow("TM1 refused the login of 'analyst' to instance 'tm1' (HTTP 401): no such application");
    await expect(analyst.token()).rejects.toThrow("TM1 gave no token for the application of instance 'tm1' (HTTP 401): no such application");
    // A session without the cookie isn't one, nor a token answer without a token
    answer = { status: 200, headers: { "Set-Cookie": "other=1" }, body: "{}" };
    await expect(analyst.cookie()).rejects.toThrow("TM1 refused the login of 'analyst' to instance 'tm1' (HTTP 200): {}");
    await expect(analyst.token()).rejects.toThrow("TM1 gave no token for the application of instance 'tm1' (HTTP 200): {}");
    // A refused login isn't kept: the next one asks again
    answer = session;
    expect(await analyst.cookie()).toBe("TM1SessionId=abc123");
  });

  it("says where TM1 was when it can't be reached", async () => {
    const nowhere = new Tm1Login("http://127.0.0.1:1/tm1/auth/v1/", "tm1", "id", "secret", "admin");
    await expect(nowhere.cookie()).rejects.toThrow("Couldn't reach TM1 at http://127.0.0.1:1/tm1/auth/v1/");
  });

  it("never prints the secret", () => {
    const analyst = login();
    expect(String(analyst)).toBe("Tm1Login[analyst in tm1]");
    expect(inspect(analyst)).not.toContain("secret");
    expect(JSON.stringify(analyst)).not.toContain("secret");
    expect([analyst.getInstance(), analyst.getUser(), analyst.getClientId(), analyst.getClientSecret(), analyst.getAuthUrl()]).toEqual([
      "tm1",
      "analyst",
      "the-id",
      'the "secret"',
      url,
    ]);
  });
});

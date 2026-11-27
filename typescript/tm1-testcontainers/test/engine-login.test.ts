// SPDX-License-Identifier: Apache-2.0

import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { inspect } from "node:util";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  IllegalStateException,
  KeycloakContainer,
  type Tm1Database,
  Tm1EngineContainer,
  Tm1EngineSource,
  Tm1Login,
  Tm1ServerContainer,
  Tm1Source,
} from "../src/index.js";

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
  type Answer = { status: number; headers: Record<string, string | string[]>; body: string };
  let answer: Answer;
  // What Keycloak's stand-in says, when a test has one: the same server, asked for a realm's token
  let keycloakAnswer: Answer | undefined;
  let server: Server;
  let url: string;

  beforeAll(async () => {
    server = createServer((request: IncomingMessage, response) => {
      let body = "";
      request.on("data", (chunk: Buffer) => (body += chunk.toString()));
      request.on("end", () => {
        const { authorization, cookie } = request.headers;
        asked.push({ method: request.method, path: request.url, authorization, contentType: request.headers["content-type"], cookie, body });
        const says = keycloakAnswer !== undefined && request.url?.startsWith("/realms/") ? keycloakAnswer : answer;
        response.writeHead(says.status, says.headers).end(says.body);
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

  /** An OAuth 2.0 login, as an engine with a Keycloak gives one: the server stands in for the
   * Keycloak (its realm's token endpoint) and for TM1 (a database's REST API). */
  describe("through OAuth 2.0", () => {
    const token = { status: 200, headers: { "Content-Type": "application/json" }, body: '{"access_token":"aaa.bbb.ccc","token_type":"Bearer"}' };
    let root: string;

    const oauth2Login = (): Tm1Login => {
      root = url.replace("tm1/auth/v1/", "");
      const keycloak = new (class extends KeycloakContainer {
        override getUrl(): string {
          return root.replace(/\/$/, "");
        }
      })();
      const engine = new (class extends Tm1EngineContainer {
        override getUrl(): string {
          return root;
        }
      })(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
        .withUser("analyst")
        .withLogin("analyst", "an$pw&more")
        .withOAuth2(keycloak);
      const database: Tm1Database = engine.database("tm1");
      return database.oauth2Login("analyst");
    };

    afterAll(() => {
      keycloakAnswer = undefined;
    });

    it("asks Keycloak for a token as the user, by the password grant", async () => {
      asked.length = 0;
      keycloakAnswer = token;
      const analyst = oauth2Login();
      expect(analyst.getTokenUrl()).toBe(root + "realms/tm1/protocol/openid-connect/token");
      expect(analyst.getAuthUrl()).toBe(url);
      expect(await analyst.token()).toBe("aaa.bbb.ccc");
      expect(asked).toHaveLength(1);
      expect(asked[0]).toMatchObject({ method: "POST", path: "/realms/tm1/protocol/openid-connect/token", contentType: "application/x-www-form-urlencoded" });
      expect(Object.fromEntries(new URLSearchParams(asked[0]!.body))).toEqual({
        grant_type: "password",
        client_id: "tm1",
        username: "analyst",
        password: "an$pw&more",
      });
    });

    it("sends the token on a request of its own, a new one each time", async () => {
      asked.length = 0;
      keycloakAnswer = token;
      const analyst = oauth2Login();
      const request = await analyst.authorizeWithToken({ method: "POST", body: "{}" });
      expect(new Headers(request.headers).get("Authorization")).toBe("Bearer aaa.bbb.ccc");
      expect(request.method).toBe("POST");
      await analyst.authorizeWithToken();
      expect(asked.map((a) => a.path)).toEqual(["/realms/tm1/protocol/openid-connect/token", "/realms/tm1/protocol/openid-connect/token"]);
      // A request used again carries the new token alone, not the old one too
      const again = await analyst.authorizeWithToken({ headers: { authorization: "Bearer old.old.old", Accept: "application/json" } });
      expect(new Headers(again.headers).get("Authorization")).toBe("Bearer aaa.bbb.ccc");
      expect(new Headers(again.headers).get("Accept")).toBe("application/json");
      expect(new Headers((await analyst.authorizeWithToken(again)).headers).get("Authorization")).toBe("Bearer aaa.bbb.ccc");
    });

    it("keeps the session TM1 gives the token's first request", async () => {
      asked.length = 0;
      keycloakAnswer = token;
      answer = { status: 200, headers: { "Set-Cookie": "TM1SessionId=oauth123; Path=/tm1; HttpOnly" }, body: '{"Name":"analyst"}' };
      const analyst = oauth2Login();
      expect(await analyst.cookie()).toBe("TM1SessionId=oauth123");
      expect(await analyst.cookie()).toBe("TM1SessionId=oauth123");
      expect(new Headers((await analyst.authorize()).headers).get("Cookie")).toBe("TM1SessionId=oauth123");
      // One token and one request with it, at the database's REST API: not at TM1's session endpoint
      expect(asked.map((a) => [a.method, a.path, a.authorization])).toEqual([
        ["POST", "/realms/tm1/protocol/openid-connect/token", undefined],
        ["GET", "/tm1/api/v1/Databases('tm1')/ActiveUser?$select=Name", "Bearer aaa.bbb.ccc"],
      ]);
    });

    it("says who refused: Keycloak the password, or TM1 the token", async () => {
      keycloakAnswer = { status: 401, headers: {}, body: '{"error":"invalid_grant"}' };
      const analyst = oauth2Login();
      await expect(analyst.token()).rejects.toThrow(IllegalStateException);
      await expect(analyst.cookie()).rejects.toThrow('Keycloak refused analyst (HTTP 401): {"error":"invalid_grant"}');
      keycloakAnswer = token;
      answer = { status: 401, headers: {}, body: "no such issuer" };
      await expect(analyst.cookie()).rejects.toThrow("TM1 refused the login of 'analyst' to instance 'tm1' (HTTP 401): no such issuer");
      // A refused login isn't kept
      answer = { status: 200, headers: { "Set-Cookie": "TM1SessionId=oauth123" }, body: "{}" };
      expect(await analyst.cookie()).toBe("TM1SessionId=oauth123");
    });

    it("has no session endpoint to ask, and the application's login no token to send", async () => {
      await expect(oauth2Login().session("Bearer aaa.bbb.ccc")).rejects.toThrow(
        "An OAuth 2.0 login has no session to ask for: TM1 takes its token on any request (authorizeWithToken), and cookie() keeps the session that gives",
      );
      const application = login();
      expect(application.isOAuth2()).toBe(false);
      expect(application.getPassword()).toBe("");
      expect(application.getTokenUrl()).toBe(url + "token");
      await expect(application.authorizeWithToken()).rejects.toThrow(IllegalStateException);
      await expect(application.authorizeWithToken()).rejects.toThrow("TM1 takes its own token only where a session is asked for");
    });

    it("never prints the password", () => {
      const analyst = oauth2Login();
      expect(analyst.getPassword()).toBe("an$pw&more");
      expect(String(analyst)).toBe("Tm1Login[analyst in tm1]");
      expect(inspect(analyst, { depth: 6 })).not.toContain("an$pw");
    });
  });

  /** A pass-through login, as an engine with a TM1 server behind it gives one: the server stands in
   * for TM1 (a database's REST API), which the header is sent to. */
  describe("with a pass-through header", () => {
    const passThroughLogin = (): Tm1Login => {
      const root = url.replace("tm1/auth/v1/", "");
      const engine = new (class extends Tm1EngineContainer {
        override getUrl(): string {
          return root;
        }
      })(new Tm1EngineSource.PrivateImage("tm1-engine:test")).withPassThrough(
        new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test")).withCredentials("analyst", "an$pw&more"),
      );
      return engine.database("tm1").passThroughLogin();
    };
    const basic = "Basic " + Buffer.from("analyst:an$pw&more").toString("base64");

    it("keeps the session TM1 gives the header's first request", async () => {
      asked.length = 0;
      answer = { status: 200, headers: { "Set-Cookie": "TM1SessionId=pass123; Path=/tm1; HttpOnly" }, body: '{"Name":"analyst"}' };
      const analyst = passThroughLogin();
      expect(analyst.getAuthorization()).toBe(basic);
      expect(analyst.getAuthUrl()).toBe(url);
      expect(analyst.getTokenUrl()).toBe(url + "token");
      expect(await analyst.cookie()).toBe("TM1SessionId=pass123");
      expect(await analyst.cookie()).toBe("TM1SessionId=pass123");
      expect(new Headers((await analyst.authorize()).headers).get("Cookie")).toBe("TM1SessionId=pass123");
      // One request with the header, at the database's REST API: not at TM1's session endpoint
      expect(asked.map((a) => [a.method, a.path, a.authorization])).toEqual([["GET", "/tm1/api/v1/Databases('tm1')/ActiveUser?$select=Name", basic]]);
    });

    it("says what TM1 answered when the server behind it refuses, and asks again", async () => {
      answer = { status: 401, headers: {}, body: "no such user" };
      const analyst = passThroughLogin();
      await expect(analyst.cookie()).rejects.toThrow(IllegalStateException);
      await expect(analyst.cookie()).rejects.toThrow("TM1 refused the login of 'analyst' to instance 'tm1' (HTTP 401): no such user");
      // A refused login isn't kept
      answer = { status: 200, headers: { "Set-Cookie": "TM1SessionId=pass123" }, body: "{}" };
      expect(await analyst.cookie()).toBe("TM1SessionId=pass123");
    });

    it("has no token and no session endpoint, and asks nothing to say so", async () => {
      asked.length = 0;
      const analyst = passThroughLogin();
      await expect(analyst.token()).rejects.toThrow(
        "A pass-through login has no token: it's the header TM1 passes on (getAuthorization), or the session that gives (authorize, cookie)",
      );
      await expect(analyst.authorizeWithToken()).rejects.toThrow("A pass-through login has no token");
      await expect(analyst.session(basic)).rejects.toThrow(
        "A pass-through login has no session to ask for: TM1 takes its header on any request (getAuthorization), and cookie() keeps the session that gives",
      );
      expect(asked).toEqual([]);
      // The other kinds are no header of their own
      expect(login().isPassThrough()).toBe(false);
      expect(() => login().getAuthorization()).toThrow(
        "Only a pass-through login is a header of its own: this one logs in through the application (getClientId, getClientSecret and getUser)",
      );
    });

    it("never prints the header or the password", () => {
      const analyst = passThroughLogin();
      expect(String(analyst)).toBe("Tm1Login[analyst in tm1]");
      expect(inspect(analyst, { depth: 6 })).not.toContain("an$pw");
      expect(inspect(analyst, { depth: 6 })).not.toContain(basic);
    });
  });
});

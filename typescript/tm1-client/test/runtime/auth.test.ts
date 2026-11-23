// SPDX-License-Identifier: Apache-2.0

import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { inspect } from "node:util";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { CognosPassports, ODataPath, ODataQuery, Tm1AuthenticationException, Tm1Authenticator, Tm1ProtocolException, Tm1Request } from "../../src/index.js";
import { cognosSession } from "../../src/auth/index.js";
import { ScriptedTm1 } from "../support/scripted-tm1.js";

const base64 = (text: string): string => Buffer.from(text, "utf8").toString("base64");

/** The Java client's `Tm1AuthenticatorTest`. */
describe("Tm1Authenticator", () => {
  it("writes Basic credentials", async () => {
    const basic = Tm1Authenticator.basic("admin", "apple");
    expect(await basic.authorization()).toBe("Basic " + base64("admin:apple"));
    expect(await Tm1Authenticator.basic("admin", null).authorization()).toBe("Basic " + base64("admin:"));
    expect(await Tm1Authenticator.basic("admin").authorization()).toBe("Basic " + base64("admin:"));
    expect(basic.describe()).toBe("Basic admin");
  });

  it("writes CAMNamespace credentials", async () => {
    const cam = Tm1Authenticator.camNamespace("alice", "secret", "LDAP");
    expect(await cam.authorization()).toBe("CAMNamespace " + base64("alice:secret:LDAP"));
    expect(cam.describe()).toBe("CAMNamespace LDAP/alice");
  });

  it("asks for a fresh passport on every login", async () => {
    let issued = 0;
    const cam = Tm1Authenticator.camPassport(() => "p" + ++issued);
    expect(await cam.authorization()).toBe("CAMPassport p1");
    expect(await cam.authorization()).toBe("CAMPassport p2");
    expect(cam.describe()).toBe("CAMPassport");
  });

  it("never shows credentials in toString", () => {
    expect(Tm1Authenticator.basic("admin", "apple").toString()).not.toContain("apple");
    expect(Tm1Authenticator.basic("admin", "apple").toString()).not.toContain(base64("admin:apple"));
    expect(Tm1Authenticator.camNamespace("alice", "secret", "LDAP").toString()).not.toContain("secret");
    expect(Tm1Authenticator.camPassport(() => Promise.resolve("passport-value")).toString()).not.toContain("passport-value");
    for (const shown of [
      JSON.stringify(Tm1Authenticator.basic("admin", "apple")),
      inspect(Tm1Authenticator.basic("admin", "apple"), { showHidden: true, depth: 5 }),
    ]) {
      expect(shown).not.toContain(base64("admin:apple"));
    }
    expect(inspect(Tm1Authenticator.camNamespace("alice", "secret", "LDAP"), { showHidden: true })).not.toContain(base64("alice:secret:LDAP"));
  });
});

/** The Java client's `CognosPassportsTest`, against a stand-in for Cognos's session resource: a real
 * HTTP server, so `fetch` reads its `Set-Cookie` as it would Cognos's. */
describe("CognosPassports", () => {
  let cognos: Server;
  let bodies: string[];
  let logons: number;
  let dispatcher: string;

  beforeEach(async () => {
    bodies = [];
    logons = 0;
    cognos = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on("data", (c: Buffer) => chunks.push(c));
      request.on("end", () => {
        const body = Buffer.concat(chunks).toString("utf8");
        bodies.push(`${request.method} ${request.url} ${request.headers["content-type"]} ${body}`);
        if (request.url !== "/api/v1/session") {
          response.writeHead(404).end();
        } else if (body.includes('"wrong"')) {
          response.writeHead(401, { "Content-Type": "application/json" }).end('{"errorCodeString":"camAuthUserRecoverable"}');
        } else {
          response.writeHead(201, { "Set-Cookie": ["XSRF-TOKEN=x; Path=/", `cam_passport=passport-${++logons}; Path=/; HttpOnly`] }).end();
        }
      });
    });
    await new Promise<void>((resolve) => cognos.listen(0, "127.0.0.1", resolve));
    dispatcher = `http://127.0.0.1:${(cognos.address() as AddressInfo).port}`;
  });

  afterEach(async () => {
    cognos.closeAllConnections();
    await new Promise((resolve) => cognos.close(resolve));
  });

  it("logs on for a fresh passport each time", async () => {
    const passports = CognosPassports.password(dispatcher + "/bi/", "LDAP", "viewer", "viewerpw");
    expect(await passports()).toBe("passport-1");
    expect(await passports()).toBe("passport-2");
    expect(bodies).toHaveLength(2);
    for (const body of bodies) {
      expect(body.startsWith("PUT /api/v1/session application/json ")).toBe(true);
      expect(body).toContain('{"name":"CAMNamespace","value":"LDAP"}');
      expect(body).toContain('"CAMUsername"');
      expect(body).toContain('"viewerpw"');
    }
  });

  it("sends an ID token rather than a password", async () => {
    const passports = CognosPassports.idToken(dispatcher, "OIDC", () => Promise.resolve("eyJ.token"));
    expect(await passports()).toBe("passport-1");
    expect(bodies).toHaveLength(1);
    expect(bodies[0]).toContain("CAMOIDCIdToken");
    expect(bodies[0]).toContain("eyJ.token");
    expect(bodies[0]).not.toContain("CAMPassword");
  });

  it("refuses wrong credentials as rejected", async () => {
    const e = await CognosPassports.password(dispatcher, "LDAP", "viewer", "wrong")().catch((x: unknown) => x);
    expect(e).toBeInstanceOf(Tm1AuthenticationException);
    expect((e as Tm1AuthenticationException).kind).toBe("CredentialRejected");
    expect((e as Error).message).not.toContain("wrong");
  });

  it("reports an answer without a passport", async () => {
    const e = await CognosPassports.password(dispatcher + "/nowhere", "LDAP", "viewer", "viewerpw")().catch((x: unknown) => x);
    expect(e).toBeInstanceOf(Tm1ProtocolException);
    expect((e as Error).message).toContain("Cognos answered the logon with 404 and no cam_passport cookie");
  });

  it("describes without the secret", async () => {
    const cam = Tm1Authenticator.cognosLogon(dispatcher, "LDAP", "viewer", "viewerpw");
    expect(cam.describe()).toBe("CAMPassport from Cognos LDAP/viewer");
    expect(cam.toString()).not.toContain("viewerpw");
    expect(JSON.stringify(cam)).not.toContain("viewerpw");
    expect(inspect(cam, { showHidden: true, depth: 5 })).not.toContain("viewerpw");
    expect(await cam.authorization()).toBe("CAMPassport passport-1");
    expect(Tm1Authenticator.cognosIdToken(dispatcher, "OIDC", () => "t").describe()).toBe("CAMPassport from Cognos OIDC (ID token)");
  });

  it("logs in to TM1 with a fresh passport each login", async () => {
    const tm1 = new ScriptedTm1()
      .on("GET Server", () => ScriptedTm1.json(200, "{}"))
      .onEach(
        "POST Dimensions",
        () => ScriptedTm1.status(401, "TM1SessionId=fresh; Path=/api/"),
        () => ScriptedTm1.json(201, "{}"),
      );
    const session = tm1.sessionWithOwnCredentials((b) => b.authenticator(Tm1Authenticator.cognosLogon(dispatcher, "LDAP", "viewer", "viewerpw")));
    await session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none()));
    await session.execute(Tm1Request.post(ODataPath.of("Dimensions"), { Name: "d" }));
    expect(tm1.requests(ScriptedTm1.LOGIN).map((r) => r.authorization)).toEqual(["CAMPassport passport-1", "CAMPassport passport-2"]);
  });

  it("sends with the fetch it's given", async () => {
    const seen: string[] = [];
    const own: typeof fetch = (input, init) => {
      seen.push(`${init?.method} ${String(input)}`);
      return Promise.resolve(new Response(null, { status: 201, headers: { "Set-Cookie": "cam_passport=mine; Path=/" } }));
    };
    expect(await CognosPassports.password("https://cognos.example.com:9300/bi", "LDAP", "u", "p", own)()).toBe("mine");
    expect(seen).toEqual(["PUT https://cognos.example.com:9300/api/v1/session"]);
  });

  it("finds the session resource with or without /bi", () => {
    expect(cognosSession("http://cognos:9300")).toBe("http://cognos:9300/api/v1/session");
    expect(cognosSession("http://cognos:9300/bi/")).toBe("http://cognos:9300/api/v1/session");
    expect(cognosSession("http://cognos:9300/ibmcognos/bi")).toBe("http://cognos:9300/ibmcognos/api/v1/session");
  });

  it("needs its settings", () => {
    expect(() => CognosPassports.password(dispatcher, "LDAP", undefined as unknown as string, "p")).toThrow(TypeError);
    expect(() => CognosPassports.idToken(dispatcher, undefined as unknown as string, () => "t")).toThrow(TypeError);
    expect(() => CognosPassports.idToken(dispatcher, "OIDC", undefined as never)).toThrow(TypeError);
  });
});

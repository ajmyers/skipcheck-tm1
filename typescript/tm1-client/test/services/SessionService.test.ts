// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery, Tm1Client, Tm1Patch, type Tm1HttpResponse } from "../../src/index.js";
import { parseJson, writeJson } from "../../src/json.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** SessionService against a fake TM1 (the .NET client's SessionServiceTests; the Java client tests
 * SessionService only against TM1). */
describe("SessionService", () => {
  it("lists, gets and reads the active session", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ ID: 7, Context: "loader" });
    const client = tm1.client();
    expect((await client.sessions.list(ODataQuery.none().expand("User")).toArray()).map((s) => s.Context)).toEqual(["loader"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Sessions?$expand=User");
    tm1.respond = () => ({ ID: 7 });
    expect((await client.sessions.get(7)).ID).toBe(7);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Sessions(7)");
    await client.sessions.activeSession(ODataQuery.none().select("ID"));
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET ActiveSession?$select=ID");
  });

  it("reads the user and threads, and closes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "admin" });
    const client = tm1.client();
    expect((await client.sessions.user(7)).Name).toBe("admin");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Sessions(7)/User");
    tm1.respond = () => FakeTm1.collection({ ID: 99 });
    expect((await client.sessions.threads(7).toArray()).map((t) => t.ID)).toEqual([99]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Sessions(7)/Threads");
    tm1.respond = () => null;
    await client.sessions.close(7);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Sessions(7)/tm1.Close", "{}"]);
  });

  it("preserves 64-bit session IDs outside Number.MAX_SAFE_INTEGER as bigint", async () => {
    const rawId = "9196504895629575123";
    const id = BigInt(rawId);
    const seen: string[] = [];
    const client = Tm1Client.builder()
      .baseUrl("http://tm1.test/api/v1/")
      .basicAuth("admin", "pw")
      .checkCapabilities(false)
      .transport({
        async send(req): Promise<Tm1HttpResponse> {
          seen.push(`${req.method} ${req.url.replace("http://tm1.test/api/v1/", "")}`);
          if (req.method === "POST" && req.url.endsWith("/tm1.Close")) {
            return { status: 204, headers: {}, body: new Uint8Array() };
          }
          return {
            status: 200,
            headers: { "content-type": "application/json", "set-cookie": "TM1SessionId=s1; Path=/api/v1" },
            body: new TextEncoder().encode(`{"ID":${rawId}}`),
          };
        },
      })
      .build();
    expect(await client.session.sessionId()).toBe(id);
    expect((await client.sessions.activeSession()).ID).toBe(id);
    expect((await client.sessions.get(id)).ID).toBe(id);
    expect(seen.at(-1)).toBe(`GET Sessions(${rawId})`);
    await client.sessions.close(id);
    expect(seen.at(-1)).toBe(`POST Sessions(${rawId})/tm1.Close`);
  });

  it("handles 64-bit integer edge cases in parseJson, writeJson, patches and $batch", async () => {
    expect(parseJson('{"a":9007199254740991,"b":-9007199254740991}')).toEqual({
      a: Number.MAX_SAFE_INTEGER,
      b: Number.MIN_SAFE_INTEGER,
    });
    expect(parseJson('{"pos":9007199254740992,"neg":-9007199254740992}')).toEqual({
      pos: 9007199254740992n,
      neg: -9007199254740992n,
    });
    expect(parseJson('{"f":1e20,"d":9007199254740992.5}')).toEqual({ f: 1e20, d: Number("9007199254740992.5") });
    expect(writeJson({ id: 9196504895629575123n, neg: -9196504895629575123n })).toBe('{"id":9196504895629575123,"neg":-9196504895629575123}');
    class BigIntPatch extends Tm1Patch {}
    const patchA = new BigIntPatch(new Map<string, unknown>([["ID", 9196504895629575123n]]));
    const patchB = new BigIntPatch(new Map<string, unknown>([["ID", 9196504895629575123n]]));
    expect(patchA.equals(patchB)).toBe(true);
    expect(patchA.toString()).toBe('BigIntPatch{"ID":9196504895629575123}');

    const rawId = "9196504895629575123";
    const id = BigInt(rawId);
    const client = Tm1Client.builder()
      .baseUrl("http://tm1.test/api/v1/")
      .basicAuth("admin", "pw")
      .checkCapabilities(false)
      .transport({
        async send(req): Promise<Tm1HttpResponse> {
          if (req.url.endsWith("/$batch")) {
            return {
              status: 200,
              headers: { "content-type": "application/json" },
              body: new TextEncoder().encode(`{"responses":[{"id":"1","status":200,"body":{"ID":${rawId}}}]}`),
            };
          }
          return {
            status: 200,
            headers: { "content-type": "application/json", "set-cookie": "TM1SessionId=s1; Path=/api/v1" },
            body: new TextEncoder().encode(`{"ID":${rawId}}`),
          };
        },
      })
      .build();
    const batch = client.batch();
    const active = batch.client.sessions.activeSession();
    await batch.run();
    expect((await active).ID).toBe(id);
  });
});

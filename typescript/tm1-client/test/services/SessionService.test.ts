// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery } from "../../src/index.js";
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
});

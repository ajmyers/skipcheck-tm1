// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataPath, ODataQuery, UserPatch } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** UserService against a fake TM1: each method sends what the Java client's does, and group
 * membership is only ever set whole, never by the `$ref` delete that deletes the group (the .NET
 * client's UserServiceTests; the Java client tests UserService only against TM1). */
describe("UserService", () => {
  it("lists, gets and reads the active user", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "admin" });
    const client = tm1.client();
    expect((await client.users.list(ODataQuery.none().select("Name")).toArray()).map((u) => u.Name)).toEqual(["admin"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Users?$select=Name");
    tm1.respond = () => ({ Name: "J Smith" });
    expect((await client.users.get("J Smith", ODataQuery.none().expand("Groups"))).Name).toBe("J Smith");
    expect(tm1.last.target).toBe("Users('J%20Smith')?$expand=Groups");
    await client.users.activeUser();
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET ActiveUser");
  });

  it("creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "jsmith" });
    const client = tm1.client();
    const groups = ODataPath.of("Groups");
    const made = await client.users.create(UserPatch.builder().name("jsmith").password("pw").bindGroups(groups.key("Finance"), groups.key("Planners")).build());
    expect(made.Name).toBe("jsmith");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "Users"]);
    expect(tm1.last.text).toBe(`{"Name":"jsmith","Password":"pw","Groups@odata.bind":["Groups('Finance')","Groups('Planners')"]}`);

    tm1.respond = () => null;
    await client.users.update("jsmith", UserPatch.builder().enabled(false).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Users('jsmith')", '{"Enabled":false}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.users.delete("jsmith");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Users('jsmith')"]);
  });

  it("refuses an update with groups, at once", () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    expect(() => client.users.update("jsmith", UserPatch.builder().bindGroups(ODataPath.of("Groups").key("Finance")).build())).toThrow(RangeError);
    expect(() => client.users.update("jsmith", UserPatch.builder().groups([]).build())).toThrow(/setGroups/);
    expect(tm1.requests.filter((r) => r.method === "PATCH")).toEqual([]);
  });

  it("sets groups whole, and never deletes a link", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    await client.users.setGroups("jsmith", ["Finance", "O'Brien Team"]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["PATCH", "Users('jsmith')"]);
    expect(tm1.last.text).toBe(`{"Groups@odata.bind":["Groups('Finance')","Groups('O''Brien%20Team')"]}`);
    await client.users.setGroups("jsmith", []);
    expect(tm1.last.text).toBe('{"Groups@odata.bind":[]}');
    expect(tm1.requests.filter((r) => r.method === "DELETE" || r.target.includes("$ref"))).toEqual([]);
  });

  it("reads groups and sessions, and disconnects", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "Finance" });
    const client = tm1.client();
    expect((await client.users.groups("jsmith").toArray()).map((g) => g.Name)).toEqual(["Finance"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Users('jsmith')/Groups");
    tm1.respond = () => FakeTm1.collection({ ID: 7 });
    expect((await client.users.sessions("jsmith", ODataQuery.none().expand("Threads")).toArray()).map((s) => s.ID)).toEqual([7]);
    expect(tm1.last.target).toBe("Users('jsmith')/Sessions?$expand=Threads");
    tm1.respond = () => null;
    await client.users.disconnect("jsmith");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Users('jsmith')/tm1.Disconnect", "{}"]);
  });
});

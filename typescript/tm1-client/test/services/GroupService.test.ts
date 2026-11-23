// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** GroupService against a fake TM1 (the .NET client's GroupServiceTests; the Java client tests
 * GroupService only against TM1). */
describe("GroupService", () => {
  it("lists, gets, creates, deletes and lists users", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "ADMIN" });
    const client = tm1.client();
    expect((await client.groups.list(ODataQuery.none().top(5)).toArray()).map((g) => g.Name)).toEqual(["ADMIN"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Groups?$top=5");

    tm1.respond = () => ({ Name: "Finance" });
    expect((await client.groups.get("Finance")).Name).toBe("Finance");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Groups('Finance')");
    expect((await client.groups.create("Finance")).Name).toBe("Finance");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Groups", '{"Name":"Finance"}']);

    tm1.respond = () => null;
    await client.groups.delete("Finance");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Groups('Finance')"]);

    tm1.respond = () => FakeTm1.collection({ Name: "jsmith" });
    expect((await client.groups.users("Finance", ODataQuery.none().select("Name")).toArray()).map((u) => u.Name)).toEqual(["jsmith"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Groups('Finance')/Users?$select=Name");
  });
});

// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const MEMBERS = "Dimensions('Region')/Hierarchies('Region')/Members";

/** MemberService against a fake TM1: each method sends what the Java client's does (the .NET
 * client's MemberServiceTests; the Java client tests it only against TM1). */
describe("MemberService", () => {
  it("lists and gets", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "West", UniqueName: "[Region].[Region].[West]" });
    const members = await client.members.list("Region", "Region", ODataQuery.none().top(1)).toArray();
    expect(members.map((m) => m.UniqueName)).toEqual(["[Region].[Region].[West]"]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", MEMBERS + "?$top=1"]);
    tm1.respond = () => ({ Name: "West" });
    expect((await client.members.get("Region", "Region", "West", ODataQuery.none().select("Name"))).Name).toBe("West");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", MEMBERS + "('West')?$select=Name"]);
  });

  it("navigates", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ Name: "Total" });
    expect((await client.members.parent("Region", "Region", "West"))?.Name).toBe("Total");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", MEMBERS + "('West')/Parent"]);
    await client.members.element("Region", "Region", "West");
    expect(tm1.last.target).toBe(MEMBERS + "('West')/Element");
    await client.members.level("Region", "Region", "West");
    expect(tm1.last.target).toBe(MEMBERS + "('West')/Level");
    await client.members.hierarchy("Region", "Region", "West");
    expect(tm1.last.target).toBe(MEMBERS + "('West')/Hierarchy");

    tm1.respond = () => FakeTm1.collection({ Name: "West" });
    const children = await client.members.children("Region", "Region", "Total", ODataQuery.none().select("Name")).toArray();
    expect(children.map((m) => m.Name)).toEqual(["West"]);
    expect(tm1.last.target).toBe(MEMBERS + "('Total')/Children?$select=Name");

    // A root has no parent: TM1 sends no content
    tm1.respond = () => null;
    expect(await client.members.parent("Region", "Region", "Total")).toBeUndefined();
  });
});

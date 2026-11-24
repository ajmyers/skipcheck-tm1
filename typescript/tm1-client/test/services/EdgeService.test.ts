// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { EdgeCreate, EdgePatch, ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const EDGES = "Dimensions('Region')/Hierarchies('Region')/Edges";
const EDGE = EDGES + "(ParentName='Total',ComponentName='O''Brien')";

/** EdgeService against a fake TM1: each method sends what the Java client's does (the .NET client's
 * EdgeServiceTests; the Java client tests it only against TM1). */
describe("EdgeService", () => {
  it("lists, gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ ParentName: "Total", ComponentName: "West", Weight: 1.0 });
    const edges = await client.edges.list("Region", "Region", ODataQuery.none().top(1)).toArray();
    expect(edges.map((e) => e.Weight)).toEqual([1.0]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", EDGES + "?$top=1"]);

    tm1.respond = () => ({ ParentName: "Total", ComponentName: "O'Brien", Weight: 2.0 });
    expect((await client.edges.get("Region", "Region", "Total", "O'Brien")).Weight).toBe(2.0);
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", EDGE]);
    await client.edges.create("Region", "Region", EdgeCreate.builder().parentName("Total").componentName("West").weight(1.0).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", EDGES, '{"ParentName":"Total","ComponentName":"West","Weight":1}']);

    tm1.respond = () => null;
    await client.edges.update("Region", "Region", "Total", "O'Brien", EdgePatch.builder().weight(-1.0).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", EDGE, '{"Weight":-1}']);
    await client.edges.delete("Region", "Region", "Total", "O'Brien");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", EDGE]);
  });

  it("navigates", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ Name: "Total" });
    expect((await client.edges.parent("Region", "Region", "Total", "O'Brien", ODataQuery.none().select("Name"))).Name).toBe("Total");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", EDGE + "/Parent?$select=Name"]);
    await client.edges.component("Region", "Region", "Total", "O'Brien");
    expect(tm1.last.target).toBe(EDGE + "/Component");
    await client.edges.hierarchy("Region", "Region", "Total", "O'Brien");
    expect(tm1.last.target).toBe(EDGE + "/Hierarchy");
  });
});

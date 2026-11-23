// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { AttributesPatch, ElementPatch, ElementType, ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const ELEMENTS = "Dimensions('Region')/Hierarchies('Region')/Elements";

/** ElementService against a fake TM1: each method sends what the Java client's does, and reads
 * TM1's answer the same way (the .NET client's ElementServiceTests; the Java client tests the
 * service only against TM1). */
describe("ElementService", () => {
  it("lists, gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "West", Type: "Numeric" });
    const elements = await client.elements.list("Region", "Region", ODataQuery.none().select("Name", "Type")).toArray();
    expect(elements.map((e) => [e.Name, e.Type])).toEqual([["West", ElementType.Numeric]]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${ELEMENTS}?$select=Name,Type`);

    tm1.respond = () => ({ Name: "West" });
    expect((await client.elements.get("Region", "Region", "West")).Name).toBe("West");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", `${ELEMENTS}('West')`]);
    expect((await client.elements.create("Region", "Region", ElementPatch.builder().name("West").type(ElementType.Numeric).build())).Name).toBe("West");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", ELEMENTS, '{"Name":"West","Type":"Numeric"}']);

    tm1.respond = () => null;
    await client.elements.update("Region", "Region", "West", ElementPatch.builder().type(ElementType.String).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", `${ELEMENTS}('West')`, '{"Type":"String"}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.elements.update("Region", "Region", "West", ElementPatch.builder().type(ElementType.Numeric).build(), 'W/"3"');
    expect(tm1.last.headers["if-match"]).toBe('W/"3"');
    await client.elements.delete("Region", "Region", "West");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", `${ELEMENTS}('West')`]);
  });

  it("refuses an update with a name or attributes", () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const rename = ElementPatch.builder().name("East").build();
    expect(() => client.elements.update("Region", "Region", "West", rename)).toThrow(/can't be renamed/);
    expect(() => client.elements.update("Region", "Region", "West", rename, 'W/"1"')).toThrow(RangeError);
    const attributes = ElementPatch.builder().attributes(AttributesPatch.builder().caption("W").build()).build();
    expect(() => client.elements.update("Region", "Region", "West", attributes, 'W/"1"')).toThrow(/\}ElementAttributes_/);
    expect(() => client.elements.update("Region", "Region", "West", attributes)).toThrow(RangeError);
    expect(tm1.requests).toEqual([]);
  });

  it("navigates", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "Total" });
    expect((await client.elements.parents("Region", "Region", "West", ODataQuery.none().select("Name")).toArray()).map((e) => e.Name)).toEqual(["Total"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${ELEMENTS}('West')/Parents?$select=Name`);
    await client.elements.components("Region", "Region", "Total").toArray();
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${ELEMENTS}('Total')/Components`);
    tm1.respond = () => FakeTm1.collection({ ParentName: "Total", ComponentName: "West", Weight: 1.0 });
    const edges = await client.elements.edges("Region", "Region", "Total").toArray();
    expect(edges.map((e) => [e.ParentName, e.ComponentName, e.Weight])).toEqual([["Total", "West", 1.0]]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${ELEMENTS}('Total')/Edges`);
    tm1.respond = () => ({ Name: "Region" });
    expect((await client.elements.hierarchy("Region", "Region", "West")).Name).toBe("Region");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${ELEMENTS}('West')/Hierarchy`);
  });

  it("sets a component", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    await client.elements.setComponent("Region", "Region", "Total", "West");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", `${ELEMENTS}('Total')/tm1.SetComponent`]);
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${ELEMENTS}('West')"}`);
    await client.elements.setComponent("Region", "Region", "Total", "West", 2.0);
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${ELEMENTS}('West')","Weight":2}`);
    await client.elements.setComponent("Region", "Region", "Total", "West", "East", -1.0);
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${ELEMENTS}('West')","Before@odata.bind":"${ELEMENTS}('East')","Weight":-1}`);
  });

  it("runs the element's operations", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    for (const [call, op] of [
      [() => client.elements.lock("Region", "Region", "West"), "Lock"],
      [() => client.elements.unlock("Region", "Region", "West"), "Unlock"],
      [() => client.elements.reserve("Region", "Region", "West"), "Reserve"],
      [() => client.elements.release("Region", "Region", "West"), "Release"],
    ] as const) {
      await call();
      expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `${ELEMENTS}('West')/tm1.${op}`, "{}"]);
    }
  });
});

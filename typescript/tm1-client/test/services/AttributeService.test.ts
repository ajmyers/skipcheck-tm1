// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { AttributeDefinitionCreate, AttributeSet, AttributeType, ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** AttributeService and AttributeSet against a fake TM1: each sends what the Java client's does
 * (the .NET client's AttributeServiceTests; the Java client tests them only against TM1). */
describe("AttributeService", () => {
  it.each([
    [AttributeSet.dimensions(), "DimensionAttributes"],
    [AttributeSet.hierarchies("Region"), "Dimensions('Region')/HierarchyAttributes"],
    [AttributeSet.elements("Region", "Leaves"), "Dimensions('Region')/Hierarchies('Leaves')/ElementAttributes"],
    [AttributeSet.subsets("Region", "Region"), "Dimensions('Region')/Hierarchies('Region')/SubsetAttributes"],
    [AttributeSet.cubes(), "CubeAttributes"],
    [AttributeSet.views("Sales"), "Cubes('Sales')/ViewAttributes"],
    [AttributeSet.processes(), "ProcessAttributes"],
    [AttributeSet.chores(), "ChoreAttributes"],
  ] as const)("lists, gets, creates and deletes in %s", async (set, path) => {
    expect(set.toString()).toBe(path);
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "Manager", Type: "String" });
    const attributes = await client.attributes.list(set, ODataQuery.none().select("Name", "Type")).toArray();
    expect(attributes.map((a) => [a.Name, a.Type])).toEqual([["Manager", AttributeType.String]]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${path}?$select=Name,Type`);

    tm1.respond = () => ({ Name: "Manager", Type: "String" });
    expect((await client.attributes.get(set, "Manager")).Name).toBe("Manager");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", `${path}('Manager')`]);
    const made = await client.attributes.create(set, AttributeDefinitionCreate.builder().name("Manager").type(AttributeType.String).build());
    expect(made.Type).toBe("String");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", path, '{"Name":"Manager","Type":"String"}']);

    tm1.respond = () => null;
    await client.attributes.delete(set, "Manager");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", `${path}('Manager')`]);
  });
});

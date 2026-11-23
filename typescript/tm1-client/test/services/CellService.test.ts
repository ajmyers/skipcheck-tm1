// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { CubeUpdatePatch, ODataPath, Tuples, UpdateOrder } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const NORTH = "Dimensions('Region')/Hierarchies('Region')/Elements('North')";

/** CellService against a fake TM1: writes by tuple, one or many, as the Java client's CellService
 * sends them (the .NET client's CellServiceTests). */
describe("CellService", () => {
  it("writes a cell by its tuple", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    await tm1.client().cells.update(
      "Sales",
      new Map([
        ["Region", "North"],
        ["Month", "Jan"],
      ]),
      42,
    );
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "Cubes('Sales')/tm1.Update"]);
    expect(tm1.last.text).toBe(`{"Cells":[{"Tuple@odata.bind":["${NORTH}","Dimensions('Month')/Hierarchies('Month')/Elements('Jan')"]}],"Value":42}`);
  });

  it("clears a cell with null", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    await tm1.client().cells.update("Sales", { Region: "North" }, null);
    expect(tm1.last.text).toBe(`{"Cells":[{"Tuple@odata.bind":["${NORTH}"]}],"Value":null}`);
  });

  it("writes many cells, in an order", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    const updates = [
      CubeUpdatePatch.builder()
        .bindTuple(...Tuples.of({ Region: "North" }))
        .value(1)
        .build(),
      CubeUpdatePatch.builder()
        .bindTuple(Tuples.element("Region", "Region", "South"))
        .value("x")
        .build(),
    ];
    const updatesJson = `[{"Tuple@odata.bind":["${NORTH}"],"Value":1},{"Tuple@odata.bind":["Dimensions('Region')/Hierarchies('Region')/Elements('South')"],"Value":"x"}]`;
    await client.cells.updateCells("Sales", updates);
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "Cubes('Sales')/tm1.UpdateCells"]);
    expect(tm1.last.text).toBe(`{"Updates":${updatesJson}}`);

    await client.cells.updateCells("Sales", updates, UpdateOrder.ByLevel);
    expect([tm1.last.target, tm1.last.text]).toEqual(["Cubes('Sales')/tm1.UpdateCells", `{"Updates":${updatesJson},"Order":"ByLevel"}`]);

    const reference = CubeUpdatePatch.builder()
      .bindTuple(Tuples.element("Region", "Region", "South"))
      .bindReferenceCube(ODataPath.of("Cubes").key("Sales"))
      .bindReferenceCell(...Tuples.of({ Region: "North" }))
      .value(5)
      .build();
    await client.cells.updateCells("Sales", [reference]);
    expect(tm1.last.json).toEqual({
      Updates: [
        {
          "Tuple@odata.bind": ["Dimensions('Region')/Hierarchies('Region')/Elements('South')"],
          "ReferenceCube@odata.bind": "Cubes('Sales')",
          "ReferenceCell@odata.bind": [NORTH],
          Value: 5,
        },
      ],
    });
    expect(() => client.cells.updateCells("Sales", updates, UpdateOrder.Unknown)).toThrow(TypeError);
  });
});

/** Tuples: a cell's element paths, as the Java client's `Tuples` builds them (the .NET client's
 * TuplesTests). */
describe("Tuples", () => {
  it("places each element in its dimension's own hierarchy, in order", () => {
    const tuple = Tuples.of(
      new Map([
        ["Region", "North"],
        ["Month", "Jan's"],
      ]),
    );
    expect(tuple.map((p) => p.toString())).toEqual([NORTH, "Dimensions('Month')/Hierarchies('Month')/Elements('Jan''s')"]);
  });

  it("places an element in any hierarchy", () => {
    expect(Tuples.element("Region", "By Manager", "North").toString()).toBe("Dimensions('Region')/Hierarchies('By%20Manager')/Elements('North')");
    expect(() => Tuples.element("Region", null as unknown as string, "North")).toThrow(TypeError);
    expect(() => Tuples.of(null as unknown as Map<string, string>)).toThrow(TypeError);
  });
});

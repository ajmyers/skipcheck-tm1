// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { Cube, CubeCreate, CubePatch, Filter, ODataPath, ODataQuery, Tm1PreconditionFailedException } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** CubeService against a fake TM1: each method sends what the Java client's does, and reads TM1's
 * answer the same way (the .NET client's CubeServiceTests). */
describe("CubeService", () => {
  it("lists with a query", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "Sales" });
    const client = tm1.client();
    const query = ODataQuery.none().select(Cube.Fields.Name).filter(Filter.startsWith(Cube.Fields.Name, "Sales")).orderBy(Cube.Fields.Name);
    const cubes = await client.cubes.modelCubes(query).toArray();
    expect(cubes.map((c) => c.Name)).toEqual(["Sales"]);
    expect(tm1.last.target).toBe("ModelCubes()?$select=Name&$filter=startswith(Name,'Sales')&$orderby=Name");
    await client.cubes.list(ODataQuery.none().top(2)).toArray();
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cubes?$top=2");
    await client.cubes.controlCubes().toArray();
    expect(tm1.last.target).toBe("ControlCubes()");
  });

  it("gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "Sales", "@odata.etag": 'W/"1"' });
    const client = tm1.client();
    const cube = await client.cubes.get("Sales", ODataQuery.none().select("Name"));
    expect([cube.Name, cube["@odata.etag"]]).toEqual(["Sales", 'W/"1"']);
    expect(tm1.last.target).toBe("Cubes('Sales')?$select=Name");

    const dimensions = ODataPath.of("Dimensions");
    await client.cubes.create(CubeCreate.builder().name("Sales").bindDimensions(dimensions.key("Region"), dimensions.key("Month")).build());
    expect(tm1.last.method).toBe("POST");
    expect(tm1.last.text).toBe('{"Name":"Sales","Dimensions@odata.bind":["Dimensions(\'Region\')","Dimensions(\'Month\')"]}');

    tm1.respond = () => null;
    await client.cubes.update("Sales", CubePatch.builder().rules(null).build());
    expect([tm1.last.method, tm1.last.text]).toEqual(["PATCH", '{"Rules":null}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.cubes.update("Sales", CubePatch.builder().rules("SKIPCHECK;").build(), 'W/"1"');
    expect(tm1.last.headers["if-match"]).toBe('W/"1"');
    await client.cubes.delete("Sales");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Cubes('Sales')"]);
  });

  it("refuses a stale ETag", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(412, { error: { code: "278", message: "stale" } });
    const e = await tm1
      .client()
      .cubes.update("Sales", CubePatch.builder().rules("x").build(), 'W/"0"')
      .catch((x: unknown) => x);
    expect(e).toBeInstanceOf(Tm1PreconditionFailedException);
    expect((e as Tm1PreconditionFailedException).kind).toBe("PreconditionFailed");
    expect((e as Tm1PreconditionFailedException).error?.code).toBe("278");
  });

  it("runs the cube's operations", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => null;
    for (const [call, op] of [
      [() => client.cubes.load("Sales"), "Load"],
      [() => client.cubes.unload("Sales"), "Unload"],
      [() => client.cubes.lock("Sales"), "Lock"],
      [() => client.cubes.unlock("Sales"), "Unlock"],
      [() => client.cubes.reserve("Sales"), "Reserve"],
      [() => client.cubes.release("Sales"), "Release"],
    ] as const) {
      await call();
      expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `Cubes('Sales')/tm1.${op}`, "{}"]);
    }
    tm1.respond = () => ({ "@odata.context": "$metadata#Edm.Double", value: 12.5 });
    expect(await client.cubes.reorderDimensions("Sales", ["Month", "Region"])).toBe(12.5);
    expect(tm1.last.text).toBe('{"Dimensions@odata.bind":["Dimensions(\'Month\')","Dimensions(\'Region\')"]}');

    tm1.respond = () => FakeTm1.collection({ LineNumber: 2, Message: "Syntax error" });
    const errors = await client.cubes.checkRules("Sales", "SKIPCHECK").toArray();
    expect([tm1.last.target, tm1.last.text]).toEqual(["Cubes('Sales')/tm1.CheckRules", '{"Rules":"SKIPCHECK"}']);
    expect(errors[0]?.LineNumber).toBe(2);
    await client.cubes.checkRules("Sales").toArray();
    expect(tm1.last.text).toBe("{}");

    tm1.respond = () => FakeTm1.collection({ Name: "Month" });
    await client.cubes.dimensions("Sales").toArray();
    expect(tm1.last.target).toBe("Cubes('Sales')/Dimensions");
    await client.cubes.dimensionsStorageOrder("Sales").toArray();
    expect(tm1.last.target).toBe("Cubes('Sales')/tm1.DimensionsStorageOrder()");
  });

  it("traces by cell", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const cell = new Map([
      ["Region", "North"],
      ["Month", "Jan"],
    ]);
    const elements = `["Dimensions('Region')/Hierarchies('Region')/Elements('North')","Dimensions('Month')/Hierarchies('Month')/Elements('Jan')"]`;
    tm1.respond = () => ({ Type: "Rule" });
    await client.cubes.traceCellCalculation("Sales", cell, ODataQuery.none().expand("Components"));
    expect(tm1.last.target).toBe("Cubes('Sales')/tm1.TraceCellCalculation?$expand=Components");
    expect(tm1.last.text).toBe(`{"Tuple@odata.bind":${elements}}`);
    await client.cubes.traceFeeders("Sales", { Region: "North", Month: "Jan" });
    expect(tm1.last.target).toBe("Cubes('Sales')/tm1.TraceFeeders");
    tm1.respond = () => FakeTm1.collection();
    await client.cubes.checkFeeders("Sales", cell, "Unfed").toArray();
    expect(tm1.last.text).toBe(`{"Tuple@odata.bind":${elements},"Selection":"Unfed"}`);
  });
});

import { describe, expect, it } from "vitest";
import { DimensionPatch, ODataQuery, Tm1PreconditionFailedException } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** DimensionService against a fake TM1: each method sends what the Java client's does, and reads
 * TM1's answer the same way (the .NET client's DimensionServiceTests; the Java client tests the
 * service only against TM1). */
describe("DimensionService", () => {
  it("lists", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "Region" });
    const client = tm1.client();
    const dimensions = await client.dimensions.list(ODataQuery.none().top(1)).toArray();
    expect(dimensions.map((d) => d.Name)).toEqual(["Region"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Dimensions?$top=1");
    await client.dimensions.modelDimensions(ODataQuery.none().select("Name")).toArray();
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET ModelDimensions()?$select=Name");
    await client.dimensions.controlDimensions().toArray();
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET ControlDimensions()");
  });

  it("gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "Region", "@odata.etag": 'W/"1"' });
    const client = tm1.client();
    const dimension = await client.dimensions.get("Region", ODataQuery.none().select("Name"));
    expect([dimension.Name, dimension["@odata.etag"]]).toEqual(["Region", 'W/"1"']);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Dimensions('Region')?$select=Name");

    expect((await client.dimensions.create(DimensionPatch.builder().name("Region").build())).Name).toBe("Region");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Dimensions", '{"Name":"Region"}']);

    tm1.respond = () => null;
    await client.dimensions.update("Region", DimensionPatch.builder().name("Region").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Dimensions('Region')", '{"Name":"Region"}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.dimensions.update("Region", DimensionPatch.builder().name("Region").build(), 'W/"1"');
    expect(tm1.last.headers["if-match"]).toBe('W/"1"');
    await client.dimensions.delete("O'Brien");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Dimensions('O''Brien')"]);
  });

  it("refuses a stale ETag", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(412, { error: { code: "278", message: "stale" } });
    await expect(tm1.client().dimensions.update("Region", DimensionPatch.builder().name("Region").build(), 'W/"0"')).rejects.toBeInstanceOf(
      Tm1PreconditionFailedException,
    );
  });

  it("runs the dimension's operations", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ Name: "Copy" });
    expect((await client.dimensions.saveAs("Region", "Copy")).Name).toBe("Copy");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Dimensions('Region')/tm1.SaveAs", '{"Name":"Copy"}']);
    await client.dimensions.saveAs("Region", "Copy", true, false);
    expect(tm1.last.text).toBe('{"Name":"Copy","Overwrite":true,"KeepExistingAttributes":false}');

    tm1.respond = () => ({ Name: "Leaves" });
    expect((await client.dimensions.addAllLeavesHierarchy("Region")).Name).toBe("Leaves");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Dimensions('Region')/tm1.AddAllLeavesHierarchy", "{}"]);
    await client.dimensions.addAllLeavesHierarchy("Region", "Leaves", true);
    expect(tm1.last.text).toBe('{"AllLeavesHierarchyName":"Leaves","Visible":true}');

    tm1.respond = () => null;
    for (const [call, op] of [
      [() => client.dimensions.lock("Region"), "Lock"],
      [() => client.dimensions.unlock("Region"), "Unlock"],
      [() => client.dimensions.reserve("Region"), "Reserve"],
      [() => client.dimensions.release("Region"), "Release"],
    ] as const) {
      await call();
      expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `Dimensions('Region')/tm1.${op}`, "{}"]);
    }
  });
});

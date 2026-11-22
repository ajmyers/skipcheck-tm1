import { describe, expect, it } from "vitest";
import { ElementPatch, ODataPath, ODataQuery, SubsetPatch, SubsetScope } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const HIERARCHY = "Dimensions('Region')/Hierarchies('Region')";

/** SubsetService against a fake TM1: each method sends what the Java client's does (the .NET
 * client's SubsetServiceTests; the Java client tests it only against TM1). */
describe("SubsetService", () => {
  it("names each scope's collection", () => {
    expect([SubsetScope.Public, SubsetScope.Private, SubsetScope.Session].map((s) => SubsetScope.navigation(s))).toEqual(["Subsets", "PrivateSubsets", "SessionSubsets"]);
    expect(() => SubsetScope.navigation("Shared" as SubsetScope)).toThrow(RangeError);
  });

  it.each([
    [SubsetScope.Public, "Subsets"],
    [SubsetScope.Private, "PrivateSubsets"],
    [SubsetScope.Session, "SessionSubsets"],
  ] as const)("lists, gets, creates, updates and deletes in scope %s", async (scope, navigation) => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const subsets = `${HIERARCHY}/${navigation}`;
    tm1.respond = () => FakeTm1.collection({ Name: "West Coast" });
    const listed = await client.subsets.list("Region", "Region", scope, ODataQuery.none().select("Name")).toArray();
    expect(listed.map((s) => s.Name)).toEqual(["West Coast"]);
    expect(tm1.last.target).toBe(subsets + "?$select=Name");

    tm1.respond = () => ({ Name: "West Coast" });
    await client.subsets.get("Region", "Region", scope, "West Coast");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", subsets + "('West%20Coast')"]);
    await client.subsets.create("Region", "Region", scope, SubsetPatch.builder().name("Leaves").expression("{TM1SUBSETALL([Region])}").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", subsets, '{"Name":"Leaves","Expression":"{TM1SUBSETALL([Region])}"}']);

    tm1.respond = () => null;
    await client.subsets.update("Region", "Region", scope, "Leaves", SubsetPatch.builder().expression("{}").build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", subsets + "('Leaves')", '{"Expression":"{}"}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.subsets.update("Region", "Region", scope, "Leaves", SubsetPatch.builder().expression("{}").build(), 'W/"4"');
    expect(tm1.last.headers["if-match"]).toBe('W/"4"');
    await client.subsets.delete("Region", "Region", scope, "Leaves");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", subsets + "('Leaves')"]);
  });

  it("refuses an update with elements, sending nothing", () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const set = SubsetPatch.builder().elements([ElementPatch.builder().name("West").build()]).build();
    const bound = SubsetPatch.builder().bindElements(ODataPath.of("x")).build();
    expect(() => client.subsets.update("Region", "Region", SubsetScope.Public, "s", set)).toThrow(RangeError);
    expect(() => client.subsets.update("Region", "Region", SubsetScope.Public, "s", set)).toThrow(/addElements/);
    expect(() => client.subsets.update("Region", "Region", SubsetScope.Public, "s", bound, 'W/"1"')).toThrow(RangeError);
    expect(tm1.requests).toEqual([]);
  });

  it("adds, clears and lists elements", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => null;
    await client.subsets.addElements("Region", "Region", SubsetScope.Public, "s", ["West", "East"]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["PATCH", HIERARCHY + "/Subsets('s')"]);
    expect(tm1.last.text).toBe(`{"Elements@odata.bind":["${HIERARCHY}/Elements('West')","${HIERARCHY}/Elements('East')"]}`);
    await client.subsets.clearElements("Region", "Region", SubsetScope.Private, "s");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", HIERARCHY + "/PrivateSubsets('s')/Elements/$ref"]);

    tm1.respond = () => FakeTm1.collection({ Name: "West" });
    const elements = await client.subsets.elements("Region", "Region", SubsetScope.Public, "s", ODataQuery.none().select("Name")).toArray();
    expect(elements.map((e) => e.Name)).toEqual(["West"]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", HIERARCHY + "/Subsets('s')/Elements?$select=Name"]);
    tm1.respond = () => ({ Name: "Region" });
    expect((await client.subsets.hierarchy("Region", "Region", SubsetScope.Session, "s")).Name).toBe("Region");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", HIERARCHY + "/SessionSubsets('s')/Hierarchy"]);
  });

  it("saves as", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "Copy" });
    const client = tm1.client();
    expect((await client.subsets.saveAs("Region", "Region", SubsetScope.Public, "s", "Copy")).Name).toBe("Copy");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", HIERARCHY + "/Subsets('s')/tm1.SaveAs", '{"Name":"Copy"}']);
    await client.subsets.saveAs("Region", "Region", SubsetScope.Public, "s", "Copy", true, false);
    expect(tm1.last.text).toBe('{"Name":"Copy","MakePrivate":true,"MakeStatic":false}');
  });

  it("sets elements", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    await client.subsets.setElement("Region", "Region", SubsetScope.Public, "s", "West");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", HIERARCHY + "/Subsets('s')/tm1.SetElement"]);
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${HIERARCHY}/Elements('West')"}`);
    await client.subsets.setElementBefore("Region", "Region", SubsetScope.Public, "s", "West", "East");
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${HIERARCHY}/Elements('West')","Before@odata.bind":"${HIERARCHY}/Elements('East')"}`);
    await client.subsets.setElementAfter("Region", "Region", SubsetScope.Public, "s", "West", "East");
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${HIERARCHY}/Elements('West')","After@odata.bind":"${HIERARCHY}/Elements('East')"}`);
  });

  it("creates session subsets", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "$session" });
    const client = tm1.client();
    expect((await client.subsets.createSessionSubset("Region", "Region", SubsetPatch.builder().expression("{[Region].[West]}").build())).Name).toBe("$session");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", HIERARCHY + "/tm1.CreateSessionSubset"]);
    expect(tm1.last.text).toBe('{"Subset":{"Expression":"{[Region].[West]}"}}');
    await client.subsets.createSessionSubsetFrom("Region", "Region", SubsetScope.Private, "Mine");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", HIERARCHY + "/tm1.CreateSessionSubset"]);
    expect(tm1.last.text).toBe(`{"Subset@odata.bind":"${HIERARCHY}/PrivateSubsets('Mine')"}`);
  });
});

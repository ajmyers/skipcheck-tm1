import { describe, expect, it } from "vitest";
import { EdgePatch, ElementPatch, ElementType, HierarchyPatch, ODataPath, ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const HIERARCHY = "Dimensions('Region')/Hierarchies('Region')";

/** HierarchyService against a fake TM1: each method sends what the Java client's does, and reads
 * TM1's answer the same way (the .NET client's HierarchyServiceTests; the Java client tests the
 * service only against TM1). */
describe("HierarchyService", () => {
  it("lists, gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "Region" }, { Name: "Leaves" });
    expect((await client.hierarchies.list("Region", ODataQuery.none().select("Name")).toArray()).map((h) => h.Name)).toEqual(["Region", "Leaves"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Dimensions('Region')/Hierarchies?$select=Name");

    tm1.respond = () => ({ Name: "Leaves" });
    expect((await client.hierarchies.get("Region", "Leaves")).Name).toBe("Leaves");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", "Dimensions('Region')/Hierarchies('Leaves')"]);
    expect((await client.hierarchies.create("Region", HierarchyPatch.builder().name("Leaves").build())).Name).toBe("Leaves");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Dimensions('Region')/Hierarchies", '{"Name":"Leaves"}']);

    tm1.respond = () => null;
    await client.hierarchies.update("Region", "Leaves", HierarchyPatch.builder().visible(false).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Dimensions('Region')/Hierarchies('Leaves')", '{"Visible":false}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.hierarchies.update("Region", "Leaves", HierarchyPatch.builder().visible(true).build(), 'W/"2"');
    expect(tm1.last.headers["if-match"]).toBe('W/"2"');
    await client.hierarchies.delete("Region", "Leaves");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Dimensions('Region')/Hierarchies('Leaves')"]);
  });

  it("refuses an update with structure", () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const withElements = HierarchyPatch.builder()
      .elements([ElementPatch.builder().name("x").build()])
      .build();
    const withBinding = HierarchyPatch.builder().bindElements(ODataPath.of("Dimensions").key("d")).build();
    const withEdges = HierarchyPatch.builder().edges([]).build();
    for (const patch of [withElements, withBinding, withEdges]) {
      expect(() => client.hierarchies.update("Region", "Region", patch)).toThrow(/replaceStructure/);
      expect(() => client.hierarchies.update("Region", "Region", patch)).toThrow(RangeError);
      expect(() => client.hierarchies.update("Region", "Region", patch, 'W/"1"')).toThrow(RangeError);
    }
    expect(tm1.requests).toEqual([]);
  });

  it("replaces the structure", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    await tm1
      .client()
      .hierarchies.replaceStructure(
        "Region",
        "Region",
        [ElementPatch.builder().name("All").type(ElementType.Consolidated).build()],
        [EdgePatch.builder().parentName("All").componentName("North").weight(1).build()],
      );
    expect([tm1.last.method, tm1.last.target]).toEqual(["PATCH", HIERARCHY]);
    expect(tm1.last.json).toEqual({ Elements: [{ Name: "All", Type: "Consolidated" }], Edges: [{ ParentName: "All", ComponentName: "North", Weight: 1 }] });
  });

  it("navigates", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ Name: "Region" });
    expect((await client.hierarchies.defaultHierarchy("Region", ODataQuery.none().select("Name"))).Name).toBe("Region");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Dimensions('Region')/DefaultHierarchy?$select=Name");
    expect((await client.hierarchies.dimension("Region", "Leaves")).Name).toBe("Region");
    expect(tm1.last.target).toBe("Dimensions('Region')/Hierarchies('Leaves')/Dimension");
    expect((await client.hierarchies.allMember("Region", "Region"))?.Name).toBe("Region");
    expect(tm1.last.target).toBe(`${HIERARCHY}/AllMember`);
    expect((await client.hierarchies.defaultMember("Region", "Region"))?.Name).toBe("Region");
    expect(tm1.last.target).toBe(`${HIERARCHY}/DefaultMember`);

    // Several roots: TM1 sends no content
    tm1.respond = () => null;
    expect(await client.hierarchies.allMember("Region", "Region")).toBeNull();
    expect(await client.hierarchies.defaultMember("Region", "Region")).toBeNull();

    tm1.respond = () => FakeTm1.collection({ Name: "Total" });
    expect((await client.hierarchies.rootMembers("Region", "Region", ODataQuery.none().top(5)).toArray()).map((m) => m.Name)).toEqual(["Total"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${HIERARCHY}/tm1.RootMembers()?$top=5`);
  });

  it("saves as", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Name: "Copy" });
    const client = tm1.client();
    expect((await client.hierarchies.saveAs("Region", "Region", "Copy")).Name).toBe("Copy");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `${HIERARCHY}/tm1.SaveAs`, '{"Name":"Copy"}']);
    await client.hierarchies.saveAs("Region", "Region", "Copy", true, true);
    expect(tm1.last.text).toBe('{"Name":"Copy","Overwrite":true,"KeepExistingAttributes":true}');
  });

  it("sets an element", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    await client.hierarchies.setElement("Region", "Region", "West");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual([
      "POST",
      `${HIERARCHY}/tm1.SetElement`,
      `{"Element@odata.bind":"${HIERARCHY}/Elements('West')"}`,
    ]);
    await client.hierarchies.setElement("Region", "Region", "West", "East");
    expect(tm1.last.text).toBe(`{"Element@odata.bind":"${HIERARCHY}/Elements('West')","Before@odata.bind":"${HIERARCHY}/Elements('East')"}`);
    expect(tm1.requests.filter((r) => r.target.endsWith("tm1.SetElement")).length).toBe(2);
  });
});

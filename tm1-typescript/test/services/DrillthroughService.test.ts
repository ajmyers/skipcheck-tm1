import { describe, expect, it } from "vitest";
import { Drillthrough, ODataQuery, ProcessParameterPatch, typeOf } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const SCRIPT = "Cellsets('c1')/Cells(4)/DrillthroughScripts('}Drill_Sales')";

const decoded = (tm1: FakeTm1): string => decodeURIComponent(tm1.last.target);

/** DrillthroughService against a fake TM1: a cell's drillthroughs, drillthrough processes run
 * directly, and rowsets, as the Java client's DrillthroughService sends them (the .NET client's
 * DrillthroughServiceTests). */
describe("DrillthroughService", () => {
  it("lists a cell's drillthroughs", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ "@odata.type": "#ibm.tm1.api.v1.CubeDrillthrough", Name: "}Drill_Sales" });
    const scripts = await tm1.client().drillthrough.scripts("c1", 4, ODataQuery.none().select("Name")).toArray();
    expect(scripts.map((s) => s.Name)).toEqual(["}Drill_Sales"]);
    expect(typeOf(Drillthrough, scripts[0]!)).toBe("CubeDrillthrough");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Cellsets('c1')/Cells(4)/DrillthroughScripts?$select=Name");
  });

  it("executes a cell's drillthroughs", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ ID: "c2" });
    expect((await client.drillthrough.execute("c1", 4, "}Drill_Sales", ODataQuery.none().expand("Cells"))).ID).toBe("c2");
    expect([tm1.last.method, decoded(tm1), tm1.last.text]).toEqual(["POST", SCRIPT + "/tm1.Execute?$expand=Cells", "{}"]);

    tm1.respond = () => FakeTm1.collection({ Region: "North", Amount: 5 });
    const rows = await client.drillthrough.executeRelational("c1", 4, "}Drill_Sales").toArray();
    expect(rows).toEqual([{ Region: "North", Amount: 5 }]);
    expect([tm1.last.method, decoded(tm1), tm1.last.text]).toEqual(["POST", SCRIPT + "/tm1.Execute", "{}"]);

    tm1.respond = () => ({ ID: "r1" });
    expect((await client.drillthrough.executeRelationalWithRowset("c1", 4, "}Drill_Sales")).ID).toBe("r1");
    expect([tm1.last.method, decoded(tm1), tm1.last.text]).toEqual(["POST", SCRIPT + "/tm1.ExecuteWithRowset", "{}"]);
  });

  it("executes processes directly", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    const parameters = [ProcessParameterPatch.builder().name("pRegion").value("North").build()];
    tm1.respond = () => ({ ID: "c2" });
    expect((await client.drillthrough.executeProcess("}Drill_Sales", parameters, ODataQuery.none().expand("Axes"))).ID).toBe("c2");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "ExecuteCubeDrillthrough?$expand=Axes"]);
    expect(tm1.last.text).toBe(`{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Sales')","Parameters":[{"Name":"pRegion","Value":"North"}]}`);
    await client.drillthrough.executeProcess("}Drill_Sales", []);
    expect([tm1.last.target, tm1.last.text]).toEqual(["ExecuteCubeDrillthrough", `{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Sales')"}`]);

    tm1.respond = () => FakeTm1.collection({ Amount: 5 }, { Amount: 6 });
    const rows = (await client.drillthrough.executeRelationalProcess("}Drill_Orders", parameters).toArray()) as { Amount: number }[];
    expect(rows.map((r) => r.Amount)).toEqual([5, 6]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "ExecuteRelationalDrillthrough"]);
    expect(tm1.last.text).toBe(`{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Orders')","Parameters":[{"Name":"pRegion","Value":"North"}]}`);

    tm1.respond = () => ({ ID: "r1" });
    expect((await client.drillthrough.executeRelationalProcessWithRowset("}Drill_Orders", [])).ID).toBe("r1");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual([
      "POST",
      "ExecuteRelationalDrillthroughWithRowset",
      `{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Orders')"}`,
    ]);
  });

  it("reads and deletes rowsets", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ ID: "r1" });
    expect((await client.drillthrough.rowset("r1", ODataQuery.none().select("ID"))).ID).toBe("r1");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Rowsets('r1')?$select=ID");

    tm1.respond = () => FakeTm1.collection({ Region: "North" }, { Region: "South" });
    const rows = await client.drillthrough.rows("r1", ODataQuery.none().top(2).skip(1)).toArray();
    expect(rows.map((r) => r["Region"])).toEqual(["North", "South"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Rowsets('r1')/Rows?$top=2&$skip=1");

    tm1.respond = () => null;
    await client.drillthrough.deleteRowset("r1");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("DELETE Rowsets('r1')");
  });
});

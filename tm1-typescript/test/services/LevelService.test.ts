import { describe, expect, it } from "vitest";
import { ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const LEVELS = "Dimensions('Region')/Hierarchies('Region')/Levels";

/** LevelService against a fake TM1: each method sends what the Java client's does (the .NET
 * client's LevelServiceTests; the Java client tests the service only against TM1). */
describe("LevelService", () => {
  it("lists and gets", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Number: 0, Name: "Level000" }, { Number: 1, Name: "Level001" });
    const levels = await client.levels.list("Region", "Region", ODataQuery.none().orderBy("Number")).toArray();
    expect(levels.map((l) => l.Number)).toEqual([0, 1]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${LEVELS}?$orderby=Number`);
    tm1.respond = () => ({ Number: 1, Name: "Level001" });
    expect((await client.levels.get("Region", "Region", 1)).Number).toBe(1);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${LEVELS}(1)`);
    // Java's int
    expect(() => client.levels.get("Region", "Region", 1.5)).toThrow(RangeError);
  });

  it("navigates", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "West" });
    const members = await client.levels.members("Region", "Region", 0, ODataQuery.none().select("Name")).toArray();
    expect(members.map((m) => m.Name)).toEqual(["West"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${LEVELS}(0)/Members?$select=Name`);
    tm1.respond = () => ({ Name: "Region" });
    expect((await client.levels.hierarchy("Region", "Region", 0)).Name).toBe("Region");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${LEVELS}(0)/Hierarchy`);
  });
});

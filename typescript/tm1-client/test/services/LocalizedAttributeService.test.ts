import { describe, expect, it } from "vitest";
import { AttributeOwner, AttributesPatch, LocalizedAttributesPatch, ODataQuery, SubsetScope, ViewScope } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** LocalizedAttributeService and AttributeOwner against a fake TM1: each sends what the Java
 * client's does (the .NET client's LocalizedAttributeServiceTests; the Java client tests them only
 * against TM1). */
describe("LocalizedAttributeService", () => {
  it.each([
    [AttributeOwner.dimension("Region"), "Dimensions('Region')"],
    [AttributeOwner.hierarchy("Region", "Leaves"), "Dimensions('Region')/Hierarchies('Leaves')"],
    [AttributeOwner.element("Region", "Region", "West"), "Dimensions('Region')/Hierarchies('Region')/Elements('West')"],
    [AttributeOwner.member("Region", "Region", "West"), "Dimensions('Region')/Hierarchies('Region')/Members('West')"],
    [AttributeOwner.subset("Region", "Region", SubsetScope.Private, "s"), "Dimensions('Region')/Hierarchies('Region')/PrivateSubsets('s')"],
    [AttributeOwner.cube("Sales"), "Cubes('Sales')"],
    [AttributeOwner.view("Sales", ViewScope.Public, "v"), "Cubes('Sales')/Views('v')"],
    [AttributeOwner.view("Sales", ViewScope.Private, "v"), "Cubes('Sales')/PrivateViews('v')"],
    [AttributeOwner.process("Load"), "Processes('Load')"],
    [AttributeOwner.chore("Nightly"), "Chores('Nightly')"],
  ] as const)("lists, gets and creates for %s", async (owner, path) => {
    expect(owner.toString()).toBe(path);
    const localized = path + "/LocalizedAttributes";
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ LocaleID: "fr", Attributes: { Caption: "Ouest", Manager: "Jean" } });
    const translations = await client.localizedAttributes.list(owner, ODataQuery.none().top(1)).toArray();
    expect(translations.map((t) => [t.LocaleID, t.Attributes?.Caption, t.Attributes?.["Manager"]])).toEqual([["fr", "Ouest", "Jean"]]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe(`GET ${localized}?$top=1`);

    tm1.respond = () => ({ LocaleID: "de-CH" });
    expect((await client.localizedAttributes.get(owner, "de-CH")).LocaleID).toBe("de-CH");
    expect([tm1.last.method, tm1.last.target]).toEqual(["GET", `${localized}('de-CH')`]);
    await client.localizedAttributes.create(
      owner,
      LocalizedAttributesPatch.builder().localeID("fr").attributes(AttributesPatch.builder().caption("Ouest").property("Manager", "Jean").build()).build(),
    );
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual([
      "POST",
      localized,
      '{"LocaleID":"fr","Attributes":{"Caption":"Ouest","Manager":"Jean"}}',
    ]);
  });

  it("finds a subset or a view in its scope's collection", () => {
    expect(AttributeOwner.view("Sales", "Public", "v").toString()).toBe("Cubes('Sales')/Views('v')");
    expect(AttributeOwner.view("Sales", "Private", "v").toString()).toBe("Cubes('Sales')/PrivateViews('v')");
    expect(AttributeOwner.subset("R", "R", "Public", "s").toString()).toBe("Dimensions('R')/Hierarchies('R')/Subsets('s')");
    expect(AttributeOwner.subset("R", "R", "Session", "s").toString()).toBe("Dimensions('R')/Hierarchies('R')/SessionSubsets('s')");
  });
});

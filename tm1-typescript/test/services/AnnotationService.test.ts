import { describe, expect, it } from "vitest";
import { AnnotationPatch, ODataQuery } from "../../src/index.js";
import { FakeTm1, type Recorded } from "../support/fake-tm1.js";

const sent = (r: Recorded): string => decodeURIComponent(`${r.method} ${r.target}`);

/** AnnotationService against a fake TM1: each method sends what the Java client's does (the .NET
 * client's AnnotationServiceTests; the Java client tests it only against TM1). */
describe("AnnotationService", () => {
  it("annotates a cell as Workspace does", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => ({ ID: "C}ClientGroups~B.B~7uA", Text: "first note", commentLocation: "Admin,ADMIN" });
    const cell = new Map([
      ["}Clients", "Admin"],
      ["}Groups", "ADMIN"],
    ]);
    const made = await client.annotations.create("}ClientGroups", cell, "first note");
    expect(made.ID).toBe("C}ClientGroups~B.B~7uA");
    expect(made["commentLocation"]).toBe("Admin,ADMIN");
    expect(sent(tm1.last)).toBe("POST Annotations");
    const body = tm1.last.json;
    expect(Object.keys(body)).toEqual(["Text", "ApplicationContext", "DimensionalContext@odata.bind", "objectName", "commentValue", "commentType", "commentLocation"]);
    expect(body.Text).toBe("first note");
    expect(body.ApplicationContext).toHaveLength(1);
    expect(decodeURIComponent(body.ApplicationContext[0]["Facet@odata.bind"])).toBe("ApplicationContextFacets('}Cubes')");
    expect(body.ApplicationContext[0].Value).toBe("}ClientGroups");
    expect((body["DimensionalContext@odata.bind"] as string[]).map((e) => decodeURIComponent(e))).toEqual([
      "Dimensions('}Clients')/Hierarchies('}Clients')/Members('Admin')",
      "Dimensions('}Groups')/Hierarchies('}Groups')/Members('ADMIN')",
    ]);
    expect(body.objectName).toBe("}ClientGroups");
    expect(body.commentValue).toBe("first note");
    expect(body.commentType).toBe("ANNOTATION");
    expect(body.commentLocation).toBe("Admin,ADMIN");

    await client.annotations.create(AnnotationPatch.builder().text("by hand").build());
    expect([sent(tm1.last), tm1.last.text]).toEqual(["POST Annotations", '{"Text":"by hand"}']);
  });

  it("reads, changes and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection();
    await client.annotations.list(ODataQuery.none().top(1)).toArray();
    expect(sent(tm1.last)).toBe("GET Annotations?$top=1");
    await client.annotations.applicationContext("a1", ODataQuery.none().expand("Facet")).toArray();
    expect(sent(tm1.last)).toBe("GET Annotations('a1')/ApplicationContext?$expand=Facet");
    await client.annotations.dimensionalContext("a1").toArray();
    expect(sent(tm1.last)).toBe("GET Annotations('a1')/DimensionalContext");
    await client.annotations.attachments("a1").toArray();
    expect(sent(tm1.last)).toBe("GET Annotations('a1')/Attachments");
    await client.annotations.cubeAnnotations("Sales").toArray();
    expect(sent(tm1.last)).toBe("GET Cubes('Sales')/Annotations");
    await client.annotations.cellAnnotations("cs1", 3).toArray();
    expect(sent(tm1.last)).toBe("GET Cellsets('cs1')/Cells(3)/Annotations");
    await client.annotations.facets(ODataQuery.none().expand("Values")).toArray();
    expect(sent(tm1.last)).toBe("GET ApplicationContextFacets?$expand=Values");
    await client.annotations.facetValues("}Cubes").toArray();
    expect(sent(tm1.last)).toBe("GET ApplicationContextFacets('}Cubes')/Values");

    tm1.respond = () => ({ Name: "}Cubes", ID: "a1", Text: "note" });
    expect((await client.annotations.facet("}Cubes", "Sales")).Name).toBe("}Cubes");
    expect(sent(tm1.last)).toBe("GET ApplicationContextFacets('}Cubes')/Values('Sales')/Facet");
    expect((await client.annotations.get("a1")).Text).toBe("note");
    expect(sent(tm1.last)).toBe("GET Annotations('a1')");

    tm1.respond = () => null;
    await client.annotations.update("a1", AnnotationPatch.builder().text("second").property("commentValue", "second").build());
    expect([sent(tm1.last), tm1.last.text]).toEqual(["PATCH Annotations('a1')", '{"Text":"second","commentValue":"second"}']);
    await client.annotations.delete("a1");
    expect(sent(tm1.last)).toBe("DELETE Annotations('a1')");
    await client.annotations.deleteAnnotationArtifacts();
    expect([sent(tm1.last), tm1.last.text]).toEqual(["POST DeleteAnnotationArtifacts", "{}"]);
  });
});

// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import {
  Attributes,
  AttributesPatch,
  Cube,
  CubePatch,
  DimensionPatch,
  Element,
  ElementPatch,
  ElementType,
  Filter,
  HierarchyPatch,
  MDXView,
  MDXViewPatch,
  Member,
  ODataPath,
  ODataQuery,
  readAs,
  typeOf,
  View,
  type ViewPatch,
} from "../src/index.js";

/** The Java client's `GeneratedModelTest`: the generated model reads TM1's JSON, and its patches
 * write what TM1 is sent. A model read here is TM1's JSON, its own property names kept, so where
 * Java reads into a record this reads with readAs and looks at the JSON. */
describe("the generated model", () => {
  it("reads an entity with its etag and expanded navigation, ignoring what it doesn't know", () => {
    const cube = readAs(
      Cube,
      JSON.parse(`{"@odata.context":"$metadata#Cubes/$entity","@odata.etag":"W/\\"abc\\"","Name":"Sales",
        "LastDataUpdate":"2026-11-21T10:15:30Z","SomethingNewer":42,
        "Dimensions":[{"Name":"Region"},{"Name":"Month"}]}`),
    );
    expect(cube["@odata.etag"]).toBe('W/"abc"');
    expect(cube.Name).toBe("Sales");
    expect(cube.LastDataUpdate).toBe("2026-11-21T10:15:30Z");
    expect(cube.Dimensions?.map((d) => d.Name)).toEqual(["Region", "Month"]);
    expect(cube.Rules, "not selected").toBeUndefined();
    // What it doesn't know doesn't stop the read. Java drops it; here a value read is TM1's JSON, so
    // it stays as TM1 sent it
    expect(cube).toHaveProperty("SomethingNewer", 42);
  });

  it("reads enums and falls back for a value it doesn't know", () => {
    expect(readAs(Element, { Type: "Consolidated" }).Type).toBe(ElementType.Consolidated);
    expect(readAs(Element, { Type: "SomethingNew" }).Type).toBe(ElementType.Unknown);
    expect(JSON.stringify(ElementType.Numeric)).toBe('"Numeric"');
  });

  it("reads a hierarchy by @odata.type wherever it is, and falls back for types it doesn't know", () => {
    expect(typeOf(View, readAs(View, { "@odata.type": "#ibm.tm1.api.v1.NativeView", Name: "a" }))).toBe("NativeView");
    const mdx = readAs(View, { Name: "b", MDX: "SELECT", "@odata.type": "#tm1.MDXView" });
    expect(typeOf(View, mdx), "by alias, and with the type after other properties").toBe("MDXView");
    expect((mdx as MDXView).MDX).toBe("SELECT");
    const future = readAs(View, { "@odata.type": "#ibm.tm1.api.v1.FutureView", Name: "c", X: 1 });
    expect(typeOf(View, future), "a subtype a newer server added").toBe("View");
    expect(future.Name).toBe("c");
    expect(future["X"], "views are open: what it doesn't know is kept").toBe(1);
    expect(typeOf(View, readAs(View, { Name: "d" }))).toBe("View");
  });

  it("reads a value of a concrete root as plain", () => {
    expect(typeOf(Member, readAs(Member, { Name: "m" }))).toBe("Member");
    expect(typeOf(Member, readAs(Member, { "@odata.type": "#ibm.tm1.api.v1.TupleMember", Name: "t" }))).toBe("TupleMember");
  });

  it("reads a subtype asked for by name", () => {
    expect(readAs(MDXView, { "@odata.type": "#ibm.tm1.api.v1.MDXView", Name: "e" }).Name).toBe("e");
    const f = readAs(MDXView, { Name: "f" });
    expect(f.Name, "without @odata.type").toBe("f");
    expect(typeOf(MDXView, f)).toBe("MDXView");
  });

  it("writes queries with field constants and enum literals", () => {
    const query = ODataQuery.none()
      .select(Element.Fields.Name, Element.Fields.Type)
      .filter(Element.Fields.Type.eq(ElementType.literal("Consolidated")))
      .expand(Element.Fields.Parents, (p) => p.select(Element.Fields.Name))
      .orderBy(Element.Fields.Name);
    expect(
      query.equals(
        ODataQuery.none()
          .select("Name", "Type")
          .filter(Filter.raw("(Type eq ibm.tm1.api.v1.ElementType'Consolidated')"))
          .expand("Parents", (p) => p.select("Name"))
          .orderBy("Name"),
      ),
    ).toBe(true);
    expect(MDXView.Fields.MDX.path).toBe("MDX");
    expect(MDXView.Fields.Name.path, "inherited from View").toBe(View.Fields.Name.path);
    expect(() => ElementType.literal("Unknown")).toThrow();
  });

  it("sends only what a patch sets, in order, with explicit nulls and bindings", () => {
    const dimensions = ODataPath.of("Dimensions");
    const patch = (): CubePatch => CubePatch.builder().name("Sales").rules(null).bindDimensions(dimensions.key("Region"), dimensions.key("Month")).build();
    expect(JSON.stringify(patch())).toBe('{"Name":"Sales","Rules":null,"Dimensions@odata.bind":["Dimensions(\'Region\')","Dimensions(\'Month\')"]}');
    expect(patch().equals(patch())).toBe(true);
    expect(patch().equals(CubePatch.builder().name("Sales").build())).toBe(false);
  });

  it("nests patches for deep inserts, and writes enums as TM1 does", () => {
    const dimension = DimensionPatch.builder()
      .name("Region")
      .hierarchies([
        HierarchyPatch.builder()
          .name("Region")
          .elements([ElementPatch.builder().name("North").type(ElementType.Numeric).build()])
          .build(),
      ])
      .build();
    expect(JSON.stringify(dimension)).toBe('{"Name":"Region","Hierarchies":[{"Name":"Region","Elements":[{"Name":"North","Type":"Numeric"}]}]}');
  });

  it("sends a subtype's patch with its @odata.type, and keeps its builder when chaining", () => {
    // name() is View's setter: it hands back MDXView's builder, so mdx() is still there
    const view = MDXViewPatch.builder().name("v").mdx("SELECT").build();
    expect(JSON.stringify(view)).toBe('{"@odata.type":"#ibm.tm1.api.v1.MDXView","Name":"v","MDX":"SELECT"}');
    const asRoot: ViewPatch = view;
    expect(asRoot.toJSON()).toHaveProperty(["@odata.type"]);
    expect(JSON.stringify(CubePatch.builder().views([view]).build())).toContain("#ibm.tm1.api.v1.MDXView");
  });

  it("keeps attribute values beyond the caption", () => {
    const json = '{"Caption":"North","Region Code":"N","}TM1_DefaultDisplayValue":"North","Rank":3}';
    const attributes = readAs(Attributes, JSON.parse(json));
    expect(attributes.Caption).toBe("North");
    expect(Object.keys(attributes).filter((k) => k !== "Caption")).toEqual(["Region Code", "}TM1_DefaultDisplayValue", "Rank"]);
    expect(attributes["Rank"]).toBe(3);
    expect(JSON.parse(JSON.stringify(attributes))).toEqual(JSON.parse(json));
    expect(JSON.stringify(AttributesPatch.builder().caption("Nord").property("Region Code", "N").build())).toBe('{"Caption":"Nord","Region Code":"N"}');
  });
});

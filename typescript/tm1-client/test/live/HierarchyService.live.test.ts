// SPDX-License-Identifier: Apache-2.0

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  DimensionCreate,
  EdgeCreate,
  ElementCreate,
  ElementType,
  Hierarchy,
  HierarchyCreate,
  HierarchyPatch,
  Member,
  ODataPath,
  ODataQuery,
  Tm1PreconditionFailedException,
  Tm1Request,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const numeric = (name: string): ElementCreate => ElementCreate.builder().name(name).type(ElementType.Numeric).build();
const consolidated = (name: string): ElementCreate => ElementCreate.builder().name(name).type(ElementType.Consolidated).build();
const edge = (parent: string, component: string): EdgeCreate => EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();

/** Total = North + South, and East alone. */
const structure = (name: string): HierarchyCreate =>
  HierarchyCreate.builder()
    .name(name)
    .elements([consolidated("Total"), numeric("North"), numeric("South"), numeric("East")])
    .edges([edge("Total", "North"), edge("Total", "South")])
    .build();

/** HierarchyService against a real TM1, on a dimension of each test's own: the Java client's `HierarchyServiceTm1Test`. */
describe("HierarchyService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let dimension: string;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  beforeEach(async () => {
    if (!Live.configured) return;
    dimension = Live.name();
    await tm1.dimensions.create(
      DimensionCreate.builder()
        .name(dimension)
        .hierarchies([structure(dimension)])
        .build(),
    );
  });

  afterEach(async () => {
    if (!Live.configured) return;
    await tm1.dimensions.delete(dimension);
  });

  const roots = async (): Promise<string[]> =>
    (await tm1.hierarchies.rootMembers(dimension, dimension, ODataQuery.none().select(Member.Fields.Name)).toArray()).map((m) => m.Name!);
  const elements = async () => (await tm1.hierarchies.get(dimension, dimension, ODataQuery.none().expand(Hierarchy.Fields.Elements))).Elements ?? [];

  tm1Test("creates, lists, reads and deletes a hierarchy", async () => {
    expect((await tm1.hierarchies.create(dimension, structure("Alt"))).Name).toBe("Alt");
    expect((await tm1.hierarchies.list(dimension, ODataQuery.none().select(Hierarchy.Fields.Name)).toArray()).map((h) => h.Name)).toEqual(
      expect.arrayContaining([dimension, "Alt"]),
    );
    expect((await tm1.hierarchies.get(dimension, "Alt", ODataQuery.none().expand(Hierarchy.Fields.Elements))).Elements).toHaveLength(4);
    expect((await tm1.hierarchies.defaultHierarchy(dimension)).Name).toBe(dimension);
    expect((await tm1.hierarchies.dimension(dimension, "Alt")).Name).toBe(dimension);

    await tm1.hierarchies.delete(dimension, "Alt");
    expect((await tm1.hierarchies.list(dimension).toArray()).map((h) => h.Name)).not.toContain("Alt");
  });

  tm1Test("refuses to replace elements through a plain update", () => {
    // HierarchyPatch offers neither; a JavaScript caller's patch with them is refused, sending nothing
    expect("elements" in HierarchyPatch.builder()).toBe(false);
    const elements = HierarchyCreate.builder().elements([]).build() as unknown as HierarchyPatch;
    expect(() => tm1.hierarchies.update(dimension, dimension, elements)).toThrow(/replaceStructure/);
    const edges = HierarchyCreate.builder()
      .edges([edge("Total", "East")])
      .build() as unknown as HierarchyPatch;
    expect(() => tm1.hierarchies.update(dimension, dimension, edges, 'W/"x"')).toThrow(RangeError);
  });

  tm1Test("replaces the structure, deleting what it doesn't list", async () => {
    await tm1.hierarchies.replaceStructure(dimension, dimension, [consolidated("Total"), numeric("North")], [edge("Total", "North")]);
    expect((await elements()).map((e) => e.Name).sort()).toEqual(["North", "Total"]);
  });

  tm1Test("updates only when the ETag is current, and ignores Visible", async () => {
    const etag = (await tm1.hierarchies.get(dimension, dimension))["@odata.etag"]!;
    await tm1.hierarchies.update(dimension, dimension, HierarchyPatch.builder().elementsSortType("ByInput").build(), etag);
    // HierarchyPatch doesn't offer Visible, as TM1 ignores it: sent as it is, to pin it
    await tm1.session.execute(Tm1Request.patch(ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension), { Visible: false }));
    // TM1 accepts Visible in a PATCH and ignores it
    expect((await tm1.hierarchies.get(dimension, dimension)).Visible).toBe(true);

    await tm1.hierarchies.replaceStructure(dimension, dimension, [numeric("North")], []);
    await expect(tm1.hierarchies.update(dimension, dimension, HierarchyPatch.builder().build(), etag)).rejects.toBeInstanceOf(Tm1PreconditionFailedException);
  });

  tm1Test("reads the all, default and root members", async () => {
    // No all member with two roots: TM1 sends 204
    expect(await tm1.hierarchies.allMember(dimension, dimension)).toBeNull();
    expect((await tm1.hierarchies.defaultMember(dimension, dimension))?.Name?.trim()).toBeTruthy();
    expect(await roots()).toEqual(["Total", "East"]);

    await tm1.hierarchies.replaceStructure(dimension, dimension, [consolidated("Total"), numeric("North")], [edge("Total", "North")]);
    // With one root, the all member is that root
    expect((await tm1.hierarchies.allMember(dimension, dimension))?.Name).toBe("Total");
  });

  tm1Test("saves a copy", async () => {
    expect((await tm1.hierarchies.saveAs(dimension, dimension, "Copy")).Name).toBe("Copy");
    expect((await tm1.hierarchies.saveAs(dimension, dimension, "Copy", true, false)).Name).toBe("Copy");
    expect((await tm1.hierarchies.get(dimension, "Copy", ODataQuery.none().expand(Hierarchy.Fields.Elements))).Elements).toHaveLength(4);
  });

  tm1Test("places a root before another, or last without before", async () => {
    await tm1.hierarchies.setElement(dimension, dimension, "East", "Total");
    expect(await roots()).toEqual(["East", "Total"]);
    await tm1.hierarchies.setElement(dimension, dimension, "East");
    // No Before puts it last, though $metadata says first
    expect(await roots()).toEqual(["Total", "East"]);
  });
});

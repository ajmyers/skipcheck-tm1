// SPDX-License-Identifier: Apache-2.0

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  DimensionPatch,
  Edge,
  EdgePatch,
  Element,
  ElementPatch,
  ElementType,
  HierarchyPatch,
  ODataPath,
  ODataQuery,
  Tm1BadRequestException,
  Tm1NotFoundException,
  Tm1PreconditionFailedException,
  Tm1Request,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const element = (name: string, type: ElementType): ElementPatch => ElementPatch.builder().name(name).type(type).build();
const edge = (parent: string, component: string): EdgePatch => EdgePatch.builder().parentName(parent).componentName(component).weight(1.0).build();

/** ElementService (and edges) against a real TM1, on a dimension with Total = North + South, and
 * East and Label alone: the Java client's `ElementServiceTm1Test`. */
describe("ElementService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let d: string;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  beforeEach(async () => {
    if (!Live.configured) return;
    d = Live.name();
    await tm1.dimensions.create(
      DimensionPatch.builder()
        .name(d)
        .hierarchies([
          HierarchyPatch.builder()
            .name(d)
            .elements([
              element("Total", ElementType.Consolidated),
              element("North", ElementType.Numeric),
              element("South", ElementType.Numeric),
              element("East", ElementType.Numeric),
              element("Label", ElementType.String),
            ])
            .edges([edge("Total", "North"), edge("Total", "South")])
            .build(),
        ])
        .build(),
    );
  });

  afterEach(async () => {
    if (!Live.configured) return;
    await tm1.dimensions.delete(d);
  });

  const components = async (parent: string): Promise<string[]> => (await tm1.elements.components(d, d, parent).toArray()).map((e) => e.Name!);

  const edgeOf = (parent: string, component: string): Promise<Edge> => tm1.edges.get(d, d, parent, component);

  tm1Test("creates, reads, updates and deletes an element", async () => {
    expect((await tm1.elements.create(d, d, element("West", ElementType.Numeric))).Name).toBe("West");
    expect((await tm1.elements.list(d, d, ODataQuery.none().select(Element.Fields.Name)).toArray()).map((e) => e.Name).sort()).toEqual([
      "East",
      "Label",
      "North",
      "South",
      "Total",
      "West",
    ]);

    const etag = (await tm1.elements.get(d, d, "West"))["@odata.etag"]!;
    await tm1.elements.update(d, d, "West", ElementPatch.builder().type(ElementType.String).build(), etag);
    expect((await tm1.elements.get(d, d, "West")).Type).toBe(ElementType.String);
    await expect(tm1.elements.update(d, d, "West", ElementPatch.builder().type(ElementType.Numeric).build(), etag)).rejects.toBeInstanceOf(
      Tm1PreconditionFailedException,
    );

    await tm1.elements.delete(d, d, "West");
    await expect(tm1.elements.get(d, d, "West")).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("refuses name and attributes in an update, as TM1 ignores or rejects them", async () => {
    expect(() => tm1.elements.update(d, d, "East", ElementPatch.builder().name("Est").build())).toThrow(/renamed/);
    expect(() => tm1.elements.update(d, d, "East", ElementPatch.builder().name("Est").build(), 'W/"x"')).toThrow(RangeError);

    const east = ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key("East");
    await tm1.session.execute(Tm1Request.patch(east, { Name: "Est" }));
    // TM1 ignores the new name
    expect((await tm1.elements.get(d, d, "East")).Name).toBe("East");
    // TM1 rejects attributes
    await expect(tm1.session.execute(Tm1Request.patch(east, { Attributes: { Caption: "E" } }))).rejects.toBeInstanceOf(Tm1BadRequestException);
  });

  tm1Test("navigates parents, components, edges and the hierarchy", async () => {
    expect((await tm1.elements.parents(d, d, "North").toArray()).map((e) => e.Name)).toEqual(["Total"]);
    expect(await components("Total")).toEqual(["North", "South"]);
    expect((await tm1.elements.edges(d, d, "Total").toArray()).map((e) => [e.ParentName, e.ComponentName])).toEqual([
      ["Total", "North"],
      ["Total", "South"],
    ]);
    expect((await tm1.elements.hierarchy(d, d, "North")).Name).toBe(d);
  });

  tm1Test("sets components last, or before another with a weight", async () => {
    await tm1.elements.setComponent(d, d, "Total", "East");
    // No Before puts it last
    expect(await components("Total")).toEqual(["North", "South", "East"]);
    await tm1.elements.create(d, d, element("West", ElementType.Numeric));
    await tm1.elements.setComponent(d, d, "Total", "West", "North", 2.0);
    expect(await components("Total")).toEqual(["West", "North", "South", "East"]);
    expect((await edgeOf("Total", "West")).Weight).toBe(2.0);
    await tm1.elements.create(d, d, element("Far", ElementType.Numeric));
    await tm1.elements.setComponent(d, d, "Total", "Far", -1.0);
    expect((await edgeOf("Total", "Far")).Weight).toBe(-1.0);
  });

  tm1Test("locks and reserves an element", async () => {
    await tm1.elements.lock(d, d, "North");
    await tm1.elements.unlock(d, d, "North");
    await tm1.elements.reserve(d, d, "North");
    await tm1.elements.release(d, d, "North");
  });
});

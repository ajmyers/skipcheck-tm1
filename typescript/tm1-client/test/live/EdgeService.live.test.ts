// SPDX-License-Identifier: Apache-2.0

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import { DimensionCreate, EdgeCreate, EdgePatch, ElementCreate, ElementType, HierarchyCreate, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** EdgeService against a real TM1, on Total = North + South, with East and Label besides: the Java
 * client's `EdgeServiceTm1Test`. */
describe("EdgeService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let d: string;

  const edge = (parent: string, component: string): EdgeCreate => EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();

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
    const element = (name: string, type: ElementType): ElementCreate => ElementCreate.builder().name(name).type(type).build();
    await tm1.dimensions.create(
      DimensionCreate.builder()
        .name(d)
        .hierarchies([
          HierarchyCreate.builder()
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
    if (!tm1 || !d) return;
    await tm1.dimensions.delete(d).catch(() => undefined);
  });

  tm1Test("creates, reads, reweights and deletes edges", async () => {
    expect(await tm1.edges.list(d, d).toArray()).toHaveLength(2);
    expect((await tm1.edges.get(d, d, "Total", "North")).Weight).toBe(1.0);
    await tm1.edges.update(d, d, "Total", "North", EdgePatch.builder().weight(3.0).build());
    expect((await tm1.edges.get(d, d, "Total", "North")).Weight).toBe(3.0);

    expect((await tm1.edges.parent(d, d, "Total", "North")).Name).toBe("Total");
    expect((await tm1.edges.component(d, d, "Total", "North")).Name).toBe("North");
    expect((await tm1.edges.hierarchy(d, d, "Total", "North")).Name).toBe(d);

    expect((await tm1.edges.create(d, d, edge("Total", "East"))).ComponentName).toBe("East");
    await tm1.edges.delete(d, d, "Total", "North");
    const components = await tm1.elements.components(d, d, "Total").toArray();
    expect(components.map((e) => e.Name)).toEqual(["South", "East"]);
    const parents = await tm1.elements.parents(d, d, "North").toArray();
    expect(parents, "still an element, now a root").toEqual([]);
  });
});

// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import { DimensionPatch, EdgePatch, ElementPatch, ElementType, HierarchyPatch, Member, ODataQuery, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const numeric = (name: string): ElementPatch => ElementPatch.builder().name(name).type(ElementType.Numeric).build();
const consolidated = (name: string): ElementPatch => ElementPatch.builder().name(name).type(ElementType.Consolidated).build();
const edge = (parent: string, component: string): EdgePatch => EdgePatch.builder().parentName(parent).componentName(component).weight(1.0).build();

/** LevelService against a real TM1, on World > Europe (France, Spain), Asia (Japan): the Java
 * client's `LevelAndMemberServiceTm1Test`. */
describe("LevelService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const d = Live.name();

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await tm1.dimensions.create(
      DimensionPatch.builder()
        .name(d)
        .hierarchies([
          HierarchyPatch.builder()
            .name(d)
            .elements([consolidated("World"), consolidated("Europe"), consolidated("Asia"), numeric("France"), numeric("Spain"), numeric("Japan")])
            .edges([edge("World", "Europe"), edge("World", "Asia"), edge("Europe", "France"), edge("Europe", "Spain"), edge("Asia", "Japan")])
            .build(),
        ])
        .build(),
    );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.dimensions.delete(d).catch(() => undefined);
    await tm1.close();
  });

  tm1Test("lists levels and their members", async () => {
    const levels = await tm1.levels.list(d, d).toArray();
    expect(levels.map((l) => l.Number)).toEqual([0, 1, 2]);
    expect(levels.map((l) => l.Cardinality)).toEqual([1, 2, 3]);
    expect((await tm1.levels.get(d, d, 2)).Cardinality).toBe(3);
    expect((await tm1.levels.members(d, d, 2, ODataQuery.none().select(Member.Fields.Name)).toArray()).map((m) => m.Name).sort()).toEqual([
      "France",
      "Japan",
      "Spain",
    ]);
    expect((await tm1.levels.hierarchy(d, d, 0)).Name).toBe(d);
  });
});

// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  Cube,
  CubePatch,
  DimensionPatch,
  ElementPatch,
  ElementType,
  Filter,
  HierarchyPatch,
  ODataPath,
  ODataQuery,
  Tm1NotFoundException,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

// Not a port of a Java test class: live tests only this client has. A test that ports a Java one
// goes in that class's file (test/java-live-parity.test.ts checks those); this file is exempt.

/** CubeService against a real TM1, on a cube of its own. */
describe("TypeScript-only live tests: CubeService" + liveReason, () => {
  let tm1: Tm1Client;
  const cube = Live.name();
  const dimensions = [Live.name(), Live.name()];

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    // Different elements in each: a rule's ['a'] is ambiguous to TM1 if both dimensions have an "a"
    for (const [d, elements] of [
      [dimensions[0]!, ["a", "b"]],
      [dimensions[1]!, ["x", "y"]],
    ] as const) {
      const hierarchy = HierarchyPatch.builder()
        .name(d)
        .elements(elements.map((e) => ElementPatch.builder().name(e).type(ElementType.Numeric).build()))
        .build();
      await tm1.dimensions.create(DimensionPatch.builder().name(d).hierarchies([hierarchy]).build());
    }
    const all = ODataPath.of("Dimensions");
    await tm1.cubes.create(
      CubePatch.builder()
        .name(cube)
        .bindDimensions(...dimensions.map((d) => all.key(d)))
        .build(),
    );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.cubes.delete(cube).catch(() => undefined);
    for (const d of dimensions) await tm1.dimensions.delete(d).catch(() => undefined);
    await tm1.close();
  });

  tm1Test("finds the cube among the model cubes, by a filter", async () => {
    const found = await tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name).filter(Filter.startsWith(Cube.Fields.Name, cube))).toArray();
    expect(found.map((c) => c.Name)).toEqual([cube]);
    expect((await tm1.cubes.dimensions(cube).toArray()).map((d) => d.Name).filter((n) => n !== "Sandboxes")).toEqual(dimensions);
  });

  tm1Test("checks rules before saving them, and saves them with an ETag", async () => {
    expect(await tm1.cubes.checkRules(cube, "SKIPCHECK;\n['a'] = N: 1;").toArray()).toEqual([]);
    expect((await tm1.cubes.checkRules(cube, "nonsense").toArray()).length).toBeGreaterThan(0);
    const read = await tm1.cubes.get(cube);
    await tm1.cubes.update(cube, CubePatch.builder().rules("SKIPCHECK;").build(), read["@odata.etag"]);
    expect((await tm1.cubes.get(cube, ODataQuery.none().select(Cube.Fields.Rules))).Rules).toContain("SKIPCHECK");
  });

  tm1Test("says a cube that isn't there isn't", async () => {
    await expect(tm1.cubes.get(Live.name())).rejects.toBeInstanceOf(Tm1NotFoundException);
  });
});

// SPDX-License-Identifier: Apache-2.0

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  Cube,
  CubeCreate,
  CubePatch,
  Dimension,
  DimensionCreate,
  ElementCreate,
  ElementType,
  Filter,
  HierarchyCreate,
  ODataPath,
  ODataQuery,
  type Tm1Client,
  Tm1NotFoundException,
  Tm1PreconditionFailedException,
  type Tm1Stream,
} from "../../src/index.js";
import { Tm1Operations } from "../../src/generated/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { lacks } from "./server-assumptions.js";

const dim = (name: string): ODataPath => ODataPath.of("Dimensions").key(name);

const names = async (dimensions: Tm1Stream<Dimension>): Promise<(string | null | undefined)[]> => (await dimensions.toArray()).map((d) => d.Name);

/** CubeService against a real TM1, on a cube of two fresh dimensions, Region and Measure: the Java
 * client's `CubeServiceTm1Test`. */
describe("CubeService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let cube: string;
  let region: string;
  let measure: string;

  const dimension = (name: string, ...elements: string[]): Promise<unknown> =>
    tm1.dimensions.create(
      DimensionCreate.builder()
        .name(name)
        .hierarchies([
          HierarchyCreate.builder()
            .name(name)
            .elements(elements.map((e) => ElementCreate.builder().name(e).type(ElementType.Numeric).build()))
            .build(),
        ])
        .build(),
    );

  const rules = async (): Promise<string | null | undefined> => (await tm1.cubes.get(cube, ODataQuery.none().select(Cube.Fields.Rules))).Rules;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  beforeEach(async () => {
    if (!Live.configured) return;
    cube = Live.name();
    region = cube + "-region";
    measure = cube + "-measure";
    await dimension(region, "North", "South");
    await dimension(measure, "Amount");
    await tm1.cubes.create(CubeCreate.builder().name(cube).bindDimensions(dim(region), dim(measure)).build());
  });

  afterEach(async () => {
    if (!tm1 || !cube) return;
    await tm1.cubes.delete(cube).catch(() => undefined);
    await tm1.dimensions.delete(region);
    await tm1.dimensions.delete(measure);
  });

  tm1Test("creates, reads, lists and deletes a cube", async () => {
    expect((await tm1.cubes.get(cube))["@odata.etag"]?.trim()).toBeTruthy();
    const listed = await tm1.cubes.list(ODataQuery.none().select(Cube.Fields.Name).filter(Filter.startsWith(Cube.Fields.Name, cube))).toArray();
    expect(listed.map((c) => c.Name)).toEqual([cube]);
    // Sandboxes first
    expect(await names(tm1.cubes.dimensions(cube, ODataQuery.none().select(Dimension.Fields.Name)))).toEqual(["Sandboxes", region, measure]);

    await tm1.cubes.delete(cube);
    await expect(tm1.cubes.get(cube)).rejects.toBeInstanceOf(Tm1NotFoundException);
    expect((await tm1.dimensions.get(region)).Name, "its dimensions stay").toBe(region);
  });

  tm1Test("saves rules even if they don't compile, and checks the ETag", async () => {
    const etag = (await tm1.cubes.get(cube))["@odata.etag"]!;
    await tm1.cubes.update(cube, CubePatch.builder().rules("SKIPCHECK;\n").build(), etag);
    expect(await rules()).toContain("SKIPCHECK");
    await expect(tm1.cubes.update(cube, CubePatch.builder().rules("").build(), etag)).rejects.toBeInstanceOf(Tm1PreconditionFailedException);

    await tm1.cubes.update(cube, CubePatch.builder().rules("['Nowhere'] = N: 1 +;\n").build());
    expect(await rules(), "TM1 keeps rules that don't compile").toContain("Nowhere");
    await tm1.cubes.update(cube, CubePatch.builder().rules(null).build());
    expect((await rules()) ?? "").toBe("");
  });

  tm1Test("reorders the storage order", async () => {
    expect((await names(tm1.cubes.dimensionsStorageOrder(cube))).sort()).toEqual([region, measure].sort());
    const change = await tm1.cubes.reorderDimensions(cube, [measure, region]);
    expect(typeof change).toBe("number");
    expect(await names(tm1.cubes.dimensionsStorageOrder(cube))).toEqual([measure, region]);
    expect(await names(tm1.cubes.dimensions(cube)), "presentation order unchanged").toEqual(["Sandboxes", region, measure]);
  });

  tm1Test("loads, unloads, locks and reserves", async (context) => {
    await tm1.cubes.unload(cube);
    await tm1.cubes.load(cube);
    await tm1.cubes.lock(cube);
    await tm1.cubes.unlock(cube);
    const why = await lacks(tm1, Tm1Operations.CubeReserve);
    if (why) return context.skip(why);
    await tm1.cubes.reserve(cube);
    await tm1.cubes.release(cube);
  });
});

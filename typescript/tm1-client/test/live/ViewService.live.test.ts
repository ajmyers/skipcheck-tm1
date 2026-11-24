// SPDX-License-Identifier: Apache-2.0

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  CubeCreate,
  type MDXView,
  MDXViewCreate,
  MDXViewPatch,
  type NativeView,
  NativeViewCreate,
  ODataQuery,
  type Tm1Client,
  Tm1PreconditionFailedException,
  typeOf,
  View,
  ViewAxisSelectionPatch,
  ViewScope,
  ViewTitlePatch,
} from "../../src/index.js";
import { Fixtures } from "./data-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** ViewService against a real TM1, on a cube of Region, Measure and Year, each with a public subset
 * "All": the Java client's `ViewServiceTm1Test`. */
describe("ViewService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let cube = "";
  let region = "";
  let measure = "";
  let year = "";

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  beforeEach(async () => {
    if (!tm1) return;
    cube = Live.name();
    region = cube + "_region";
    measure = cube + "_measure";
    year = cube + "_year";
    for (const [d, elements] of [
      [region, ["North", "South"]],
      [measure, ["Amount"]],
      [year, ["2025", "2026"]],
    ] as const) {
      await Fixtures.dimension(tm1, d, [...elements]);
      await Fixtures.subsetAll(tm1, d);
    }
    await tm1.cubes.create(
      CubeCreate.builder().name(cube).bindDimensions(Fixtures.dimensionPath(region), Fixtures.dimensionPath(measure), Fixtures.dimensionPath(year)).build(),
    );
  });

  afterEach(async () => {
    if (!tm1) return;
    await tm1.cubes.delete(cube).catch(() => undefined);
    for (const d of [region, measure, year]) await Fixtures.deleteDimension(tm1, d);
  });

  const mdx = (): string => `SELECT {[${measure}].[${measure}].Members} ON 0, {[${region}].[${region}].Members} ON 1 FROM [${cube}]`;
  const nativeView = (name: string): NativeViewCreate =>
    NativeViewCreate.builder()
      .name(name)
      .columns([ViewAxisSelectionPatch.builder().bindSubset(Fixtures.subset(measure)).build()])
      .rows([ViewAxisSelectionPatch.builder().bindSubset(Fixtures.subset(region)).build()])
      .titles([
        ViewTitlePatch.builder()
          .bindSubset(Fixtures.subset(year))
          .bindSelected(Fixtures.dimensionPath(year).nav("Hierarchies").key(year).nav("Elements").key("2026"))
          .build(),
      ])
      .build();

  tm1Test("creates, reads, updates and deletes both kinds of view", async () => {
    await tm1.views.create(cube, ViewScope.Public, MDXViewCreate.builder().name("Mdx").mdx(mdx()).build());
    await tm1.views.create(cube, ViewScope.Public, nativeView("Native"));
    const all = await tm1.views.list(cube, ViewScope.Public).toArray();
    expect(all.map((v) => v.Name).sort()).toEqual(["Mdx", "Native"]);
    expect(all.filter((v) => v.Name === "Mdx").map((v) => typeOf(View, v))).toEqual(["MDXView"]);
    expect(all.filter((v) => v.Name === "Native").map((v) => typeOf(View, v))).toEqual(["NativeView"]);

    const etag = (await tm1.views.get(cube, ViewScope.Public, "Mdx"))["@odata.etag"];
    const changed = `SELECT {[${measure}].[${measure}].[Amount]} ON 0 FROM [${cube}]`;
    await tm1.views.update(cube, ViewScope.Public, "Mdx", MDXViewPatch.builder().mdx(changed).build(), etag);
    const read = await tm1.views.get(cube, ViewScope.Public, "Mdx");
    expect(typeOf(View, read)).toBe("MDXView");
    expect((read as MDXView).MDX).toBe(changed);
    await expect(tm1.views.update(cube, ViewScope.Public, "Mdx", MDXViewPatch.builder().mdx(mdx()).build(), etag)).rejects.toBeInstanceOf(
      Tm1PreconditionFailedException,
    );
    expect((await tm1.views.cube(cube, ViewScope.Public, "Mdx")).Name).toBe(cube);

    await tm1.views.delete(cube, ViewScope.Public, "Mdx");
    expect((await tm1.views.list(cube, ViewScope.Public).toArray()).map((v) => v.Name)).toEqual(["Native"]);
  });

  tm1Test("keeps private views apart", async () => {
    await tm1.views.create(cube, ViewScope.Private, MDXViewCreate.builder().name("Mine").mdx(mdx()).build());
    expect((await tm1.views.list(cube, ViewScope.Private).toArray()).map((v) => v.Name)).toEqual(["Mine"]);
    expect(await tm1.views.list(cube, ViewScope.Public).toArray()).toEqual([]);
  });

  tm1Test("expands a native view's axes through its type", async () => {
    await tm1.views.create(cube, ViewScope.Public, nativeView("Native"));
    const read = await tm1.views.getNative(
      cube,
      ViewScope.Public,
      "Native",
      ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset").expand("Titles/Subset").expand("Titles/Selected"),
    );
    expect(read.Columns?.map((c) => c.Subset?.Name)).toEqual(["All"]);
    expect(read.Rows).toHaveLength(1);
    expect(read.Rows![0]!.Subset?.UniqueName).toContain(region);
    expect(read.Titles?.map((t) => t.Selected?.Name)).toEqual(["2026"]);
  });

  tm1Test("expands native views' axes in a list with MDX views", async () => {
    await tm1.views.create(cube, ViewScope.Public, nativeView("Native"));
    await tm1.views.create(cube, ViewScope.Public, MDXViewCreate.builder().name("Mdx").mdx(mdx()).build());
    const all = await tm1.views.list(cube, ViewScope.Public, ODataQuery.none().expand("tm1.NativeView/Columns/Subset")).toArray();
    const natives = all.filter((v) => typeOf(View, v) === "NativeView") as NativeView[];
    expect(natives).toHaveLength(1);
    expect(natives[0]!.Columns?.map((c) => c.Subset?.Name)).toEqual(["All"]);
    expect(all.filter((v) => typeOf(View, v) === "MDXView")).toHaveLength(1);
  });
});

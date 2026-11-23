import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  CubePatch,
  MDXViewPatch,
  NativeViewPatch,
  ODataQuery,
  Tm1NotFoundException,
  ViewAxisSelectionPatch,
  ViewScope,
  type CellsetCell,
  type Tm1Client,
} from "../../src/index.js";
import { Fixtures } from "./data-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

const REGIONS = ["N", "S", "E", "W"];
const MEASURES = ["Amount", "Count"];

/** CellsetService against a real TM1, on Region (N, S, E, W) by Measure (Amount, Count), cell values
 * 1 to 8 in ordinal order: the Java client's `CellsetServiceTm1Test`. */
describe("CellsetService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const cube = Live.name();
  const region = cube + "_region";
  const measure = cube + "_measure";
  const mdx = `SELECT {[${measure}].[${measure}].Members} ON 0, {[${region}].[${region}].Members} ON 1 FROM [${cube}]`;
  const values = (cells: CellsetCell[] | null | undefined): number[] => (cells ?? []).map((c) => Number(c.Value));

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await Fixtures.dimension(tm1, region, REGIONS);
    await Fixtures.dimension(tm1, measure, MEASURES);
    await tm1.cubes.create(CubePatch.builder().name(cube).bindDimensions(Fixtures.dimensionPath(region), Fixtures.dimensionPath(measure)).build());
    // Ordinals run across the columns (measures) first: cell (row r, column c) is r * 2 + c
    let value = 1;
    for (const r of REGIONS)
      for (const m of MEASURES)
        await tm1.cells.update(
          cube,
          new Map([
            [region, r],
            [measure, m],
          ]),
          String(value++),
        );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.cubes.delete(cube).catch(() => undefined);
    await Fixtures.deleteDimension(tm1, region);
    await Fixtures.deleteDimension(tm1, measure);
    await tm1.close();
  });

  const exists = (id: string): Promise<boolean> =>
    tm1.cellsets.get(id).then(
      () => true,
      (e: unknown) => {
        if (e instanceof Tm1NotFoundException) return false;
        throw e;
      },
    );

  tm1Test("runs MDX and reads the cellset piece by piece", async () => {
    const cellset = await tm1.cellsets.execute(
      mdx,
      ODataQuery.none()
        .expand("Axes")
        .expand("Cells", (c) => c.select("Ordinal", "Value")),
    );
    const id = cellset.ID!;
    expect(id).toBeTruthy();
    expect(cellset.Axes, "columns, rows and the slicer TM1 adds").toHaveLength(3);
    expect(cellset.Cells).toHaveLength(8);
    try {
      expect(await tm1.cellsets.list().toArray(), "TM1 lists none, though each reads by ID").toEqual([]);
      expect((await tm1.cellsets.get(id)).ID).toBe(id);
      expect((await tm1.cellsets.axes(id).toArray()).map((a) => a.Cardinality)).toEqual([2, 4, 1]);
      expect((await tm1.cellsets.hierarchies(id, 1).toArray()).map((h) => h.Name)).toEqual([region]);
      expect(await tm1.cellsets.tuples(id, 1).toArray()).toHaveLength(4);
      expect((await tm1.cellsets.tupleMembers(id, 1, 2).toArray()).map((m) => m.Name)).toEqual(["E"]);
      expect(values(await tm1.cellsets.cells(id, ODataQuery.none().select("Ordinal", "Value")).toArray())).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
      expect(
        (await tm1.cellsets.cellMembers(id, 5).toArray()).map((m) => m.Name),
        "Sandboxes' Base first",
      ).toEqual(["Base", "E", "Count"]);
      expect((await tm1.cellsets.cube(id)).Name).toBe(cube);
      expect(
        (await tm1.cellsets.partition(id, 2, 4, ODataQuery.none().select("Ordinal")).toArray()).map((c) => c.Ordinal),
        "a rectangle: column 0 of rows 1 and 2",
      ).toEqual([2, 4]);
      expect(
        (await tm1.cellsets.partition(id, 2, 5, ODataQuery.none().select("Ordinal")).toArray()).map((c) => c.Ordinal),
        "whole rows",
      ).toEqual([2, 3, 4, 5]);
    } finally {
      await tm1.cellsets.delete(id);
    }
    await expect(tm1.cellsets.get(id)).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("streams cells in order across pages and deletes the cellset", async () => {
    let id = "";
    const read = await tm1.cellsets
      .stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 3, (cellset, cells) => {
        id = cellset.ID!;
        return cells.map((c) => Number(c.Value));
      })
      .toArray();
    expect(read).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    await expect(tm1.cellsets.get(id), "the cellset is deleted").rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("streams at least a row per page", async () => {
    expect(await tm1.cellsets.stream(mdx, ODataQuery.none().select("Ordinal", "Value"), 1, (_, cells) => cells.map((c) => c.Ordinal)).toArray()).toEqual([
      0, 1, 2, 3, 4, 5, 6, 7,
    ]);
  });

  tm1Test("deletes the cellset when the stream is cancelled", async () => {
    let id = "";
    const first: number[] = [];
    for await (const ordinal of tm1.cellsets.stream(mdx, ODataQuery.none().select("Ordinal"), 2, (cellset, cells) => {
      id = cellset.ID!;
      return cells.map((c) => c.Ordinal!);
    })) {
      first.push(ordinal);
      if (first.length === 3) break;
    }
    expect(first).toEqual([0, 1, 2]);
    // The delete runs as the loop ends: it's done by now
    expect(await exists(id)).toBe(false);
    await expect(tm1.cellsets.get(id), "the cellset is deleted").rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("executes MDX and native views", async () => {
    await tm1.views.create(cube, ViewScope.Public, MDXViewPatch.builder().name("Mdx").mdx(mdx).build());
    for (const d of [region, measure]) await Fixtures.subsetAll(tm1, d);
    const axis = (d: string): ViewAxisSelectionPatch => ViewAxisSelectionPatch.builder().bindSubset(Fixtures.subset(d)).build();
    await tm1.views.create(
      cube,
      ViewScope.Public,
      NativeViewPatch.builder()
        .name("Native")
        .columns([axis(measure)])
        .rows([axis(region)])
        .build(),
    );
    for (const view of ["Mdx", "Native"]) {
      const cellset = await tm1.cellsets.executeView(
        cube,
        ViewScope.Public,
        view,
        ODataQuery.none().expand("Cells", (c) => c.select("Value")),
      );
      try {
        expect(values(cellset.Cells), view).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
      } finally {
        await tm1.cellsets.delete(cellset.ID!);
      }
    }
  });
});

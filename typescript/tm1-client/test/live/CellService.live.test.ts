import { afterAll, afterEach, beforeAll, beforeEach, describe, expect } from "vitest";
import {
  CellsetUpdatePatch,
  CubePatch,
  CubeUpdatePatch,
  ODataPath,
  ODataQuery,
  Tm1BadRequestException,
  Tm1Request,
  Tuples,
  UpdateOrder,
  ViewScope,
  type Tm1Client,
} from "../../src/index.js";
import { Fixtures } from "./data-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** Cell writes against a real TM1, on Region (Total = North + South) by Measure (Amount, Note as
 * text): the Java client's `CellServiceTm1Test`. */
describe("CellService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const id = Live.name();
  const region = id + "_region";
  const measure = id + "_measure";
  let cube = "";

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await Fixtures.dimension(
      tm1,
      region,
      [["Total", "Consolidated"], "North", "South"],
      [
        ["Total", "North"],
        ["Total", "South"],
      ],
    );
    await Fixtures.dimension(tm1, measure, ["Amount", ["Note", "String"]]);
  });

  afterAll(async () => {
    if (!tm1) return;
    await Fixtures.deleteDimension(tm1, region);
    await Fixtures.deleteDimension(tm1, measure);
    await tm1.close();
  });

  beforeEach(async () => {
    if (!tm1) return;
    cube = Live.name();
    await tm1.cubes.create(CubePatch.builder().name(cube).bindDimensions(Fixtures.dimensionPath(region), Fixtures.dimensionPath(measure)).build());
  });

  afterEach(async () => {
    if (!tm1) return;
    await tm1.cubes.delete(cube).catch(() => undefined);
  });

  const cell = (r: string, m: string): Map<string, string> =>
    new Map([
      [region, r],
      [measure, m],
    ]);
  const mdxOf = (r: string, m: string): string => `SELECT {[${measure}].[${measure}].[${m}]} ON 0, {[${region}].[${region}].[${r}]} ON 1 FROM [${cube}]`;
  const number = (r: string, m: string): Promise<number> => Fixtures.number(tm1, mdxOf(r, m));
  const read = (r: string, m: string): Promise<unknown> => Fixtures.value(tm1, mdxOf(r, m));
  const write = (r: string, m: string, value: unknown): CubeUpdatePatch =>
    CubeUpdatePatch.builder()
      .bindTuple(...Tuples.of(cell(r, m)))
      .value(value)
      .build();
  const paths = (tuple: Map<string, string>): string[] => Tuples.of(tuple).map((p) => p.toString());

  tm1Test("writes numbers, text and nulls cell by cell", async () => {
    await tm1.cells.update(cube, cell("North", "Amount"), 5);
    await tm1.cells.update(cube, cell("South", "Amount"), 7.5);
    await tm1.cells.update(cube, cell("North", "Note"), "hello");
    expect(await number("North", "Amount")).toBe(5);
    expect(await number("Total", "Amount"), "consolidated").toBe(12.5);
    expect(await read("North", "Note")).toBe("hello");

    await tm1.cells.update(cube, cell("North", "Amount"), null);
    expect(await number("North", "Amount"), "null clears").toBe(0);
  });

  tm1Test("needs a value in every update, and doesn't copy from a reference", async () => {
    await tm1.cells.update(cube, cell("North", "Amount"), 40);
    const withReference = (): ReturnType<typeof CubeUpdatePatch.builder> =>
      CubeUpdatePatch.builder()
        .bindTuple(...Tuples.of(cell("South", "Amount")))
        .bindReferenceCube(ODataPath.of("Cubes").key(cube))
        .bindReferenceCell(...Tuples.of(cell("North", "Amount")));
    const refused = await tm1.cells.updateCells(cube, [withReference().build()]).catch((e: unknown) => e);
    expect(refused).toBeInstanceOf(Tm1BadRequestException);
    expect((refused as Error).message).toContain("Value");
    await tm1.cells.updateCells(cube, [withReference().value(5).build()]);
    expect(await number("South", "Amount"), "the value, not the reference's 40").toBe(5);
  });

  tm1Test("writes only the first of several cells, and refuses consolidations", async () => {
    // Through the raw session: the service only offers what TM1 does
    const body = { Cells: [{ "Tuple@odata.bind": paths(cell("North", "Amount")) }, { "Tuple@odata.bind": paths(cell("South", "Amount")) }], Value: 3 };
    await tm1.session.execute(Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"), body));
    expect(await number("North", "Amount")).toBe(3);
    expect(await number("South", "Amount"), "only the first cell is written").toBe(0);

    const refused = await tm1.cells.update(cube, cell("Total", "Amount"), 100).catch((e: unknown) => e);
    expect(refused, "no spread to a consolidation").toBeInstanceOf(Tm1BadRequestException);
    expect((refused as Error).message).toContain("Consolidated");
  });

  tm1Test("writes many cells in one request", async () => {
    await tm1.cells.updateCells(cube, [write("North", "Amount", 10), write("South", "Amount", 20), write("North", "Note", "n")]);
    expect(await number("Total", "Amount")).toBe(30);
    expect(await read("North", "Note")).toBe("n");
    await tm1.cells.updateCells(cube, [write("North", "Amount", 1), write("South", "Amount", 2)], UpdateOrder.ByLevel);
    expect(await number("Total", "Amount")).toBe(3);
  });

  tm1Test("writes through a cellset and saves it as a view", async () => {
    const mdx = `SELECT {[${measure}].[${measure}].[Amount]} ON 0, {[${region}].[${region}].[North], [${region}].[${region}].[South]} ON 1 FROM [${cube}]`;
    const cellset = await tm1.cellsets.execute(mdx, ODataQuery.none());
    const cs = cellset.ID!;
    try {
      await tm1.cellsets.update(cs, 0, 4);
      expect(await number("North", "Amount")).toBe(4);
      await tm1.cellsets.update(cs, 1, 6);
      expect(await number("Total", "Amount")).toBe(10);
      await tm1.session.execute(Tm1Request.post(ODataPath.of("Cellsets").key(cs).action("tm1.Update"), { BeginOrdinal: 0, EndOrdinal: 1, Value: 9 }));
      expect(await number("Total", "Amount"), "a range writes only its first cell").toBe(15);
      await tm1.cellsets.updateCells(cs, [CellsetUpdatePatch.builder().ordinal(0).value(1).build(), CellsetUpdatePatch.builder().ordinal(1).value(2).build()]);
      expect(await number("Total", "Amount")).toBe(3);

      expect((await tm1.cellsets.saveViewAs(cs, "Saved")).Name).toBe("Saved");
      expect((await tm1.views.get(cube, ViewScope.Public, "Saved")).Name).toBe("Saved");
      expect((await tm1.cellsets.saveViewAs(cs, "Mine", false, true)).Name).toBe("Mine");
      expect((await tm1.views.get(cube, ViewScope.Private, "Mine")).Name).toBe("Mine");
    } finally {
      await tm1.cellsets.delete(cs);
    }
  });
});

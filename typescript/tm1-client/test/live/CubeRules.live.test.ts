// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  CheckFeedersSelection,
  CubeCreate,
  CubePatch,
  DimensionCreate,
  EdgeCreate,
  ElementCreate,
  ElementType,
  HierarchyCreate,
  ODataPath,
  ODataQuery,
  Tm1Request,
  type Tm1Client,
} from "../../src/index.js";
import { Tm1Operations } from "../../src/generated/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { lacksParameter } from "./server-assumptions.js";

const RULES = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\nFEEDERS;\n['Amount'] => ['Double'];\n";
const UNFED_RULES = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\n";

const element = (name: string, type: ElementType): ElementCreate => ElementCreate.builder().name(name).type(type).build();
const edge = (parent: string, component: string): EdgeCreate => EdgeCreate.builder().parentName(parent).componentName(component).weight(1.0).build();
const elementPath = (dimension: string, name: string): string =>
  ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension).nav("Elements").key(name).toString();

/** Rule checks and traces against a real TM1, on Region (Total = North + South) by Measure (Amount,
 * Double = Amount * 2): the Java client's `CubeRulesTm1Test`. */
describe("Cube rules on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const cube = Live.name();
  const region = cube + "-region";
  const measure = cube + "-measure";

  const tuple = (regionElement: string, measureElement: string): Map<string, string> =>
    new Map([
      [region, regionElement],
      [measure, measureElement],
    ]);
  const tupleNames = (elements: { Name?: string | null }[] | null | undefined): (string | null | undefined)[] => (elements ?? []).map((e) => e.Name);

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await tm1.dimensions.create(
      DimensionCreate.builder()
        .name(region)
        .hierarchies([
          HierarchyCreate.builder()
            .name(region)
            .elements([element("Total", ElementType.Consolidated), element("North", ElementType.Numeric), element("South", ElementType.Numeric)])
            .edges([edge("Total", "North"), edge("Total", "South")])
            .build(),
        ])
        .build(),
    );
    await tm1.dimensions.create(
      DimensionCreate.builder()
        .name(measure)
        .hierarchies([
          HierarchyCreate.builder()
            .name(measure)
            .elements([element("Amount", ElementType.Numeric), element("Double", ElementType.Numeric)])
            .build(),
        ])
        .build(),
    );
    await tm1.cubes.create(
      CubeCreate.builder().name(cube).rules(RULES).bindDimensions(ODataPath.of("Dimensions").key(region), ODataPath.of("Dimensions").key(measure)).build(),
    );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.cubes.delete(cube);
    await tm1.dimensions.delete(region);
    await tm1.dimensions.delete(measure);
    await tm1.close();
  });

  tm1Test("checks saved and proposed rules", async () => {
    expect(await tm1.cubes.checkRules(cube).toArray()).toEqual([]);
    const errors = await tm1.cubes.checkRules(cube, "SKIPCHECK;\n['Double'] = N: ['Amount'] *;\n").toArray();
    expect(errors).not.toEqual([]);
    for (const e of errors) {
      expect(e.LineNumber).toBe(2);
      expect(e.Message?.trim()).toBeTruthy();
    }
    expect((await tm1.cubes.get(cube)).Rules, "checking doesn't save").toBe(RULES);
  });

  tm1Test("traces a calculation and feeders", async () => {
    const calculation = await tm1.cubes.traceCellCalculation(cube, tuple("North", "Double"), ODataQuery.none().expand("Tuple").expand("Cube"));
    expect((calculation.Statements ?? []).some((s) => s.includes("['Amount'] * 2"))).toBe(true);
    expect(calculation.Cube?.Name).toBe(cube);
    expect(tupleNames(calculation.Tuple)).toEqual(["North", "Double"]);

    const feeders = await tm1.cubes.traceFeeders(cube, tuple("North", "Amount"), ODataQuery.none().expand("FedCells/Tuple"));
    expect((feeders.FedCells ?? []).map((c) => tupleNames(c.Tuple))).toContainEqual(["North", "Double"]);
  });

  tm1Test("checks feeders under a consolidation", async (context) => {
    // Both needs Selection, which servers before schema 11.8.32 don't have
    const why = await lacksParameter(tm1, Tm1Operations.CubeCheckFeeders, "Selection");
    if (why) return context.skip(why);
    // A value to feed from, written through the raw session as Java's test does
    await tm1.session.execute(
      Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"), {
        Cells: [{ "Tuple@odata.bind": [elementPath(region, "North"), elementPath(measure, "Amount")] }],
        Value: "5",
      }),
    );
    const cells = await tm1.cubes.checkFeeders(cube, tuple("Total", "Double"), CheckFeedersSelection.Both, ODataQuery.none().expand("Tuple")).toArray();
    expect(cells.filter((c) => c.Fed === true).map((c) => tupleNames(c.Tuple))).toContainEqual(["North", "Double"]);
  });

  tm1Test("lists unfed cells on every server", async () => {
    // Unfed is what servers without Selection (before schema 11.8.32) list, so it works on every
    // server: there it's sent without Selection. South written after the feeder is taken out makes
    // South/Double a cell the rules calculate and nothing feeds (a cell fed once stays fed, so not
    // North)
    await tm1.cubes.update(cube, CubePatch.builder().rules(UNFED_RULES).build());
    try {
      await tm1.session.execute(
        Tm1Request.post(ODataPath.of("Cubes").key(cube).action("tm1.Update"), {
          Cells: [{ "Tuple@odata.bind": [elementPath(region, "South"), elementPath(measure, "Amount")] }],
          Value: "5",
        }),
      );
      const cells = await tm1.cubes.checkFeeders(cube, tuple("Total", "Double"), CheckFeedersSelection.Unfed, ODataQuery.none().expand("Tuple")).toArray();
      expect(cells.every((c) => c.Fed !== true)).toBe(true);
      expect(cells.map((c) => tupleNames(c.Tuple))).toContainEqual(["South", "Double"]);
    } finally {
      await tm1.cubes.update(cube, CubePatch.builder().rules(RULES).build());
    }
  });
});

// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  CubePatch,
  Drillthrough,
  NativeViewPatch,
  ODataPath,
  ODataQuery,
  Tm1Request,
  ViewAxisSelectionPatch,
  ViewScope,
  typeOf,
  type Tm1Client,
} from "../../src/index.js";
import { Fixtures } from "./data-fixtures.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** Cube drillthrough against a real TM1: a cube with a view "Detail", a `}Drill_Detail…` process
 * returning it, and rules naming it: the Java client's `DrillthroughTm1Test`. */
describe("DrillthroughService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const cube = Live.name();
  const suffix = cube.slice(-8);
  const process = "}Drill_Detail" + suffix;
  const dimensions = [cube + "_a", cube + "_b"];
  const axis = (d: string): ViewAxisSelectionPatch => ViewAxisSelectionPatch.builder().bindSubset(Fixtures.subset(d)).build();
  const parameter = (name: string): Record<string, unknown> => ({ Name: name, Value: "", Type: "String" });

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    for (const d of dimensions) {
      await Fixtures.dimension(tm1, d, ["X", "Y"]);
      await Fixtures.subsetAll(tm1, d);
    }
    await tm1.cubes.create(
      CubePatch.builder()
        .name(cube)
        .bindDimensions(...dimensions.map((d) => Fixtures.dimensionPath(d)))
        .drillthroughRules(`[] = S: '${process.slice("}Drill_".length)}';\n`)
        .build(),
    );
    await tm1.views.create(
      cube,
      ViewScope.Public,
      NativeViewPatch.builder()
        .name("Detail")
        .columns([axis(dimensions[1]!)])
        .rows([axis(dimensions[0]!)])
        .build(),
    );
    // Processes through the session until ProcessService is ported
    await tm1.session.execute(
      Tm1Request.post(ODataPath.of("Processes"), {
        Name: process,
        Parameters: [parameter("pCube"), parameter("p0"), parameter("p1")],
        EpilogProcedure: `ReturnViewHandle('${cube}', 'Detail');\n`,
      }),
    );
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process))).catch(() => undefined);
    await tm1.cubes.delete(cube).catch(() => undefined);
    for (const d of dimensions) await Fixtures.deleteDimension(tm1, d);
    await tm1.close();
  });

  tm1Test("runs a drillthrough process directly", async () => {
    const cellset = await tm1.drillthrough.executeProcess(
      process,
      [],
      ODataQuery.none().expand("Cells", (c) => c.select("Ordinal")),
    );
    try {
      expect(cellset.Cells, "the Detail view's 2 x 2 cells").toHaveLength(4);
    } finally {
      await tm1.cellsets.delete(cellset.ID!);
    }
  });

  tm1Test("lists and runs a cell's drillthrough", async () => {
    const [a, b] = dimensions as [string, string];
    const source = await tm1.cellsets.execute(`SELECT {[${b}].[${b}].[X]} ON 0, {[${a}].[${a}].[X]} ON 1 FROM [${cube}]`, ODataQuery.none());
    try {
      const scripts = await tm1.drillthrough.scripts(source.ID!, 0).toArray();
      expect(scripts).toHaveLength(1);
      expect(typeOf(Drillthrough, scripts[0]!)).toBe("CubeDrillthrough");
      const detail = await tm1.drillthrough.execute(
        source.ID!,
        0,
        scripts[0]!.Name!,
        ODataQuery.none().expand("Cells", (c) => c.select("Ordinal")),
      );
      try {
        expect(detail.Cells).toHaveLength(4);
      } finally {
        await tm1.cellsets.delete(detail.ID!);
      }
    } finally {
      await tm1.cellsets.delete(source.ID!);
    }
  });

  // Relational drillthrough reads an ODBC data source, which the TM1 test container can't host
  // (disabled in Java too)
  tm1Test.skip("runs relational drillthrough", async () => {
    expect(await tm1.drillthrough.executeRelationalProcess("}Drill_Relational", []).toArray()).toBeDefined();
    const rowset = (await tm1.drillthrough.executeRelationalProcessWithRowset("}Drill_Relational", [])).ID!;
    expect(await tm1.drillthrough.rows(rowset).toArray()).toBeDefined();
    await tm1.drillthrough.deleteRowset(rowset);
  });
});

import { afterAll, beforeAll, describe, expect } from "vitest";
import { Cube, Dimension, ODataQuery, Tm1BadRequestException, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** MDX statements, and the model and control cube and dimension functions, against a real TM1: the
 * Java client's `MdxAndModelTm1Test`. */
describe("MDX statements and model objects on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("runs an MDX statement, but not CREATE SET", async () => {
    await tm1.cellsets.executeStatement("SELECT {} ON 0 FROM [}ClientGroups]");
    await expect(tm1.cellsets.executeStatement("CREATE SET [}Clients].[Probe] AS {TM1SUBSETALL([}Clients].[}Clients])}")).rejects.toBeInstanceOf(
      Tm1BadRequestException,
    );
  });

  tm1Test("separates model and control objects", async () => {
    const names = async (all: { toArray(): Promise<{ Name?: string | null }[]> }): Promise<string[]> => (await all.toArray()).map((o) => o.Name ?? "");
    const control = (n: string): boolean => n.startsWith("}");

    const model = await names(tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name)));
    const controlCubes = await names(tm1.cubes.controlCubes(ODataQuery.none().select(Cube.Fields.Name)));
    expect(model.filter(control)).toEqual([]);
    expect(controlCubes).not.toEqual([]);
    expect(controlCubes.filter((n) => !control(n))).toEqual([]);
    expect(controlCubes).toContain("}ClientGroups");

    const modelDims = await names(tm1.dimensions.modelDimensions(ODataQuery.none().select(Dimension.Fields.Name)));
    const controlDims = await names(tm1.dimensions.controlDimensions(ODataQuery.none().select(Dimension.Fields.Name)));
    expect(modelDims.filter(control)).toEqual([]);
    expect(controlDims).not.toEqual([]);
    expect(controlDims.filter((n) => !control(n))).toEqual([]);
    expect(controlDims).toContain("}Clients");
  });
});

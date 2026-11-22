import { afterAll, beforeAll, describe, expect } from "vitest";
import { ODataQuery, Tm1NotFoundException, type Tm1Client } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** MemberSetService against a real TM1: the member sets test of the Java client's
 * `MdxAndModelTm1Test` (the rest is CellsetService's and CubeService's). */
describe("MemberSetService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("keeps a set expression's result by ordinal, but lists none", async () => {
    const set = await tm1.memberSets.execute("{TM1SUBSETALL([}Clients].[}Clients])}", ODataQuery.none().expand("Tuples", (t) => t.expand("Members", (m) => m.select("Name"))));
    expect((set.Tuples ?? []).map((t) => (t.Members ?? []).map((m) => m.Name))).toContainEqual(["Admin"]);
    const ordinal = set.Ordinal!;
    expect((await tm1.memberSets.get(ordinal)).Cardinality).toBe(set.Cardinality);
    expect(await tm1.memberSets.list().toArray(), "TM1 lists none").toEqual([]);

    await tm1.memberSets.delete(ordinal);
    await expect(tm1.memberSets.get(ordinal)).rejects.toBeInstanceOf(Tm1NotFoundException);
  });
});

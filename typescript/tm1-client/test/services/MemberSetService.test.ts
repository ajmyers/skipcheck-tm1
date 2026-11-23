// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** MemberSetService against a fake TM1: MDX set expressions and the member sets they keep (the .NET
 * client's MemberSetServiceTests; the Java client tests it only against TM1). */
describe("MemberSetService", () => {
  it("executes a set expression", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => ({ Ordinal: 3, Cardinality: 2 });
    const set = await tm1.client().memberSets.execute("{TM1SUBSETALL([Region].[Region])}", ODataQuery.none().expand("Tuples"));
    expect([set.Ordinal, set.Cardinality]).toEqual([3, 2]);
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "ExecuteMDXSetExpression?$expand=Tuples"]);
    expect(tm1.last.text).toBe('{"MDX":"{TM1SUBSETALL([Region].[Region])}"}');
  });

  it("lists, gets and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Ordinal: 3 });
    const sets = await client.memberSets.list(ODataQuery.none().top(1)).toArray();
    expect(sets.map((s) => s.Ordinal)).toEqual([3]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET MemberSets?$top=1");

    tm1.respond = () => ({ Ordinal: 3 });
    expect((await client.memberSets.get(3, ODataQuery.none().select("Cardinality"))).Ordinal).toBe(3);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET MemberSets(3)?$select=Cardinality");

    tm1.respond = () => null;
    await client.memberSets.delete(3);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("DELETE MemberSets(3)");
  });
});

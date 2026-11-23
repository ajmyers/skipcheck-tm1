// SPDX-License-Identifier: Apache-2.0

import { CellsetAxis, Member, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for MemberSetService, quoted in its documentation.

export async function names(tm1: Tm1Client): Promise<string[]> {
  // @start region = names
  // The names of Region's leaves, with the member set deleted once they're read
  const withNames = ODataQuery.none().expand(CellsetAxis.Fields.Tuples, (t) => t.expand("Members", (m) => m.select(Member.Fields.Name)));
  const set = await tm1.memberSets.execute("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}", withNames);
  try {
    return (set.Tuples ?? []).map((tuple) => tuple.Members![0]!.Name!);
  } finally {
    await tm1.memberSets.delete(set.Ordinal!);
  }
  // @end
}

export async function keep(tm1: Tm1Client): Promise<number> {
  // @start region = keep
  // Keep a large set on the server and read it later by its ordinal; TM1 doesn't list
  // member sets, so hold on to the ordinal, and delete the set when done
  const set = await tm1.memberSets.execute("{TM1SUBSETALL([Product].[Product])}");
  return set.Ordinal!;
  // @end
}

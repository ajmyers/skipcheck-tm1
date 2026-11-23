// SPDX-License-Identifier: Apache-2.0

import { Member, ODataQuery, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for MemberService, quoted in its documentation.

export async function uniqueName(tm1: Tm1Client): Promise<string> {
  // @start region = unique-name
  // A member's unique name, such as [Region].[Region].[North], for use in MDX
  const north = await tm1.members.get("Region", "Region", "North", ODataQuery.none().select(Member.Fields.UniqueName));
  return north.UniqueName!;
  // @end
}

export async function children(tm1: Tm1Client): Promise<string[]> {
  // @start region = children
  // The children of Total, in order
  const members = await tm1.members.children("Region", "Region", "Total", ODataQuery.none().select(Member.Fields.Name)).toArray();
  return members.map((member) => member.Name!);
  // @end
}

export async function parent(tm1: Tm1Client): Promise<string> {
  // @start region = parent
  // The parent of North (the first, if it has several); a root has none
  const parent = await tm1.members.parent("Region", "Region", "North", ODataQuery.none().select(Member.Fields.Name));
  return parent?.Name ?? "(a root)";
  // @end
}

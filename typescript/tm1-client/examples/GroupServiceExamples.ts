// SPDX-License-Identifier: Apache-2.0

import { Group, ODataQuery, User, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for GroupService, quoted in its documentation.

export async function members(tm1: Tm1Client): Promise<string[]> {
  // @start region = members
  const users = await tm1.groups.users("Planners", ODataQuery.none().select(User.Fields.Name)).toArray();
  return users.map((user) => user.Name!);
  // @end
}

export async function createAndAdd(tm1: Tm1Client): Promise<void> {
  // @start region = create
  // A new group, then alice added to it, from her side: her groups plus this one
  await tm1.groups.create("Planners");
  const groups = await tm1.users.groups("alice", ODataQuery.none().select(Group.Fields.Name)).toArray();
  await tm1.users.setGroups("alice", [...groups.map((group) => group.Name!), "Planners"]);
  // @end
}

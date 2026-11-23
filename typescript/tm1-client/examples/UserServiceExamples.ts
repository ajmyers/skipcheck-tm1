// SPDX-License-Identifier: Apache-2.0

import { Group, ODataPath, ODataQuery, UserPatch, type Tm1Client, type User } from "@skipcheck/tm1-client";

// Examples for UserService, quoted in its documentation.

export function create(tm1: Tm1Client): Promise<User> {
  // @start region = create
  // A user in two existing groups, bound by their paths
  const groups = ODataPath.of("Groups");
  const alice = UserPatch.builder().name("alice").password("initial-password").bindGroups(groups.key("Planners"), groups.key("Sales")).build();
  return tm1.users.create(alice);
  // @end
}

export async function leaveGroup(tm1: Tm1Client): Promise<void> {
  // @start region = leave-group
  // Groups are set whole: keep every group but Planners
  const groups = await tm1.users.groups("alice", ODataQuery.none().select(Group.Fields.Name)).toArray();
  const rest = groups.map((group) => group.Name!).filter((name) => name !== "Planners");
  await tm1.users.setGroups("alice", rest);
  // @end
}

export async function lockOut(tm1: Tm1Client): Promise<void> {
  // @start region = lock-out
  // Stop the user logging in, then end the sessions they already have
  await tm1.users.update("alice", UserPatch.builder().enabled(false).build());
  await tm1.users.disconnect("alice");
  // @end
}

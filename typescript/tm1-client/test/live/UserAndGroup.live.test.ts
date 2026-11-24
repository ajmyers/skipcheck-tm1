// SPDX-License-Identifier: Apache-2.0

import { afterEach, beforeEach, describe, expect } from "vitest";
import { ODataPath, type Tm1Client, type Tm1ClientBuilder, Tm1NotFoundException, Tm1Request, UserCreate, UserPatch, UserType } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** A client beside `Live.client()`, of the same TM1 with the same settings: as another session, or
 * as `user` with TM1's own security. */
async function otherClient(configure?: (b: Tm1ClientBuilder) => Tm1ClientBuilder, user?: string, password?: string): Promise<Tm1Client> {
  const builder = await Live.builder(user, password);
  return (configure?.(builder) ?? builder).build();
}

/** UserService and GroupService against a real TM1, on a user and two groups of their own: the Java
 * client's `UserAndGroupTm1Test`. */
describe("UserService and GroupService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  let user: string;
  let g1: string;
  let g2: string;

  beforeEach(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    user = Live.name();
    g1 = Live.name();
    g2 = Live.name();
    await tm1.groups.create(g1);
    await tm1.groups.create(g2);
    await tm1.users.create(UserCreate.builder().name(user).password("pw").bindGroups(ODataPath.of("Groups").key(g1)).build());
  });

  afterEach(async () => {
    if (!tm1) return;
    await tm1.users.delete(user).catch(() => undefined);
    await tm1.groups.delete(g1).catch(() => undefined);
    await tm1.groups.delete(g2).catch(() => undefined);
    await tm1.close();
  });

  const groups = async (): Promise<(string | undefined)[]> => (await tm1.users.groups(user).toArray()).map((g) => g.Name);

  tm1Test("creates, reads, updates and deletes a user", async () => {
    const read = await tm1.users.get(user);
    expect(read.Type).toBe(UserType.User);
    // Names match ignoring case and spaces
    expect((await tm1.users.get(user.toUpperCase().replaceAll("_", " _ "))).Name).toBeDefined();
    expect(read["@odata.etag"], "TM1 sends none for a user").toBeUndefined();
    await tm1.users.update(user, UserPatch.builder().friendlyName("Friend").build());
    expect((await tm1.users.get(user)).FriendlyName).toBe("Friend");
    expect((await tm1.users.activeUser()).Name?.toLowerCase()).toBe((await Live.target()).user.toLowerCase());

    await tm1.users.delete(user);
    await expect(tm1.users.get(user)).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("sets groups whole, and lists members from both sides", async () => {
    expect(await groups()).toEqual([g1]);
    await tm1.users.setGroups(user, [g1, g2]);
    expect((await groups()).sort()).toEqual([g1, g2].sort());
    await tm1.users.setGroups(user, [g2]);
    expect(await groups(), "replaced").toEqual([g2]);
    expect((await tm1.groups.users(g2).toArray()).map((u) => u.Name)).toEqual([user]);
    expect((await tm1.groups.get(g1)).Name, "left, not deleted").toBe(g1);

    expect(() => tm1.users.update(user, UserPatch.builder().bindGroups(ODataPath.of("Groups").key(g1)).build())).toThrow(/setGroups/);
  });

  tm1Test("deletes the group itself on a reference delete", async () => {
    // The call UserService never makes: OData's "remove the link" deletes the group
    await tm1.session.execute(Tm1Request.delete(ODataPath.of("Users").key(user).nav("Groups").key(g1).ref()));
    await expect(tm1.groups.get(g1)).rejects.toBeInstanceOf(Tm1NotFoundException);
  });

  tm1Test("disconnects a user", async () => {
    const asUser = await otherClient(undefined, user, "pw");
    try {
      await asUser.connect();
      expect((await tm1.users.get(user)).IsActive).toBe(true);
      await tm1.users.disconnect(user);
      expect((await tm1.users.get(user)).IsActive).toBe(false);
    } finally {
      await asUser.close().catch(() => undefined);
    }
  });
});

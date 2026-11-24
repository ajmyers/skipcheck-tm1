// SPDX-License-Identifier: Apache-2.0

import { describe, expect } from "vitest";
import { SandboxCreate, Tm1Client } from "../../src/index.js";
import { liveReason, tm1Test } from "./live.js";
import { twoByTwo } from "./sandbox-fixture.js";

/** Sandboxes and change sets against a real TM1, on a cube of two dimensions with cells X and Y: the
 * Java client's `SandboxAndChangeSetTm1Test`. */
describe("SandboxService and ChangeSetService on TM1" + liveReason, () => {
  const { tm1, cube: cubeName, cell, value } = twoByTwo();

  tm1Test("keeps sandbox writes apart until published", async () => {
    const client = tm1();
    const cube = cubeName();
    await client.sandboxes.create(SandboxCreate.builder().name("S").build());
    expect((await client.sandboxes.list().toArray()).map((s) => s.Name)).toContain("S");
    await client.withSandbox("S").cells.update(cube, cell("X"), 5);
    expect(await value(client.withSandbox("S"), "X")).toBe(5);
    expect(await value(client, "X"), "base data untouched").toBe(0);

    await client.sandboxes.publish("S");
    expect(await value(client, "X")).toBe(5);
  });

  tm1Test("merges, discards, loads and unloads", async () => {
    const client = tm1();
    const cube = cubeName();
    await client.sandboxes.create(SandboxCreate.builder().name("S").build());
    await client.sandboxes.create(SandboxCreate.builder().name("T").build());
    await client.withSandbox("S").cells.update(cube, cell("X"), 7);
    await client.sandboxes.merge("S", "T");
    expect(await value(client.withSandbox("T"), "X")).toBe(7);

    await client.sandboxes.discardChanges("S");
    expect(await value(client.withSandbox("S"), "X")).toBe(0);
    await client.sandboxes.unload("T");
    await client.sandboxes.load("T");
    expect((await client.sandboxes.get("T")).IsLoaded).toBe(true);

    await client.sandboxes.delete("T");
    expect((await client.sandboxes.list().toArray()).map((s) => s.Name)).not.toContain("T");
  });

  tm1Test("undoes the writes of a change set only", async () => {
    const client = tm1();
    let id = "";
    await client.changeSets
      .within((session) => {
        id = session.changeSet()!;
        return Tm1Client.of(session).cells.update(cubeName(), cell("X"), 5);
      })
      .toArray();
    expect(id).toBeTruthy();
    await client.cells.update(cubeName(), cell("Y"), 7);
    expect(await value(client, "X")).toBe(5);

    await client.changeSets.undo(id);
    expect(await value(client, "X"), "in the change set: reverted").toBe(0);
    expect(await value(client, "Y"), "outside it: kept").toBe(7);
  });

  tm1Test("begins and ends a change set by hand", async () => {
    const client = tm1();
    const id = await client.changeSets.begin();
    expect(id.trim()).not.toBe("");
    await client.withChangeSet(id).cells.update(cubeName(), cell("Y"), 3);
    expect(typeof (await client.changeSets.end(id))).toBe("number");
    await client.changeSets.undo(id);
    expect(await value(client, "Y")).toBe(0);
  });
});

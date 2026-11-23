import { describe, expect } from "vitest";
import { SandboxPatch } from "../../src/index.js";
import { liveReason, tm1Test } from "./live.js";
import { twoByTwo } from "./sandbox-fixture.js";

/** Sandboxes against a real TM1, on a cube of two dimensions with cells X and Y: the Java client's
 * `SandboxAndChangeSetTm1Test`, its sandbox half. */
describe("SandboxService on TM1" + liveReason, () => {
  const { tm1, cube: cubeName, cell, value } = twoByTwo();

  tm1Test("keeps sandbox writes apart until published", async () => {
    const client = tm1();
    const cube = cubeName();
    await client.sandboxes.create(SandboxPatch.builder().name("S").build());
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
    await client.sandboxes.create(SandboxPatch.builder().name("S").build());
    await client.sandboxes.create(SandboxPatch.builder().name("T").build());
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
});

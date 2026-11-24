// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ODataQuery, SandboxCreate, SandboxPatch } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** SandboxService against a fake TM1: the sandboxes themselves and their actions, as the Java
 * client's SandboxService sends them (the .NET client's SandboxServiceTests). */
describe("SandboxService", () => {
  it("lists, gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Name: "What if", IsLoaded: true });
    const sandboxes = await client.sandboxes.list(ODataQuery.none().select("Name")).toArray();
    expect(sandboxes.map((s) => [s.Name, s.IsLoaded])).toEqual([["What if", true]]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Sandboxes?$select=Name");

    tm1.respond = () => ({ Name: "What if" });
    expect((await client.sandboxes.get("What if")).Name).toBe("What if");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Sandboxes('What%20if')");
    expect((await client.sandboxes.create(SandboxCreate.builder().name("What if").build())).Name).toBe("What if");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Sandboxes", '{"Name":"What if"}']);

    tm1.respond = () => null;
    await client.sandboxes.update("What if", SandboxPatch.builder().includeInSandboxDimension(false).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Sandboxes('What%20if')", '{"IncludeInSandboxDimension":false}']);
    await client.sandboxes.delete("What if");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("DELETE Sandboxes('What%20if')");
    const sent = tm1.requests.length;
    expect(() => client.sandboxes.create(null as unknown as SandboxCreate)).toThrow(TypeError);
    expect(tm1.requests.length).toBe(sent);
  });

  it("runs the sandbox's actions", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => null;
    for (const [call, action] of [
      [() => client.sandboxes.load("s"), "Load"],
      [() => client.sandboxes.unload("s"), "Unload"],
      [() => client.sandboxes.discardChanges("s"), "DiscardChanges"],
      [() => client.sandboxes.publish("s"), "Publish"],
    ] as const) {
      await call();
      expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `Sandboxes('s')/tm1.${action}`, "{}"]);
    }
  });

  it("publishes and merges with options", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => null;
    await client.sandboxes.publish("s", "UsingSource", true, false);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual([
      "POST",
      "Sandboxes('s')/tm1.Publish",
      '{"ConflictResolution":"UsingSource","AllowQueueing":true,"CleanAfter":false}',
    ]);

    await client.sandboxes.merge("s", "t");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Sandboxes('s')/tm1.Merge", `{"Target@odata.bind":"Sandboxes('t')"}`]);
    await client.sandboxes.merge("s", "t", "UsingTarget", false, true);
    expect(tm1.last.text).toBe(`{"Target@odata.bind":"Sandboxes('t')","ConflictResolution":"UsingTarget","AllowQueueing":false,"CleanAfter":true}`);
    expect(() => client.sandboxes.publish("s", "Unknown", true, true)).toThrow(TypeError);
  });

  it("reads and writes in a sandbox through its view", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    await tm1.client().withSandbox("What if").cells.update("Sales", { Region: "North" }, 1);
    expect(tm1.last.target).toBe("Cubes('Sales')/tm1.Update?!sandbox=What%20if");
  });
});

// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { ChoreCreate, ChorePatch, ChoreTaskParameterPatch, ChoreTaskPatch, ODataPath, ODataQuery, Tm1PreconditionFailedException } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** ChoreService against a fake TM1: each method sends what the Java client's does (the .NET
 * client's ChoreServiceTests; the Java client has live tests only). */
describe("ChoreService", () => {
  it("lists, gets, creates, updates and deletes", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Name: "Nightly Load" });
    const client = tm1.client();
    expect((await client.chores.list(ODataQuery.none().top(1)).toArray()).map((c) => c.Name)).toEqual(["Nightly Load"]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Chores?$top=1");

    tm1.respond = () => ({ Name: "Nightly Load", "@odata.etag": 'W/"3"' });
    expect((await client.chores.get("Nightly Load", ODataQuery.none().expand("Tasks")))["@odata.etag"]).toBe('W/"3"');
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Chores('Nightly%20Load')?$expand=Tasks");

    const task = ChoreTaskPatch.builder()
      .bindProcess(ODataPath.of("Processes").key("Load Sales"))
      .parameters([ChoreTaskParameterPatch.builder().name("pYear").value(2026).build()])
      .build();
    expect((await client.chores.create(ChoreCreate.builder().name("Nightly Load").active(false).tasks([task]).build())).Name).toBe("Nightly Load");
    expect([tm1.last.method, tm1.last.target]).toEqual(["POST", "Chores"]);
    expect(tm1.last.text).toBe(
      '{"Name":"Nightly Load","Active":false,"Tasks":[{"Process@odata.bind":"Processes(\'Load%20Sales\')","Parameters":[{"Name":"pYear","Value":2026}]}]}',
    );

    tm1.respond = () => null;
    await client.chores.update("Nightly Load", ChorePatch.builder().active(true).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Chores('Nightly%20Load')", '{"Active":true}']);
    expect(tm1.last.headers["if-match"]).toBeUndefined();
    await client.chores.update("Nightly Load", ChorePatch.builder().active(true).build(), 'W/"3"');
    expect(tm1.last.headers["if-match"]).toBe('W/"3"');
    await client.chores.delete("Nightly Load");
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Chores('Nightly%20Load')"]);

    tm1.respond = () => FakeTm1.reply(412, { error: { code: "278", message: "stale" } });
    await expect(client.chores.update("c", ChorePatch.builder().active(false).build(), 'W/"0"')).rejects.toBeInstanceOf(Tm1PreconditionFailedException);
  });

  it("works with tasks by step", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ Step: 0 }, { Step: 1 });
    const client = tm1.client();
    expect((await client.chores.tasks("c", ODataQuery.none().expand("Process")).toArray()).map((t) => t.Step)).toEqual([0, 1]);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Chores('c')/Tasks?$expand=Process");

    tm1.respond = () => ({ Step: 2 });
    const added = await client.chores.addTask("c", ChoreTaskPatch.builder().bindProcess(ODataPath.of("Processes").key("p")).build());
    expect(added.Step).toBe(2);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Chores('c')/Tasks", '{"Process@odata.bind":"Processes(\'p\')"}']);

    tm1.respond = () => null;
    await client.chores.updateTask("c", 2, ChoreTaskPatch.builder().parameters([]).build());
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["PATCH", "Chores('c')/Tasks(2)", '{"Parameters":[]}']);
    await client.chores.deleteTask("c", 2);
    expect([tm1.last.method, tm1.last.target]).toEqual(["DELETE", "Chores('c')/Tasks(2)"]);
    expect(() => client.chores.deleteTask("c", 1.5)).toThrow(RangeError);

    tm1.respond = () => ({ Name: "p" });
    expect((await client.chores.taskProcess("c", 0, ODataQuery.none().select("Name"))).Name).toBe("p");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Chores('c')/Tasks(0)/Process?$select=Name");
    tm1.respond = () => ({ Name: "c" });
    expect((await client.chores.taskChore("c", 0)).Name).toBe("c");
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Chores('c')/Tasks(0)/Chore");
  });

  it("runs the chore's actions", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => null;
    const client = tm1.client();
    for (const [call, action] of [
      [() => client.chores.execute("c"), "Execute"],
      [() => client.chores.activate("c"), "Activate"],
      [() => client.chores.deactivate("c"), "Deactivate"],
    ] as const) {
      await call();
      expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", `Chores('c')/tm1.${action}`, "{}"]);
    }

    // As Java's LocalDate and LocalTime write them
    await client.chores.setServerLocalStartTime("c", "2026-01-02", "02:00");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual([
      "POST",
      "Chores('c')/tm1.SetServerLocalStartTime",
      '{"StartDate":"2026-01-02","StartTime":"02:00"}',
    ]);
    await client.chores.setServerLocalStartTime("c", "2026-01-02", "02:00:30");
    expect(tm1.last.text).toBe('{"StartDate":"2026-01-02","StartTime":"02:00:30"}');
    await client.chores.setServerLocalStartTime("c", "2026-01-02", "02:00:00");
    expect(tm1.last.text).toBe('{"StartDate":"2026-01-02","StartTime":"02:00"}');
    await client.chores.setServerLocalStartTime("c", "2026-01-02", "02:00:30.5");
    expect(tm1.last.text).toBe('{"StartDate":"2026-01-02","StartTime":"02:00:30.500"}');
    const count = tm1.requests.length;
    expect(() => client.chores.setServerLocalStartTime("c", "2026-1-2", "02:00")).toThrow(RangeError);
    expect(() => client.chores.setServerLocalStartTime("c", "2026-01-02", "2am")).toThrow(RangeError);
    expect(tm1.requests.length).toBe(count);
  });
});

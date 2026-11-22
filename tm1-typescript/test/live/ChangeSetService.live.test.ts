import { describe, expect } from "vitest";
import { Tm1Client } from "../../src/index.js";
import { liveReason, tm1Test } from "./live.js";
import { twoByTwo } from "./sandbox-fixture.js";

/** Change sets against a real TM1, on a cube of two dimensions with cells X and Y: the Java client's
 * `SandboxAndChangeSetTm1Test`, its change set half. */
describe("ChangeSetService on TM1" + liveReason, () => {
  const { tm1, cube, cell, value } = twoByTwo();

  tm1Test("undoes the writes of a change set only", async () => {
    const client = tm1();
    let id = "";
    await client.changeSets
      .within((session) => {
        id = session.changeSet()!;
        return Tm1Client.of(session).cells.update(cube(), cell("X"), 5);
      })
      .toArray();
    expect(id).toBeTruthy();
    await client.cells.update(cube(), cell("Y"), 7);
    expect(await value(client, "X")).toBe(5);

    await client.changeSets.undo(id);
    expect(await value(client, "X"), "in the change set: reverted").toBe(0);
    expect(await value(client, "Y"), "outside it: kept").toBe(7);
  });

  tm1Test("begins and ends a change set by hand", async () => {
    const client = tm1();
    const id = await client.changeSets.begin();
    expect(id.trim()).not.toBe("");
    await client.withChangeSet(id).cells.update(cube(), cell("Y"), 3);
    expect(typeof (await client.changeSets.end(id))).toBe("number");
    await client.changeSets.undo(id);
    expect(await value(client, "Y")).toBe(0);
  });
});

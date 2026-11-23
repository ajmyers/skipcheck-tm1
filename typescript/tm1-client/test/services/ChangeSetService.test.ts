// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import { Tm1Client, Tm1ServerException, type Tm1Session } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

function fake(): FakeTm1 {
  const tm1 = new FakeTm1();
  tm1.respond = (r) => (r.target === "BeginChangeSet" ? { value: "7" } : r.target === "EndChangeSet" ? { value: 2 } : null);
  return tm1;
}

const write = (session: Tm1Session, value: number): Promise<void> => Tm1Client.of(session).cells.update("Sales", { Region: "North" }, value);

/** ChangeSetService against a fake TM1: begin, end and undo, and `within`, which ends the change set
 * however the work ends (the .NET client's ChangeSetServiceTests). */
describe("ChangeSetService", () => {
  it("begins, ends and undoes", async () => {
    const tm1 = fake();
    const client = tm1.client();
    expect(await client.changeSets.begin()).toBe("7");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "BeginChangeSet", "{}"]);
    expect(await client.changeSets.end("7")).toBe(2);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "EndChangeSet", '{"ChangeSetID":"7"}']);
    await client.changeSets.undo("7");
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "UndoChangeSet", '{"ChangeSetID":"7"}']);
  });

  it("works within a change set", async () => {
    const tm1 = fake();
    const client = tm1.client();
    let id: string | undefined;
    const result = await client.changeSets
      .within(async (session) => {
        id = session.changeSet();
        await write(session, 1);
        return "done";
      })
      .toArray();
    expect(result).toEqual(["done"]);
    expect(id).toBe("7");
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"]);
    expect(tm1.last.text).toBe('{"ChangeSetID":"7"}');

    tm1.requests.length = 0;
    expect(await client.changeSets.within((session) => write(session, 2)).toArray()).toEqual([]);
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"]);
  });

  it("sends nothing until it's iterated", () => {
    const tm1 = fake();
    tm1.client().changeSets.within(async () => 1);
    expect(tm1.requests).toEqual([]);
  });

  it("ends the change set when the work fails", async () => {
    const tm1 = fake();
    const work = (): Promise<number> => {
      throw new Error("work");
    };
    await expect(tm1.client().changeSets.within(work).toArray()).rejects.toThrow("work");
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet", "EndChangeSet"]);

    tm1.requests.length = 0;
    await expect(
      tm1
        .client()
        .changeSets.within(async () => Promise.reject(new Error("later")))
        .toArray(),
    ).rejects.toThrow("later");
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet", "EndChangeSet"]);
  });

  it("does no work if the change set can't begin", async () => {
    // Java: doesNoWorkIfTheChangeSetCantBegin
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.reply(500, { error: { code: "1", message: "down" } }, { "content-type": "application/json" });
    let ran = false;
    const work = async (): Promise<string> => {
      ran = true;
      return "never";
    };
    await expect(tm1.client().changeSets.within(work).toArray()).rejects.toBeInstanceOf(Tm1ServerException);
    expect(ran).toBe(false);
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet"]);
  });

  it("streams within a change set, and ends it however the stream ends", async () => {
    const tm1 = fake();
    const client = tm1.client();
    async function* numbers(session: Tm1Session): AsyncGenerator<number> {
      await write(session, 1);
      yield* [1, 2, 3];
    }
    expect(await client.changeSets.within(numbers).toArray()).toEqual([1, 2, 3]);
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"]);

    tm1.requests.length = 0;
    for await (const n of client.changeSets.within(numbers)) {
      expect(n).toBe(1);
      break;
    }
    expect(tm1.requests.map((r) => r.target)).toEqual(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"]);
  });
});

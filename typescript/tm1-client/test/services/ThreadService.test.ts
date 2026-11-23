import { describe, expect, it } from "vitest";
import { ODataQuery, Thread } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

/** ThreadService against a fake TM1 (the .NET client's ThreadServiceTests; the Java client tests
 * ThreadService only against TM1). */
describe("ThreadService", () => {
  it("lists, gets, reads the session and cancels", async () => {
    const tm1 = new FakeTm1();
    tm1.respond = () => FakeTm1.collection({ ID: 99, Function: "POST /api/v1/Processes('Load')/tm1.ExecuteWithReturn" });
    const client = tm1.client();
    const threads = await client.threads.list(ODataQuery.none().filter(Thread.Fields.State.ne("Idle"))).toArray();
    expect(threads.map((t) => [t.ID, t.Function])).toEqual([[99, "POST /api/v1/Processes('Load')/tm1.ExecuteWithReturn"]]);
    expect(`${tm1.last.method} ${decodeURIComponent(tm1.last.target)}`).toBe("GET Threads?$filter=(State ne 'Idle')");

    tm1.respond = () => ({ ID: 99 });
    expect((await client.threads.get(99)).ID).toBe(99);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Threads(99)");
    tm1.respond = () => ({ ID: 7 });
    expect((await client.threads.session(99, ODataQuery.none().select("ID"))).ID).toBe(7);
    expect(`${tm1.last.method} ${tm1.last.target}`).toBe("GET Threads(99)/Session?$select=ID");

    tm1.respond = () => null;
    await client.threads.cancel(99);
    expect([tm1.last.method, tm1.last.target, tm1.last.text]).toEqual(["POST", "Threads(99)/tm1.CancelOperation", "{}"]);
  });
});

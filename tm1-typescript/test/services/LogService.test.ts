import { describe, expect, it } from "vitest";
import { ODataQuery, type AuditLogEntry, type MessageLogEntry } from "../../src/index.js";
import { FakeTm1 } from "../support/fake-tm1.js";

const sent = (tm1: FakeTm1): string => `${tm1.last.method} ${tm1.last.target}`;

/** LogService against a fake TM1: each method sends what the Java client's does, and the tails poll
 * the delta link, skipping what they've yielded, until stopped (the .NET client's LogServiceTests;
 * the Java client has live tests only). */
describe("LogService", () => {
  it("reads the message log", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ ID: 1, Message: "Login Success", TimeStamp: "0000-00-00T00:00Z" });
    const entries = await client.logs.messageLogEntries(ODataQuery.none().top(1)).toArray();
    expect(entries.map((e) => e.Message)).toEqual(["Login Success"]);
    expect(entries[0]!.TimeStamp).toBeNull();
    expect(sent(tm1)).toBe("GET MessageLogEntries?$top=1");
    await client.logs.messageLog(true, ODataQuery.none().top(3)).toArray();
    expect(sent(tm1)).toBe("GET MessageLog(Reverse=true)?$top=3");
    await client.logs.messageLog(false).toArray();
    expect(sent(tm1)).toBe("GET MessageLog(Reverse=false)");
  });

  it("reads the transaction and audit logs", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Cube: "Sales" });
    expect((await client.logs.transactionLogEntries().toArray()).map((e) => e.Cube)).toEqual(["Sales"]);
    expect(sent(tm1)).toBe("GET TransactionLogEntries");
    await client.logs.transactionLog(true, false, ODataQuery.none().top(5)).toArray();
    expect(sent(tm1)).toBe("GET TransactionLog(Reverse=true,IgnoreStatusMessages=false)?$top=5");
    tm1.respond = () => FakeTm1.collection();
    expect(await client.logs.auditLogEntries(ODataQuery.none().expand("AuditDetails")).toArray()).toEqual([]);
    expect(sent(tm1)).toBe("GET AuditLogEntries?$expand=AuditDetails");
  });

  it("reads error logs", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = () => FakeTm1.collection({ Filename: "a.log" });
    expect((await client.logs.errorLogFiles(ODataQuery.none().top(1)).toArray()).map((f) => f.Filename)).toEqual(["a.log"]);
    expect(sent(tm1)).toBe("GET ErrorLogFiles?$top=1");
    tm1.respond = () => ({ Filename: "a.log" });
    expect((await client.logs.errorLogFile("a.log")).Filename).toBe("a.log");
    expect(sent(tm1)).toBe("GET ErrorLogFiles('a.log')");

    // TM1 (11.8) starts the content with a byte order mark, which is dropped
    tm1.respond = () => "﻿Error: Prolog procedure line (1): ProcessError";
    expect(await client.logs.errorLogContent("a.log")).toBe("Error: Prolog procedure line (1): ProcessError");
    expect(sent(tm1)).toBe("GET ErrorLogFiles('a.log')/Content");
    expect(tm1.last.headers["accept"]).toBe("text/plain, application/json");
    tm1.respond = () => "no mark";
    expect(await client.logs.errorLogContent("a.log")).toBe("no mark");
  });

  it("tails a log, skipping what it has yielded", async () => {
    const tm1 = new FakeTm1();
    // TM1 (11.8) answers the delta link with every entry since it was made, each time
    const polls = [
      FakeTm1.collection({ Message: "a" }),
      FakeTm1.collection({ Message: "a" }),
      FakeTm1.collection({ Message: "a" }, { Message: "b" }, { Message: "c" }),
    ];
    tm1.respond = (r) =>
      r.target === "TailMessageLog()" ? { "@odata.context": "$metadata#Edm.String", value: "MessageLogEntries/!delta('0[4096]38@11!19')" } : polls.shift();
    const tail = tm1.client().logs.tailMessageLog(1);
    expect(tm1.requests).toEqual([]); // nothing until it's iterated
    const seen: MessageLogEntry[] = [];
    for await (const entry of tail) {
      seen.push(entry);
      if (seen.length === 3) break;
    }
    expect(seen.map((e) => e.Message)).toEqual(["a", "b", "c"]);
    expect(tm1.requests.filter((r) => r.target === "TailMessageLog()").length).toBe(1);
    expect(tm1.requests.map((r) => `${r.method} ${r.target}`)).toEqual([
      "GET TailMessageLog()",
      "GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')",
      "GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')",
      "GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')",
    ]);
  });

  it("tails the transaction and audit logs until aborted", async () => {
    const tm1 = new FakeTm1();
    const client = tm1.client();
    tm1.respond = (r) => (r.target.startsWith("Tail") ? { value: "TransactionLogEntries/!delta('1')" } : FakeTm1.collection({ Cube: "Sales" }));
    expect((await client.logs.tailTransactionLog(1).first())?.Cube).toBe("Sales");
    expect(tm1.requests.map((r) => r.target)).toEqual(["TailTransactionLog()", "TransactionLogEntries/!delta('1')"]);

    tm1.requests.length = 0;
    const abort = new AbortController();
    const seen: AuditLogEntry[] = [];
    const run = (async () => {
      for await (const entry of client.logs.tailAuditLog(60_000, { signal: abort.signal })) {
        seen.push(entry);
        abort.abort(new Error("stop"));
      }
    })();
    await expect(run).rejects.toThrow("stop");
    expect(seen.length).toBe(1);
    expect(tm1.requests.map((r) => r.target)).toEqual(["TailAuditLog()", "TransactionLogEntries/!delta('1')"]);
  });

  it("refuses a negative interval", () => {
    const client = new FakeTm1().client();
    expect(() => client.logs.tailMessageLog(-1)).toThrow(RangeError);
  });
});

// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import { ODataQuery, ProcessCreate, UserCreate, type MessageLogEntry, type Tm1Client, type Tm1Stream } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/** What a tail yields for `ms` milliseconds (Java's `take(Duration)`), stopping it with its signal. */
async function during<T>(ms: number, tail: (signal: AbortSignal) => Tm1Stream<T>): Promise<T[]> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(new Error("done")), ms);
  const seen: T[] = [];
  try {
    for await (const entry of tail(abort.signal)) seen.push(entry);
  } catch (e) {
    if (!abort.signal.aborted) throw e;
  } finally {
    clearTimeout(timer);
  }
  return seen;
}

/** LogService against a real TM1: the message, transaction, audit and error logs, and following
 * them. The Java client's `LogServiceTm1Test`. */
describe("LogService on TM1" + liveReason, () => {
  let tm1: Tm1Client;

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
  });

  afterAll(async () => {
    if (tm1) await tm1.close();
  });

  tm1Test("reads the message log", async () => {
    const first = await tm1.logs.messageLogEntries(ODataQuery.none().top(20)).toArray();
    expect(first.length).toBeGreaterThan(0);
    expect(
      first.some((e) => e.TimeStamp === null),
      "an entry without a time reads as null rather than failing",
    ).toBe(true);
    const again = (await tm1.logs.messageLogEntries(ODataQuery.none().top(1)).first())!;
    expect(again.Message).toBe(first[0]!.Message);
    expect(again.ID, "an ID numbers the entry within its response").not.toBe(first[0]!.ID);

    const newest = await tm1.logs.messageLog(true, ODataQuery.none().top(3)).toArray();
    expect(newest).toHaveLength(3);
    expect(Date.parse(newest[0]!.TimeStamp!)).toBeGreaterThanOrEqual(Date.parse(newest[2]!.TimeStamp!));
  });

  tm1Test("follows the message log without repeating", async () => {
    const login = async (): Promise<void> => {
      const other = await Live.client();
      try {
        await other.cubes.list(ODataQuery.none()).toArray();
      } finally {
        await other.close();
      }
    };
    const logins = (async () => {
      await sleep(1500);
      await login();
      await sleep(1500);
      await login();
    })();
    const seen = await during(6000, (signal) => tm1.logs.tailMessageLog(300, { signal }));
    await logins;
    expect(seen.filter((e: MessageLogEntry) => e.Message?.includes("Login Success")).length).toBeGreaterThanOrEqual(2);
    const distinct = new Set(seen.map((e) => `${e.TimeStamp} ${e.ThreadID} ${e.SessionID} ${e.Message}`));
    expect(distinct.size, "no entry twice, though each poll returns them all again").toBe(seen.length);
  });

  tm1Test("reads a password's hash that isn't UTF-8 as replacement characters", async () => {
    const user = Live.name();
    await tm1.users.create(UserCreate.builder().name(user).password("pw").build());
    try {
      const newest = await tm1.logs.transactionLog(true, true, ODataQuery.none().top(50)).toArray();
      // The password's hash, which TM1 sends as bytes that aren't UTF-8
      const entries = newest.filter((e) => e.Cube === "}ClientProperties" && e.Tuple?.includes("PASSWORD") && e.Tuple?.includes(user));
      expect(entries).toHaveLength(1);
      expect(String(entries[0]!.NewValue)).toContain(String.fromCharCode(0xfffd));
    } finally {
      await tm1.users.delete(user);
    }
  });

  tm1Test("reads the transaction and audit logs", async () => {
    expect((await tm1.logs.transactionLogEntries(ODataQuery.none().top(5)).toArray()).length).toBeGreaterThan(0);
    expect((await tm1.logs.transactionLog(true, true, ODataQuery.none().top(5)).toArray()).every((e) => e.StatusMessage == null)).toBe(true);
    expect(await tm1.logs.auditLogEntries(ODataQuery.none().expand("AuditDetails").top(5)).toArray(), "the audit log is off on the test server").toEqual([]);
    expect(await during(1000, (signal) => tm1.logs.tailTransactionLog(300, { signal }))).toBeDefined();
    expect(await during(1000, (signal) => tm1.logs.tailAuditLog(300, { signal }))).toEqual([]);
  });

  tm1Test("reads a process error log", async () => {
    const process = Live.name();
    await tm1.processes.create(ProcessCreate.builder().name(process).prologProcedure("ProcessError;\n").build());
    try {
      const result = await tm1.processes.executeWithReturn(process, {}, ODataQuery.none().expand("ErrorLogFile"));
      const filename = result.ErrorLogFile!.Filename!;
      expect((await tm1.logs.errorLogFiles(ODataQuery.none()).toArray()).some((f) => f.Filename === filename)).toBe(true);
      expect((await tm1.logs.errorLogFile(filename, ODataQuery.none())).Filename).toBe(filename);
      const content = await tm1.logs.errorLogContent(filename);
      expect(content.startsWith("Error:"), "without the byte order mark").toBe(true);
      expect(content).toContain("ProcessError");
    } finally {
      await tm1.processes.delete(process);
    }
  });
});

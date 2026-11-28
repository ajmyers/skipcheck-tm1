// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { describe, expect } from "vitest";
import { ODataPath, ODataQuery, Tm1Request, Tm1Session } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { sleep, createProcess, execute } from "./raw-tm1.js";
import { TM1_12 } from "./server-assumptions.js";

/** The Java client's `Tm1SessionAsyncTm1Test`: a process run asynchronously, and stopped by cancelling. */
describe("Tm1Session async on TM1" + liveReason, () => {
  /** Waits up to 30 s for a thread (v11) or job (v12) running `process` to be there, or gone. */
  const waitFor = async (s: Tm1Session, process: string, running: boolean): Promise<boolean> => {
    const v12 = (await s.version()).isAtLeast(TM1_12);
    for (let i = 0; i < 150; i++) {
      const found = v12
        ? (await s.stream(Tm1Request.list<{ Description?: string }>(ODataPath.of("Jobs"), null)).toArray()).some(
            (j) => typeof j.Description === "string" && j.Description.includes(`Processes('${process}')`),
          )
        : (await s.stream(Tm1Request.list<{ Function?: string }>(ODataPath.of("Threads"), null)).toArray()).some(
            (t) => typeof t.Function === "string" && t.Function.includes(`Processes('${process}')`),
          );
      if (found === running) return true;
      await sleep(200);
    }
    return false;
  };

  tm1Test(
    "runs a process and reads a collection through the monitor",
    async () => {
      const tm1 = await Live.client();
      const async = tm1.withAsync(200);
      const process = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
      await tm1.session.execute(createProcess(process, "x = 1;\n"));
      try {
        expect((await async.session.execute(execute(process))).ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
        expect((await async.session.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none().select("Name"))).toArray()).length).toBeGreaterThan(0);
      } finally {
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)));
        await tm1.close();
      }
    },
    60_000,
  );

  tm1Test(
    "waits before going async when requested",
    async () => {
      const tm1 = await Live.client();
      const fast = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
      const slow = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
      try {
        await tm1.session.execute(createProcess(fast, "x = 1;\n"));
        await tm1.session.execute(createProcess(slow, "Sleep(2000);\n"));
        const longWait = tm1.withAsync(200, 10_000);
        expect((await longWait.session.execute(execute(fast))).ProcessExecuteStatusCode).toBe("CompletedSuccessfully");

        const shortWait = tm1.withAsync(200, 1_000);
        expect((await shortWait.session.execute(execute(slow))).ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
      } finally {
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(fast))).catch(() => undefined);
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(slow))).catch(() => undefined);
        await tm1.close();
      }
    },
    60_000,
  );

  tm1Test(
    "stops the work on TM1 when cancelled",
    async () => {
      const tm1 = await Live.client();
      const async = tm1.withAsync(200);
      const process = "tm1-client-" + randomUUID().replace(/-/g, "").slice(0, 8);
      await tm1.session.execute(createProcess(process, "i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n"));
      try {
        const cancel = new AbortController();
        const run = async.session.execute(execute(process), { signal: cancel.signal });
        run.catch(() => undefined);
        expect(await waitFor(tm1.session, process, true), "the process runs").toBe(true);
        cancel.abort();
        await expect(run).rejects.toBeDefined();
        expect(await waitFor(tm1.session, process, false), "cancelling deleted the monitor, which stopped it").toBe(true);
      } finally {
        await waitFor(tm1.session, process, false);
        await tm1.session.execute(Tm1Request.delete(ODataPath.of("Processes").key(process)));
        await tm1.close();
      }
    },
    120_000,
  );
});

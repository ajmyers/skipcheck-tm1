// SPDX-License-Identifier: Apache-2.0

import { randomUUID } from "node:crypto";
import { describe, expect } from "vitest";
import { ODataPath, ODataQuery, Tm1Request, Tm1Session } from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";
import { sleep, createProcess, execute } from "./raw-tm1.js";

/** The Java client's `Tm1SessionAsyncTm1Test`: a process run asynchronously, and stopped by cancelling. */
describe("Tm1Session async on TM1" + liveReason, () => {
  /** Waits up to 30 s for a thread running `process` to be there, or gone. */
  const waitFor = async (s: Tm1Session, process: string, running: boolean): Promise<boolean> => {
    for (let i = 0; i < 150; i++) {
      const threads = await s.stream(Tm1Request.list<{ Function?: string }>(ODataPath.of("Threads"), null)).toArray();
      if (threads.some((t) => typeof t.Function === "string" && t.Function.includes(`Processes('${process}')`)) === running) return true;
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

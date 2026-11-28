// SPDX-License-Identifier: Apache-2.0

import { afterAll, beforeAll, describe, expect } from "vitest";
import {
  ODataPath,
  ODataQuery,
  ProcessCreate,
  ProcessDebugContextBreakpoint,
  ProcessDebugContextLineBreakpointCreate,
  ProcessDebugContextLineBreakpointPatch,
  Tm1BadRequestException,
  Tm1Request,
  typeOf,
  type ProcessDebugContext,
  type ProcessDebugContextLineBreakpoint,
  type Tm1Client,
} from "../../src/index.js";
import { Live, liveReason, tm1Test } from "./live.js";

/** ProcessDebugService against a real TM1, debugging a process of three lines: nA = 1; nB = nA + 1;
 * nC = nB + 1. The Java client's `ProcessDebugTm1Test`. */
describe("ProcessDebugService on TM1" + liveReason, () => {
  let tm1: Tm1Client;
  const process = Live.name();

  const line = (id: number, lineNumber: number): ProcessDebugContextLineBreakpointCreate =>
    ProcessDebugContextLineBreakpointCreate.builder().id(id).processName(process).procedure("Prolog").lineNumber(lineNumber).enabled(true).build();

  beforeAll(async () => {
    if (!Live.configured) return;
    tm1 = await Live.client();
    await tm1.processes.create(ProcessCreate.builder().name(process).prologProcedure("nA = 1;\nnB = nA + 1;\nnC = nB + 1;\n").build());
  });

  afterAll(async () => {
    if (!tm1) return;
    await tm1.processes.delete(process).catch(() => undefined);
    await tm1.close();
  });

  async function debuggingEnabled(): Promise<boolean> {
    const config = await tm1.session.execute(Tm1Request.get<{ EnableTIDebugging?: boolean }>(ODataPath.of("ActiveConfiguration").nav("Modelling").nav("TI")));
    return config.EnableTIDebugging === true;
  }

  tm1Test("stops at a breakpoint, reads the state and runs to the end", async (context) => {
    if (!(await debuggingEnabled())) return context.skip("EnableTIDebugging is off on this server");
    const debug = tm1.processDebugging;
    const id = (await debug.debug(process, {}, ODataQuery.none())).ID!;
    try {
      await stopsAndRunsToTheEnd(id);
    } finally {
      // A run left paused holds the process, and its delete would wait for it
      await debug.finish(id).catch(() => undefined);
    }
  });

  /** The context once the run has stopped moving. TM1 answers a start, a resume or a step with the
   * status of that moment, Running or already Paused. */
  async function settled(id: string): Promise<ProcessDebugContext> {
    const deadline = Date.now() + 30_000;
    let context = await tm1.processDebugging.get(id, ODataQuery.none());
    while (context.Status === "Running" && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 100));
      context = await tm1.processDebugging.get(id, ODataQuery.none());
    }
    return context;
  }

  async function stopsAndRunsToTheEnd(id: string): Promise<void> {
    const debug = tm1.processDebugging;
    expect((await settled(id)).Status, "paused before the first line").toBe("Paused");
    expect(await debug.currentBreakpoint(id, ODataQuery.none()), "at no breakpoint yet").toBeUndefined();
    await debug.addBreakpoint(id, line(1, 3));
    const breakpoints = await debug.breakpoints(id, ODataQuery.none()).toArray();
    expect(breakpoints).toHaveLength(1);
    expect(typeOf(ProcessDebugContextBreakpoint, breakpoints[0]!)).toBe("ProcessDebugContextLineBreakpoint");
    expect((breakpoints[0] as ProcessDebugContextLineBreakpoint).LineNumber).toBe(3);

    expect(["Running", "Paused"]).toContain((await debug.resume(id, ODataQuery.none())).Status);
    expect((await settled(id)).Status, "at the breakpoint").toBe("Paused");
    const current = await debug.currentBreakpoint(id, ODataQuery.none());
    expect(typeOf(ProcessDebugContextBreakpoint, current!)).toBe("ProcessDebugContextLineBreakpoint");
    expect((current as ProcessDebugContextLineBreakpoint).LineNumber).toBe(3);
    const stack = await debug.callStack(id, ODataQuery.none()).toArray();
    expect(stack.map((f) => [f.Procedure, f.LineNumber])).toEqual([["Prolog", 3]]);
    const frame = stack[0]!.ID!;
    const variables = (await debug.variables(id, frame, ODataQuery.none()).toArray()).map((v) => v.Name);
    expect(variables, "TI's own variables, not the process's").toEqual(expect.arrayContaining(["NVALUE", "DATASOURCETYPE"]));
    expect(variables).not.toContain("NA");
    expect(variables).not.toContain("NB");
    expect((await debug.frameProcess(id, frame, ODataQuery.none())).Name).toBe(process);
    expect((await debug.process(id, ODataQuery.none())).Name).toBe(process);
    expect((await debug.thread(id, ODataQuery.none())).ObjectName).toBe(process);
    expect(await debug.list(ODataQuery.none()).toArray(), "lists none").toEqual([]);

    // Past the last line: still running, paused with no line to be on, or already complete
    expect(["Running", "Paused", "Complete"]).toContain((await debug.stepOver(id, ODataQuery.none())).Status);
    const done = await debug.finish(id);
    expect(done.Status).toBe("Complete");
    expect(done.Result?.ProcessExecuteStatusCode).toBe("CompletedSuccessfully");
    expect((await debug.get(id, ODataQuery.none())).Status, "still readable, complete").toBe("Complete");
    await expect(tm1.session.execute(Tm1Request.delete(ODataPath.of("ProcessDebugContexts").key(id))), "TM1 refuses to delete one").rejects.toBeInstanceOf(
      Tm1BadRequestException,
    );
  }

  tm1Test("needs a chosen breakpoint ID, and updates and removes by it", async (context) => {
    if (!(await debuggingEnabled())) return context.skip("EnableTIDebugging is off on this server");
    const debug = tm1.processDebugging;
    await debug
      .debugging(process, {}, async (context) => {
        const id = context.ID!;
        expect(() => debug.addBreakpoint(id, ProcessDebugContextLineBreakpointCreate.builder().processName(process).lineNumber(2).build())).toThrow(RangeError);
        await debug.addBreakpoint(id, line(1, 2));
        await debug.addBreakpoint(id, line(2, 3));
        await debug.removeBreakpoint(id, 1);
        await debug.updateBreakpoint(id, 2, ProcessDebugContextLineBreakpointPatch.builder().enabled(false).build());
        for (const b of await debug.breakpoints(id, ODataQuery.none()).toArray()) {
          expect(b.ID).toBe(2);
          expect(b.Enabled).toBe(false);
        }
      })
      .toArray();
  });

  tm1Test("finishes the run after debugging, even with breakpoints left", async (context) => {
    if (!(await debuggingEnabled())) return context.skip("EnableTIDebugging is off on this server");
    const debug = tm1.processDebugging;
    let id: string | undefined;
    await debug
      .debugging(process, {}, async (context) => {
        id = context.ID!;
        await debug.addBreakpoint(id, line(1, 2));
        await debug.stepIn(id, ODataQuery.none());
        return 1;
      })
      .toArray();
    expect((await debug.get(id!, ODataQuery.none())).Status).toBe("Complete");
  });
});

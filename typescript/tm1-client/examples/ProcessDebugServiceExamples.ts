// SPDX-License-Identifier: Apache-2.0

import { ProcessDebugContextLineBreakpointPatch, ProcessProcedure, type ProcessDebugContext, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for ProcessDebugService, quoted in its documentation.

export function breakpoint(tm1: Tm1Client): Promise<string[]> {
  // @start region = breakpoint
  // Where the run stops at a breakpoint on line 3 of the prolog. debugging() runs it to
  // the end afterwards.
  const debug = tm1.processDebugging;
  return debug
    .debugging("Load Sales", { pVersion: "Forecast" }, async function* (context) {
      const id = context.ID!;
      const line3 = ProcessDebugContextLineBreakpointPatch.builder()
        .id(1) // TM1 needs an ID chosen by the caller
        .processName("Load Sales")
        .procedure(ProcessProcedure.Prolog)
        .lineNumber(3)
        .enabled(true)
        .build();
      await debug.addBreakpoint(id, line3);
      await debug.resume(id);
      for await (const frame of debug.callStack(id)) yield `${frame.Procedure}, line ${frame.LineNumber}`;
    })
    .toArray();
  // @end
}

export async function step(tm1: Tm1Client): Promise<ProcessDebugContext> {
  // @start region = step
  // Without debugging(), end the run with finish(): TM1 can't delete it
  const debug = tm1.processDebugging;
  const context = await debug.debug("Load Sales");
  const id = context.ID!;
  await debug.stepOver(id);
  await debug.stepOver(id);
  return debug.finish(id);
  // @end
}

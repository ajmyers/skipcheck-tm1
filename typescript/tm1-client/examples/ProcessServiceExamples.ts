// SPDX-License-Identifier: Apache-2.0

import {
  ODataQuery,
  ProcessExecuteResult,
  ProcessParameterPatch,
  ProcessPatch,
  ProcessVariableType,
  type Process,
  type Tm1Client,
} from "@skipcheck/tm1-client";

// Examples for ProcessService, quoted in its documentation.

export async function create(tm1: Tm1Client): Promise<Process> {
  // @start region = create
  // TM1 saves code that doesn't compile, so compile the definition first
  const process = ProcessPatch.builder()
    .name("Load Sales")
    .parameters([
      ProcessParameterPatch.builder().name("pVersion").value("Actual").type(ProcessVariableType.String).build(),
      ProcessParameterPatch.builder().name("pYear").value(2026).type(ProcessVariableType.Numeric).build(),
    ])
    .prologProcedure("LogOutput('INFO', 'Loading ' | pVersion | ' for ' | NumberToString(pYear));\n")
    .build();
  const errors = await tm1.processes.compile(process).toArray();
  if (errors.length > 0) throw new Error("The process doesn't compile: " + JSON.stringify(errors));
  return tm1.processes.create(process);
  // @end
}

export async function run(tm1: Tm1Client): Promise<ProcessExecuteResult> {
  // @start region = run
  // Parameters by name, in order; a numeric one takes a number, not text
  const parameters = { pVersion: "Forecast", pYear: 2026 };
  const withLog = ODataQuery.none().expand(ProcessExecuteResult.Fields.ErrorLogFile);
  const result = await tm1.processes.executeWithReturn("Load Sales", parameters, withLog);
  // A run that ended with errors names the log it wrote
  if (result.ErrorLogFile) throw new Error(`${result.ProcessExecuteStatusCode}: see ${result.ErrorLogFile.Filename}`);
  return result;
  // @end
}

export function runUnsaved(tm1: Tm1Client): Promise<ProcessExecuteResult> {
  // @start region = run-unsaved
  // A one-off run of code that isn't saved as a process
  const cleanup = ProcessPatch.builder().name("Clear Sales Staging").prologProcedure("CubeClearData('Sales Staging');\n").build();
  return tm1.processes.executeWithReturn(cleanup);
  // @end
}

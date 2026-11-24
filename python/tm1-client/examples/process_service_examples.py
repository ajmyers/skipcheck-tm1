# SPDX-License-Identifier: Apache-2.0

"""Examples for ProcessService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Process, ProcessCreate, ProcessExecuteResult, ProcessParameterPatch, ProcessVariableType


def create(tm1: Tm1Client) -> Process:
    # @start region = create
    # TM1 saves code that doesn't compile, so compile the definition first
    process = (
        ProcessCreate.builder()
        .name("Load Sales")
        .parameters(
            [
                ProcessParameterPatch.builder().name("pVersion").value("Actual").type(ProcessVariableType.STRING).build(),
                ProcessParameterPatch.builder().name("pYear").value(2026).type(ProcessVariableType.NUMERIC).build(),
            ]
        )
        .prolog_procedure("LogOutput('INFO', 'Loading ' | pVersion | ' for ' | NumberToString(pYear));\n")
        .build()
    )
    errors = tm1.processes.compile(process)
    if errors:
        raise ValueError(f"The process doesn't compile: {errors}")
    return tm1.processes.create(process)
    # @end


def run(tm1: Tm1Client) -> ProcessExecuteResult:
    # @start region = run
    # Parameters by name, in order; a numeric one takes a number, not text
    parameters = {"pVersion": "Forecast", "pYear": 2026}
    result = tm1.processes.execute_with_return("Load Sales", parameters, ODataQuery.none().expand(ProcessExecuteResult.ERROR_LOG_FILE))
    # A run that ended with errors names the log it wrote
    if result.error_log_file is not None:
        raise RuntimeError(f"{result.process_execute_status_code}: see {result.error_log_file.filename}")
    return result
    # @end


def run_unsaved(tm1: Tm1Client) -> ProcessExecuteResult:
    # @start region = run-unsaved
    # A one-off run of code that isn't saved as a process
    cleanup = ProcessCreate.builder().name("Clear Sales Staging").prolog_procedure("CubeClearData('Sales Staging');\n").build()
    return tm1.processes.execute_with_return(cleanup)
    # @end

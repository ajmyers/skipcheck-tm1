# SPDX-License-Identifier: Apache-2.0

"""The walkthrough "Run a process and read its result" (api-site/content/walkthroughs/tm1-client),
on SData's SalesCube. The live tests run its steps and record what they print
(tests/live/test_walkthrough_examples.py)."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cellset, CellsetCell, ProcessCreate, ProcessExecuteResult, ProcessParameterPatch, ProcessVariableType


def main() -> None:
    # Leaving the with block closes the session
    with Tm1Client.builder().base_url("http://localhost:8011/api/v1/").basic_auth("admin", "apple").build() as tm1:
        create(tm1)
        run(tm1)
        check(tm1)
        fail(tm1)
        delete(tm1)


def create(tm1: Tm1Client) -> None:
    # @start region = create
    # A process that sets a month's budget to its actual units plus 10%
    process = (
        ProcessCreate.builder()
        .name("Budget From Actual")
        .parameters([ProcessParameterPatch.builder().name("pMonth").value("Jan").type(ProcessVariableType.STRING).build()])
        .prolog_procedure(
            "nActual = CellGetN('SalesCube', 'Actual', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);\n"
            "CellPutN(Round(nActual * 1.1), 'SalesCube', 'Budget', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);\n"
        )
        .build()
    )
    # TM1 saves a process even when its code has errors, so compile it first
    errors = tm1.processes.compile(process)
    if errors:
        raise ValueError(f"The process doesn't compile: {errors}")
    tm1.processes.create(process)
    # @end


def run(tm1: Tm1Client) -> None:
    # @start region = run
    # Parameters by name; TM1 waits until the process ends to answer
    result = tm1.processes.execute_with_return("Budget From Actual", parameters={"pMonth": "Feb"})
    print(f"Status: {result.process_execute_status_code}")
    # @end


def check(tm1: Tm1Client) -> None:
    # @start region = check
    mdx = (
        "SELECT {[actvsbud].[actvsbud].[Actual], [actvsbud].[actvsbud].[Budget]} ON COLUMNS FROM [SalesCube]"
        " WHERE ([region].[region].[France], [model].[model].[S Series 1.8 L Sedan],"
        " [account1].[account1].[Units], [month].[month].[Feb])"
    )
    cellset = tm1.cellsets.execute(mdx, ODataQuery.none().expand(Cellset.CELLS, lambda cells: cells.select(CellsetCell.VALUE)))
    print(f"Actual: {cellset.cells[0].value}")
    print(f"Budget: {cellset.cells[1].value}")
    tm1.cellsets.delete(cellset.id)
    # @end


def fail(tm1: Tm1Client) -> None:
    # @start region = fail
    # A month that isn't in the month dimension: TM1 runs the process, which fails
    with_log = ODataQuery.none().expand(ProcessExecuteResult.ERROR_LOG_FILE)
    result = tm1.processes.execute_with_return("Budget From Actual", parameters={"pMonth": "Febr"}, query=with_log)
    print(f"Status: {result.process_execute_status_code}")
    # A run with errors writes a log, which the result names
    if result.error_log_file is not None:
        print(tm1.logs.error_log_content(result.error_log_file.filename))
    # @end


def delete(tm1: Tm1Client) -> None:
    # @start region = delete
    tm1.processes.delete("Budget From Actual")
    # @end


if __name__ == "__main__":
    main()

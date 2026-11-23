# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1Client, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cube, ProcessExecuteResult


def connect() -> None:
    # @start region = connect
    tm1 = Tm1Client.builder().base_url("http://localhost:8010/api/v1/").basic_auth("admin", "apple").build()

    # Leaving the block closes the session on TM1
    with tm1:
        for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME)):
            print(cube.name)
    # @end


def run_async(tm1: Tm1Client) -> ProcessExecuteResult:
    # @start region = async
    # TM1 runs it in the background and the client polls for the result, so no
    # request stays open. In the asyncio flavor, cancelling the call stops the process on TM1.
    return tm1.with_async(2).processes.execute_with_return("Rebuild Forecast")
    # @end

# SPDX-License-Identifier: Apache-2.0

"""Runs the walkthroughs' examples (examples/connect_walkthrough.py and the rest) against IBM's SData
sample, and records what each step prints for the docs (tests/example_output.py): a port of the Java
client's WalkthroughExamplesTm1Test. The walkthroughs are written for SData, so these start a server
of their own with it, rather than use the run's empty one, and skip without a TM1 source and Docker
to start it from (TM1_URL alone isn't enough)."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import Tm1Client

from ..example_output import ExampleOutput, example

try:
    from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer, Tm1Source, docker_available
except ImportError:  # tm1-testcontainers isn't installed
    _SOURCE = None
    _DOCKER = False
else:
    _SOURCE = Tm1Source.discover()
    _DOCKER = _SOURCE is not None and docker_available()

pytestmark = pytest.mark.skipif(not _DOCKER, reason="Needs a TM1 source and Docker, to start a server with IBM's SData sample")


@pytest.fixture(scope="module")
def sdata() -> Iterator[Tm1ServerContainer]:
    assert _SOURCE is not None
    with Tm1ServerContainer(_SOURCE).with_seed(Tm1Seed.SDATA) as server:
        yield server


@pytest.fixture(scope="module")
def tm1(sdata: Tm1ServerContainer) -> Iterator[Tm1Client]:
    with Tm1Client.builder().base_url(sdata.rest_url).basic_auth(sdata.username, sdata.password).build() as client:
        yield client


def test_connects_and_lists_cubes(tm1: Tm1Client, sdata: Tm1ServerContainer) -> None:
    walkthrough = example("ConnectWalkthrough")
    with ExampleOutput("tm1-client", "ConnectWalkthrough") as output:
        output.record("version", lambda: walkthrough.print_version(tm1))
        output.record("list-cubes", lambda: walkthrough.list_cubes(tm1))
    assert output.output("version") == [f"Connected to TM1 {sdata.tm1_version}"]
    assert {"SalesCube", "PriceCube"} <= set(output.output("list-cubes"))


def test_reads_and_writes_cells(tm1: Tm1Client) -> None:
    walkthrough = example("CellsWalkthrough")
    with ExampleOutput("tm1-client", "CellsWalkthrough") as output:
        output.record("read", lambda: walkthrough.read(tm1))
        output.record("write", lambda: walkthrough.write(tm1))
        output.record("read-back", lambda: walkthrough.read_back(tm1))
    assert len(output.output("read")) == 3
    assert output.output("read-back") == ["Budget for Jan: 150"]


def test_runs_a_process_and_reads_its_result(tm1: Tm1Client) -> None:
    walkthrough = example("ProcessWalkthrough")
    with ExampleOutput("tm1-client", "ProcessWalkthrough") as output:
        output.record("create", lambda: walkthrough.create(tm1))
        try:
            output.record("run", lambda: walkthrough.run(tm1))
            output.record("check", lambda: walkthrough.check(tm1))
            output.record("fail", lambda: walkthrough.fail(tm1))
        finally:
            output.record("delete", lambda: walkthrough.delete(tm1))
    assert output.output("run") == ["Status: CompletedSuccessfully"]
    assert output.output("fail")[0] != "Status: CompletedSuccessfully"

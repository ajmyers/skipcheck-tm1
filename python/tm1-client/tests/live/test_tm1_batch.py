# SPDX-License-Identifier: Apache-2.0

"""Tm1Batch against TM1: many calls in one $batch, each with its own result. A port of the Java
client's Tm1BatchTm1Test."""

from __future__ import annotations

import uuid

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Client, Tm1Error, Tm1NotFoundError, Tm1Request
from skipcheck_tm1.model import Cube, DimensionPatch, ElementPatch, HierarchyPatch, ProcessExecuteStatusCode, ProcessPatch

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_runs_reads_writes_and_failures_together(tm1: Tm1Client) -> None:
    dimension = "batch" + uuid.uuid4().hex[:8]
    process = "tm1-client-" + uuid.uuid4().hex[:8]
    tm1.dimensions.create(DimensionPatch.builder().name(dimension).hierarchies([HierarchyPatch.builder().name(dimension).build()]).build())
    tm1.processes.create(ProcessPatch.builder().name(process).prolog_procedure("x = 1;\n").build())
    try:
        batch = tm1.batch()
        queued = batch.client
        cube = queued.cubes.get("}ClientGroups", ODataQuery.none().select(Cube.NAME))
        missing = queued.cubes.get("not-a-cube")
        north = queued.elements.create(dimension, dimension, ElementPatch.builder().name("North").build())
        twice = queued.elements.create(dimension, dimension, ElementPatch.builder().name("North").build())
        listed = queued.elements.list(dimension, dimension)
        run = queued.processes.execute_with_return(process)
        elements = ODataPath.of("Dimensions").key(dimension).nav("Hierarchies").key(dimension).nav("Elements")
        count = queued.session.execute(Tm1Request.text(elements.count()))
        assert batch.size() == 7

        batch.run()

        assert cube.result().name == "}ClientGroups"
        with pytest.raises(Tm1NotFoundError):
            missing.result()
        assert north.result().name == "North"
        with pytest.raises(Tm1Error):  # TM1 carries on past it, in order
            twice.result()
        assert [e.name for e in listed.result()] == ["North"], "sees the element made before it"
        assert run.result().process_execute_status_code is ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
        assert count.result() == "1", "plain text, which TM1 refuses under the batch's own Accept"
    finally:
        tm1.processes.delete(process)
        tm1.dimensions.delete(dimension)


def test_takes_a_thousand_requests(tm1: Tm1Client) -> None:
    batch = tm1.batch()
    reads = [batch.client.cubes.get("}ClientGroups", ODataQuery.none().select(Cube.NAME)) for _ in range(1000)]
    batch.run(1000)
    assert len([r.result() for r in reads]) == 1000

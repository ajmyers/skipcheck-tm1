# SPDX-License-Identifier: Apache-2.0

"""Java's SampleDatabaseExampleTest: a server of your own, for one test module, with IBM's SData
sample in it. The module gets its own container, started before its tests and stopped after them;
the shared container's source tells this one where TM1 is. Skipped without a TM1 source."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer

pytestmark = pytest.mark.requires_tm1


@pytest.fixture(scope="module")
def sdata(tm1_server: Tm1ServerContainer) -> Iterator[Tm1ServerContainer]:
    with Tm1ServerContainer(tm1_server.source).with_seed(Tm1Seed.SDATA) as container:
        yield container


@pytest.fixture(scope="module")
def tm1(sdata: Tm1ServerContainer) -> Iterator[Tm1Rest]:
    with Tm1Rest(sdata) as rest:
        yield rest


def test_reads_the_sample_cubes(sdata: Tm1ServerContainer, tm1: Tm1Rest) -> None:
    assert sdata.password == "apple"
    assert tm1.model_cube_count() > 0
    assert '"name":"salescube"' in tm1.get("Cubes('SalesCube')?$select=Name").lower()

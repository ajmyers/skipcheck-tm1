# SPDX-License-Identifier: Apache-2.0

"""Examples for RequiresTm1, quoted in the guide: pytest tests, type-checked but never run here."""

from __future__ import annotations

import uuid
from collections.abc import Iterator

import pytest

from skipcheck_tm1 import Tm1Client, Tm1Error, Tm1Version
from skipcheck_tm1.model import DimensionPatch, ElementPatch, ElementType, HierarchyPatch
from skipcheck_tm1_testcontainers import Tm1ServerContainer

# @start region = test-class
# Every test in the module needs TM1, and is skipped without one
pytestmark = pytest.mark.requires_tm1


# One client per module, on the shared server
@pytest.fixture(scope="module")
def tm1(tm1_server: Tm1ServerContainer) -> Iterator[Tm1Client]:
    with Tm1Client.builder().base_url(tm1_server.rest_url).basic_auth(tm1_server.username, tm1_server.password).build() as client:
        yield client


# The server is shared, so use a unique name and delete it afterwards
@pytest.fixture
def region(tm1: Tm1Client) -> Iterator[str]:
    name = f"Region-{uuid.uuid4()}"
    yield name
    try:
        tm1.dimensions.delete(name)
    except Tm1Error:
        pass


def test_creates_the_regions(tm1: Tm1Client, region: str) -> None:
    north = ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()
    south = ElementPatch.builder().name("South").type(ElementType.NUMERIC).build()
    hierarchy = HierarchyPatch.builder().name(region).elements([north, south]).build()
    created = tm1.dimensions.create(DimensionPatch.builder().name(region).hierarchies([hierarchy]).build())
    assert created.name == region
    # @end


# @start region = by-version
@pytest.mark.requires_tm1
def test_needs_a_recent_server(tm1_server: Tm1ServerContainer) -> None:
    # Skips, rather than fails, on servers older than the feature
    introduced_in = Tm1Version.parse("11.8.03000")
    if not tm1_server.tm1_version.is_at_least(introduced_in):
        pytest.skip(f"needs TM1 {introduced_in} or newer")
    # ...
    # @end

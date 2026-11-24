# SPDX-License-Identifier: Apache-2.0

"""Examples for RequiresTm1, RequiresTm1Web, RequiresPaw and RequiresCognos, quoted in the guide: pytest
tests, type-checked but never run here."""

from __future__ import annotations

import uuid
from collections.abc import Iterator

import pytest

from skipcheck_tm1 import Tm1Client, Tm1Error, Tm1Version
from skipcheck_tm1.model import DimensionCreate, ElementCreate, ElementType, HierarchyCreate
from skipcheck_tm1_testcontainers import CognosContainer, CognosSource, PawContainer, Tm1ServerContainer, Tm1WebContainer

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
    north = ElementCreate.builder().name("North").type(ElementType.NUMERIC).build()
    south = ElementCreate.builder().name("South").type(ElementType.NUMERIC).build()
    hierarchy = HierarchyCreate.builder().name(region).elements([north, south]).build()
    created = tm1.dimensions.create(DimensionCreate.builder().name(region).hierarchies([hierarchy]).build())
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


# @start region = tm1-web
@pytest.mark.requires_tm1_web
def test_opens_a_websheet(tm1_web: Tm1WebContainer) -> None:
    # http://localhost:54321/tm1web/, which lists the shared TM1 server
    assert tm1_web.url.endswith("/tm1web/")
    # ...
    # @end


# @start region = paw
@pytest.mark.requires_paw
def test_opens_paw(paw: PawContainer) -> None:
    # http://localhost:54321/, which logs on to the shared TM1 server
    assert paw.url.startswith("http://")
    # ...
    # @end


# @start region = cognos
@pytest.mark.requires_cognos
def test_readers_can_only_read(cognos: CognosSource) -> None:
    # No shared Cognos: each test starts its own, with the users it needs
    with CognosContainer(cognos).with_user("viewer", "viewerpw", "Readers"):
        ...
    # @end

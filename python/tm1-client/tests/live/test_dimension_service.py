# SPDX-License-Identifier: Apache-2.0

"""DimensionService against a real TM1: a port of the Java client's DimensionServiceTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import (
    Filter,
    ODataPath,
    ODataQuery,
    Tm1Client,
    Tm1NotFoundError,
    Tm1PreconditionFailedError,
    Tm1Request,
)
from skipcheck_tm1._generated.operations import Tm1Operations
from skipcheck_tm1.model import (
    Dimension,
    DimensionCreate,
    Hierarchy,
    HierarchyCreate,
)

from .conftest import CONFIGURED, REASON
from .support import (
    assume_supports,
    dimension_of,
    is_v12,
    made,
    names,
    numeric,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_creates_reads_lists_and_deletes_a_dimension(tm1: Tm1Client, name: str) -> None:
    dimensions = tm1.dimensions
    assert dimensions.create(dimension_of(name, [numeric("North"), numeric("South")])).name == name
    read = dimensions.get(name, ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.expand(Hierarchy.ELEMENTS)))
    assert read.etag
    assert len(read.hierarchies) == 1
    assert sorted(names(read.hierarchies[0].elements)) == ["North", "South"]
    listed = dimensions.list(ODataQuery.none().select(Dimension.NAME).filter(Filter.starts_with(Dimension.NAME, name)))
    assert names(listed) == [name]
    dimensions.delete(name)
    with pytest.raises(Tm1NotFoundError):
        dimensions.get(name)


def test_updates_only_when_the_etag_is_current(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North"), numeric("South")])) as d:
        etag = tm1.dimensions.get(d).etag
        patch = DimensionCreate.builder().name(d).build()
        tm1.dimensions.update(d, patch, etag)
        tm1.dimensions.add_all_leaves_hierarchy(d)
        assert tm1.dimensions.get(d).etag != etag, "a new hierarchy changes it"
        with pytest.raises(Tm1PreconditionFailedError):
            tm1.dimensions.update(d, patch, etag)


def test_ignores_hierarchies_and_the_all_leaves_name_in_a_patch(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North"), numeric("South")])) as d:
        # DimensionPatch offers neither, as TM1 ignores both: sent as they are, to pin it
        hierarchy = HierarchyCreate.builder().name(d).elements([numeric("North"), numeric("South"), numeric("East")]).build()
        tm1.session.execute(Tm1Request.patch(ODataPath.of("Dimensions").key(d), {"AllLeavesHierarchyName": "AllLeaves", "Hierarchies": [hierarchy.json()]}))
        read = tm1.dimensions.get(d, ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.expand(Hierarchy.ELEMENTS)))
        if is_v12(tm1):
            assert read.all_leaves_hierarchy_name == "AllLeaves", "TM1 v12 applies AllLeavesHierarchyName"
        else:
            assert not read.all_leaves_hierarchy_name, "TM1 accepts the PATCH and ignores it"
        assert len(read.hierarchies) == 1 and len(read.hierarchies[0].elements) == 2


def test_saves_a_copy_and_adds_an_all_leaves_hierarchy(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North"), numeric("South")])) as d:
        assert tm1.dimensions.save_as(d, d + "-copy").name == d + "-copy"
        assert tm1.dimensions.save_as(d, d + "-copy", True, False).name == d + "-copy"
        tm1.dimensions.delete(d + "-copy")
        assert tm1.dimensions.add_all_leaves_hierarchy(d).name == "Leaves"
        hierarchies = names(tm1.dimensions.get(d, ODataQuery.none().expand(Dimension.HIERARCHIES)).hierarchies)
        assert d in hierarchies and "Leaves" in hierarchies


def test_locks_and_reserves(tm1: Tm1Client, name: str) -> None:
    with made(tm1, name, dimension_of(name, [numeric("North")])) as d:
        tm1.dimensions.lock(d)
        tm1.dimensions.unlock(d)
        assume_supports(tm1, Tm1Operations.DIMENSION_RESERVE)
        tm1.dimensions.reserve(d)
        tm1.dimensions.release(d)

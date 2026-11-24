# SPDX-License-Identifier: Apache-2.0

"""SubsetService against a real TM1: a port of the Java client's SubsetServiceTm1Test."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from skipcheck_tm1 import (
    ODataPath,
    ODataQuery,
    Tm1Client,
    Tm1PreconditionFailedError,
)
from skipcheck_tm1._sync.services.dimension import SubsetScope
from skipcheck_tm1.model import (
    Element,
    SubsetCreate,
    SubsetPatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    dimension_of,
    made,
    names,
    numeric,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def abcd(tm1: Tm1Client, name: str) -> Iterator[str]:
    with made(tm1, name, dimension_of(name, [numeric("A"), numeric("B"), numeric("C"), numeric("D")])) as d:
        yield d


def el(d: str, name: str) -> ODataPath:
    return ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key(name)


def subset_elements(tm1: Tm1Client, d: str, scope: SubsetScope, subset: str) -> list[str]:
    return names(tm1.subsets.elements(d, d, scope, subset, ODataQuery.none().select(Element.NAME)))


def test_creates_static_and_dynamic_subsets_reads_updates_and_deletes_them(tm1: Tm1Client, abcd: str) -> None:
    d, subsets, public = abcd, tm1.subsets, SubsetScope.PUBLIC
    subsets.create(d, d, public, SubsetCreate.builder().name("Static").bind_elements(el(d, "A"), el(d, "B")).build())
    subsets.create(d, d, public, SubsetCreate.builder().name("Dynamic").expression(f"{{TM1SUBSETALL([{d}].[{d}])}}").build())
    assert sorted(names(subsets.list(d, d, public))) == ["Dynamic", "Static"]
    assert subset_elements(tm1, d, public, "Static") == ["A", "B"]
    assert sorted(subset_elements(tm1, d, public, "Dynamic")) == ["A", "B", "C", "D"]
    etag = subsets.get(d, d, public, "Dynamic").etag
    subsets.update(d, d, public, "Dynamic", SubsetPatch.builder().expression(f"{{[{d}].[{d}].[C]}}").build(), etag)
    assert subset_elements(tm1, d, public, "Dynamic") == ["C"]
    with pytest.raises(Tm1PreconditionFailedError):
        subsets.update(d, d, public, "Dynamic", SubsetPatch.builder().expression("{}").build(), etag)
    assert subsets.hierarchy(d, d, public, "Static").name == d
    subsets.delete(d, d, public, "Static")
    assert names(subsets.list(d, d, public)) == ["Dynamic"]


def test_appends_and_clears_elements_by_name_and_refuses_them_in_an_update(tm1: Tm1Client, abcd: str) -> None:
    d, public = abcd, SubsetScope.PUBLIC
    tm1.subsets.create(d, d, public, SubsetCreate.builder().name("S").bind_elements(el(d, "A")).build())
    tm1.subsets.add_elements(d, d, public, "S", ["C", "B"])
    assert subset_elements(tm1, d, public, "S") == ["A", "C", "B"], "appended, in order"
    tm1.subsets.clear_elements(d, d, public, "S")
    assert subset_elements(tm1, d, public, "S") == []
    with pytest.raises(ValueError, match="add_elements"):
        tm1.subsets.update(d, d, public, "S", SubsetPatch.builder().bind_elements(el(d, "D")).build())


def test_places_elements_last_before_or_after_others(tm1: Tm1Client, abcd: str) -> None:
    d, public = abcd, SubsetScope.PUBLIC
    tm1.subsets.create(d, d, public, SubsetCreate.builder().name("S").bind_elements(el(d, "A"), el(d, "B")).build())
    tm1.subsets.set_element(d, d, public, "S", "C")
    assert subset_elements(tm1, d, public, "S") == ["A", "B", "C"], "no position puts it last"
    tm1.subsets.set_element_before(d, d, public, "S", "D", "B")
    assert subset_elements(tm1, d, public, "S") == ["A", "D", "B", "C"]
    tm1.subsets.set_element_after(d, d, public, "S", "A", "B")
    assert subset_elements(tm1, d, public, "S") == ["D", "B", "A", "C"], "moved, not added again"


def test_keeps_private_subsets_apart_and_saves_copies(tm1: Tm1Client, abcd: str) -> None:
    d = abcd
    tm1.subsets.create(d, d, SubsetScope.PRIVATE, SubsetCreate.builder().name("Mine").bind_elements(el(d, "D")).build())
    assert names(tm1.subsets.list(d, d, SubsetScope.PRIVATE)) == ["Mine"]
    assert tm1.subsets.list(d, d, SubsetScope.PUBLIC) == []
    tm1.subsets.create(d, d, SubsetScope.PUBLIC, SubsetCreate.builder().name("All").expression(f"{{TM1SUBSETALL([{d}].[{d}])}}").build())
    assert tm1.subsets.save_as(d, d, SubsetScope.PUBLIC, "All", "Copy").expression
    frozen = tm1.subsets.save_as(d, d, SubsetScope.PUBLIC, "All", "Frozen", True, True)
    assert not frozen.expression, "static"
    assert sorted(subset_elements(tm1, d, SubsetScope.PRIVATE, "Frozen")) == ["A", "B", "C", "D"]


def test_creates_session_subsets_under_the_servers_name(tm1: Tm1Client, abcd: str) -> None:
    d = abcd
    defined = tm1.subsets.create_session_subset(d, d, SubsetCreate.builder().name("Wanted").expression(f"{{[{d}].[{d}].[B]}}").build())
    assert defined.name != "Wanted", "TM1 ignores the name it's given"
    assert subset_elements(tm1, d, SubsetScope.SESSION, defined.name) == ["B"]
    tm1.subsets.create(d, d, SubsetScope.PUBLIC, SubsetCreate.builder().name("S").bind_elements(el(d, "C"), el(d, "D")).build())
    copied = tm1.subsets.create_session_subset_from(d, d, SubsetScope.PUBLIC, "S")
    assert subset_elements(tm1, d, SubsetScope.SESSION, copied.name) == ["C", "D"]
    assert tm1.subsets.list(d, d, SubsetScope.SESSION) == [], "TM1 lists no session subsets, though each reads by its name"

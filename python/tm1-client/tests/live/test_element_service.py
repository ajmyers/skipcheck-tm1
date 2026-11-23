# SPDX-License-Identifier: Apache-2.0

"""ElementService against a real TM1: a port of the Java client's ElementServiceTm1Test. The
dimension is conftest.py's elements_dim."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import (
    ODataPath,
    ODataQuery,
    Tm1BadRequestError,
    Tm1Client,
    Tm1NotFoundError,
    Tm1PreconditionFailedError,
    Tm1Request,
)
from skipcheck_tm1.model import (
    Element,
    ElementPatch,
    ElementType,
)

from .conftest import CONFIGURED, REASON
from .support import (
    components,
    names,
    numeric,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_creates_reads_updates_and_deletes_an_element(tm1: Tm1Client, elements_dim: str) -> None:
    d, elements = elements_dim, tm1.elements
    assert elements.create(d, d, numeric("West")).name == "West"
    assert sorted(names(elements.list(d, d, ODataQuery.none().select(Element.NAME)))) == sorted(["Total", "North", "South", "East", "Label", "West"])
    etag = elements.get(d, d, "West").etag
    elements.update(d, d, "West", ElementPatch.builder().type(ElementType.STRING).build(), etag)
    assert elements.get(d, d, "West").type is ElementType.STRING
    with pytest.raises(Tm1PreconditionFailedError):
        elements.update(d, d, "West", ElementPatch.builder().type(ElementType.NUMERIC).build(), etag)
    elements.delete(d, d, "West")
    with pytest.raises(Tm1NotFoundError):
        elements.get(d, d, "West")


def test_refuses_name_and_attributes_in_an_update_as_tm1_ignores_or_rejects_them(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    with pytest.raises(ValueError, match="renamed"):
        tm1.elements.update(d, d, "East", ElementPatch.builder().name("Est").build())
    with pytest.raises(ValueError):
        tm1.elements.update(d, d, "East", ElementPatch.builder().name("Est").build(), 'W/"x"')
    east = ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Elements").key("East")
    tm1.session.execute(Tm1Request.patch(east, {"Name": "Est"}))
    assert tm1.elements.get(d, d, "East").name == "East", "TM1 ignores the new name"
    with pytest.raises(Tm1BadRequestError):
        tm1.session.execute(Tm1Request.patch(east, {"Attributes": {"Caption": "E"}}))


def test_navigates_parents_components_edges_and_the_hierarchy(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    assert names(tm1.elements.parents(d, d, "North")) == ["Total"]
    assert components(tm1, d, "Total") == ["North", "South"]
    assert [(e.parent_name, e.component_name) for e in tm1.elements.edges(d, d, "Total")] == [("Total", "North"), ("Total", "South")]
    assert tm1.elements.hierarchy(d, d, "North").name == d


def test_sets_components_last_or_before_another_with_a_weight(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    tm1.elements.set_component(d, d, "Total", "East")
    assert components(tm1, d, "Total") == ["North", "South", "East"], "no Before puts it last"
    tm1.elements.create(d, d, numeric("West"))
    tm1.elements.set_component(d, d, "Total", "West", before="North", weight=2.0)
    assert components(tm1, d, "Total") == ["West", "North", "South", "East"]
    assert tm1.edges.get(d, d, "Total", "West").weight == 2.0
    tm1.elements.create(d, d, numeric("Far"))
    tm1.elements.set_component(d, d, "Total", "Far", weight=-1.0)
    assert tm1.edges.get(d, d, "Total", "Far").weight == -1.0


def test_locks_and_reserves_an_element(tm1: Tm1Client, elements_dim: str) -> None:
    d = elements_dim
    tm1.elements.lock(d, d, "North")
    tm1.elements.unlock(d, d, "North")
    tm1.elements.reserve(d, d, "North")
    tm1.elements.release(d, d, "North")

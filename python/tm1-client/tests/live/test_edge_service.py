"""EdgeService against a real TM1: a port of the Java client's EdgeServiceTm1Test. The dimension is
conftest.py's elements_dim."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import (
    Tm1Client,
)
from skipcheck_tm1.model import (
    EdgePatch,
)

from .conftest import CONFIGURED, REASON
from .support import (
    components,
    edge,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_creates_reads_reweights_and_deletes_edges(tm1: Tm1Client, elements_dim: str) -> None:
    d, edges = elements_dim, tm1.edges
    assert len(edges.list(d, d)) == 2
    assert edges.get(d, d, "Total", "North").weight == 1.0
    edges.update(d, d, "Total", "North", EdgePatch.builder().weight(3.0).build())
    assert edges.get(d, d, "Total", "North").weight == 3.0
    assert edges.parent(d, d, "Total", "North").name == "Total"
    assert edges.component(d, d, "Total", "North").name == "North"
    assert edges.hierarchy(d, d, "Total", "North").name == d
    assert edges.create(d, d, edge("Total", "East")).component_name == "East"
    edges.delete(d, d, "Total", "North")
    assert components(tm1, d, "Total") == ["South", "East"]
    assert tm1.elements.parents(d, d, "North") == [], "still an element, now a root"

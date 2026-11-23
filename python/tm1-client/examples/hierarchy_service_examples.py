# SPDX-License-Identifier: Apache-2.0

"""Examples for HierarchyService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import EdgePatch, ElementPatch, ElementType, Hierarchy, HierarchyPatch, Member


def create(tm1: Tm1Client) -> Hierarchy:
    # @start region = create
    # A second hierarchy of Region, rolling the same leaves up by manager
    smith = ElementPatch.builder().name("Smith").type(ElementType.CONSOLIDATED).build()
    north = ElementPatch.builder().name("North").type(ElementType.NUMERIC).build()
    south = ElementPatch.builder().name("South").type(ElementType.NUMERIC).build()
    edges = [EdgePatch.builder().parent_name("Smith").component_name(leaf).weight(1.0).build() for leaf in ("North", "South")]
    by_manager = HierarchyPatch.builder().name("By Manager").elements([smith, north, south]).edges(edges).build()
    return tm1.hierarchies.create("Region", by_manager)
    # @end


def replace_structure(tm1: Tm1Client) -> None:
    # @start region = replace
    # Makes the elements and edges exactly these: TM1 deletes every other element of
    # the hierarchy, with its data
    everything = ElementPatch.builder().name("All Versions").type(ElementType.CONSOLIDATED).build()
    actual = ElementPatch.builder().name("Actual").type(ElementType.NUMERIC).build()
    budget = ElementPatch.builder().name("Budget").type(ElementType.NUMERIC).build()
    edges = [EdgePatch.builder().parent_name("All Versions").component_name(leaf).weight(1.0).build() for leaf in ("Actual", "Budget")]
    tm1.hierarchies.replace_structure("Version", "Version", [everything, actual, budget], edges)
    # @end


def roots(tm1: Tm1Client) -> list[str | None]:
    # @start region = roots
    # The top of the hierarchy: the members with no parent, in order
    return [member.name for member in tm1.hierarchies.root_members("Region", "Region", ODataQuery.none().select(Member.NAME))]
    # @end

# SPDX-License-Identifier: Apache-2.0

"""Examples for DimensionService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Dimension, DimensionCreate, EdgePatch, ElementCreate, ElementType, Hierarchy, HierarchyCreate


def list_names(tm1: Tm1Client) -> list[str | None]:
    # @start region = list
    # The names of the model dimensions, in name order
    by_name = ODataQuery.none().select(Dimension.NAME).order_by(Dimension.NAME)
    return [dimension.name for dimension in tm1.dimensions.model_dimensions(by_name)]
    # @end


def create(tm1: Tm1Client) -> Dimension:
    # @start region = create
    # Region, with one hierarchy of the same name: Total = North + South
    total = ElementCreate.builder().name("Total").type(ElementType.CONSOLIDATED).build()
    north = ElementCreate.builder().name("North").type(ElementType.NUMERIC).build()
    south = ElementCreate.builder().name("South").type(ElementType.NUMERIC).build()
    edges = [EdgePatch.builder().parent_name("Total").component_name(leaf).weight(1.0).build() for leaf in ("North", "South")]
    hierarchy = HierarchyCreate.builder().name("Region").elements([total, north, south]).edges(edges).build()
    return tm1.dimensions.create(DimensionCreate.builder().name("Region").hierarchies([hierarchy]).build())
    # @end


def hierarchies(tm1: Tm1Client) -> list[str | None]:
    # @start region = hierarchies
    # A dimension with its hierarchies' names expanded in the same request
    with_hierarchies = ODataQuery.none().expand(Dimension.HIERARCHIES, lambda h: h.select(Hierarchy.NAME))
    region = tm1.dimensions.get("Region", with_hierarchies)
    return [hierarchy.name for hierarchy in region.hierarchies or []]
    # @end

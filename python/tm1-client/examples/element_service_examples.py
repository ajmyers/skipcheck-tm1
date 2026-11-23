# SPDX-License-Identifier: Apache-2.0

"""Examples for ElementService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Element, ElementPatch, ElementType


def consolidations(tm1: Tm1Client) -> list[str | None]:
    # @start region = filter
    # The names of the consolidated elements, in name order
    consolidated = ODataQuery.none().select(Element.NAME).filter(Element.TYPE.eq(ElementType.CONSOLIDATED)).order_by(Element.NAME)
    return [element.name for element in tm1.elements.list("Region", "Region", consolidated)]
    # @end


def add_child(tm1: Tm1Client) -> None:
    # @start region = add-child
    # A new element is a root; SetComponent then puts it last under Total
    tm1.elements.create("Region", "Region", ElementPatch.builder().name("West").type(ElementType.NUMERIC).build())
    tm1.elements.set_component("Region", "Region", "Total", "West")
    # @end


def children(tm1: Tm1Client) -> list[str | None]:
    # @start region = children
    # The children of Total, in order
    return [element.name for element in tm1.elements.components("Region", "Region", "Total", ODataQuery.none().select(Element.NAME))]
    # @end

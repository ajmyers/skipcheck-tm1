# SPDX-License-Identifier: Apache-2.0

"""Examples for EdgeService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import Edge, EdgeCreate, EdgePatch


def list_edges(tm1: Tm1Client) -> list[str]:
    # @start region = list
    # Every parent-child link of the hierarchy, as "parent > child (weight)"
    return [f"{edge.parent_name} > {edge.component_name} ({edge.weight})" for edge in tm1.edges.list("Region", "Region")]
    # @end


def reweight(tm1: Tm1Client) -> None:
    # @start region = reweight
    # Make Cost count negatively in Margin
    tm1.edges.update("Account", "Account", "Margin", "Cost", EdgePatch.builder().weight(-1.0).build())
    # @end


def move(tm1: Tm1Client) -> Edge:
    # @start region = move
    # Move North from Total to Total Sales: add the new edge, then delete the old
    edge = tm1.edges.create("Region", "Region", EdgeCreate.builder().parent_name("Total Sales").component_name("North").weight(1.0).build())
    tm1.edges.delete("Region", "Region", "Total", "North")
    return edge
    # @end

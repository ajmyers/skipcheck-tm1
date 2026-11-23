# SPDX-License-Identifier: Apache-2.0

"""Examples for ODataPath, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataPath


def build() -> list[ODataPath]:
    # @start region = build
    element = ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements").key("O'Neil")
    # Dimensions('Region')/Hierarchies('Region')/Elements('O''Neil')
    reserve = ODataPath.of("Cubes").key("Sales").action("tm1.Reserve")
    # Cubes('Sales')/tm1.Reserve
    # @end
    return [element, reserve]

# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1Request, quoted in the guide."""

from __future__ import annotations

from typing import Any

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1Request


def build() -> list[Tm1Request[Any]]:
    # @start region = build
    # A cube's name, read as plain JSON
    cube = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"), dict)
    # An action with no answer
    reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), {})
    # @end
    return [cube, reserve]

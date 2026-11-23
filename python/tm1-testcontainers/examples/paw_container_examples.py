# SPDX-License-Identifier: Apache-2.0

"""Examples for PawContainer, quoted in the guide. Test code, never run here."""

from __future__ import annotations

from skipcheck_tm1_testcontainers import PawContainer, PawSource, Tm1ServerContainer, Tm1Source


def start(tm1_source: Tm1Source, paw_source: PawSource) -> str:
    # @start region = start
    # Entering the blocks starts TM1, then PAW; leaving them stops both
    with Tm1ServerContainer(tm1_source) as tm1, PawContainer(paw_source).with_tm1_server(tm1).accept_license() as paw:
        return paw.url  # http://localhost:54321/
    # @end

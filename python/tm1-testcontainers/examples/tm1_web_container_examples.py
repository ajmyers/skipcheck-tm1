# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1WebContainer, quoted in the guide. Test code, never run here."""

from __future__ import annotations

from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer, Tm1Source, Tm1WebContainer, Tm1WebSource


def start(tm1_source: Tm1Source, web_source: Tm1WebSource) -> str:
    # @start region = start
    # Entering the blocks starts TM1, then TM1 Web; leaving them stops both
    with Tm1ServerContainer(tm1_source).with_seed(Tm1Seed.SDATA) as tm1, Tm1WebContainer(web_source).with_tm1_server(tm1) as web:
        return web.url  # http://localhost:54321/tm1web/
    # @end

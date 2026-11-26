# SPDX-License-Identifier: Apache-2.0

"""Java's TwoServersExampleTest: two TM1 servers at once, for code that works across servers
(migrating objects from one to another, comparing environments, syncing a model from source
control). Each container is an independent TM1 server with its own Admin Server, on its own mapped
ports. Skipped without a TM1 source."""

from __future__ import annotations

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer


@pytest.mark.requires_tm1
def test_compares_a_source_and_a_target(tm1_server: Tm1ServerContainer) -> None:
    # The shared, empty server is the target. Start a second one, with data, as the source.
    target = tm1_server
    with Tm1ServerContainer(target.source).with_seed(Tm1Seed.SDATA) as source:
        with Tm1Rest(source) as from_, Tm1Rest(target) as to:
            assert source.rest_url != target.rest_url
            assert from_.model_cube_count() > 0
            assert to.model_cube_count() == 0
            # ...then run your migration from `source` to `target` and compare again

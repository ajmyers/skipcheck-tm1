# SPDX-License-Identifier: Apache-2.0

"""Java's WithoutTheExtensionExampleTest: without the requires_tm1 marker or the fixtures, with plain
skips and a with block. Use this for full control of the container's lifetime, or for a test that
damages its server: give it a fresh server of its own, thrown away at the end, so it can't affect
other tests."""

from __future__ import annotations

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import Tm1ServerContainer, Tm1Source, docker_available


def test_starts_a_throwaway_server() -> None:
    if not docker_available():
        pytest.skip("needs Docker")
    source = Tm1Source.discover()
    if source is None:
        pytest.skip("needs a TM1 source")

    with Tm1ServerContainer(source) as tm1:
        with Tm1Rest(tm1) as rest:
            assert rest.get("Configuration/ProductVersion/$value") == str(tm1.tm1_version)
    # the container, and anything the test did to it, is removed here

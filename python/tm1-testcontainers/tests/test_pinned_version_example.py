# SPDX-License-Identifier: Apache-2.0

"""Java's PinnedVersionExampleTest: making sure tests run against the TM1 version you mean. Either a
wrong version is a mistake (with_expected_version makes the container refuse to start), or the test
only applies to some versions (check tm1_version and skip). Skipped without a TM1 source."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import Tm1Version
from skipcheck_tm1_testcontainers import Tm1ServerContainer

pytestmark = pytest.mark.requires_tm1

# The version this example pins to. Change it to yours.
PA_2_1_19 = Tm1Version.parse("11.8.03500.6")


def test_refuses_to_start_on_any_other_version(tm1_server: Tm1ServerContainer) -> None:
    if tm1_server.tm1_version != PA_2_1_19:
        pytest.skip(f"this example pins to {PA_2_1_19}")

    # Starting raises "Expected TM1 11.8.03500.6 but the server from ... reports ..." on a mismatch
    with Tm1ServerContainer(tm1_server.source).with_expected_version(PA_2_1_19) as server:
        assert server.tm1_version == PA_2_1_19


def test_skips_where_the_feature_doesnt_exist(tm1_server: Tm1ServerContainer) -> None:
    # Say the behavior under test first appeared in this (made-up) release
    introduced_in = Tm1Version.parse("11.8.03000")
    if not tm1_server.tm1_version.is_at_least(introduced_in):
        pytest.skip(f"needs TM1 {introduced_in} or newer")

    assert tm1_server.tm1_version >= introduced_in  # ...then test the behavior

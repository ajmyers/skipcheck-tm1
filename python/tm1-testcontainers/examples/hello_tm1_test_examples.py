# SPDX-License-Identifier: Apache-2.0

"""The Getting started example of the testcontainers guide: a pytest test, type-checked but never run here."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1_testcontainers import Tm1ServerContainer


# @start region = hello-test
# The tm1_server fixture is the shared TM1 server; without one configured, the test is skipped
@pytest.mark.requires_tm1
def test_connects(tm1_server: Tm1ServerContainer) -> None:
    builder = Tm1Client.builder().base_url(tm1_server.rest_url).basic_auth(tm1_server.username, tm1_server.password)
    with builder.build() as tm1:
        assert tm1.session.version() is not None
    # @end

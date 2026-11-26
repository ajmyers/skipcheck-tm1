# SPDX-License-Identifier: Apache-2.0

"""Java's SharedServerExampleTest: the everyday case, one shared TM1 server for all your tests. The
tm1_server fixture is the shared container, started once per session on first use. Because the
server is shared, give the objects you create unique names and delete them afterwards. Skipped
without a TM1 source."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import Tm1ServerContainer

pytestmark = pytest.mark.requires_tm1


# 1. In a module fixture: set up something expensive once per module, like a client (Java's
# static @BeforeAll and @AfterAll)
@pytest.fixture(scope="module")
def tm1(tm1_server: Tm1ServerContainer) -> Iterator[Tm1Rest]:
    with Tm1Rest(tm1_server) as rest:
        yield rest


def test_uses_the_client_set_up_once(tm1: Tm1Rest) -> None:
    assert tm1.model_cube_count() == 0  # a new, empty database


# 2. As a test's parameter: the simplest
def test_gets_the_server_as_a_parameter(tm1_server: Tm1ServerContainer) -> None:
    assert tm1_server is Tm1ServerContainer.shared()  # always the same shared server
    assert tm1_server.rest_url.endswith("/api/v1/")
    assert tm1_server.username == "admin"
    assert tm1_server.password == ""

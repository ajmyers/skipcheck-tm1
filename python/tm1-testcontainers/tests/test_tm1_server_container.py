# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1ServerContainerTest: against a TM1 server in Docker, skipped without one."""

from __future__ import annotations

import httpx
import pytest

from skipcheck_tm1 import Tm1Version
from skipcheck_tm1_testcontainers import Tm1ServerContainer

pytestmark = pytest.mark.requires_tm1


def get(tm1: Tm1ServerContainer, path: str) -> httpx.Response:
    return httpx.get(tm1.rest_url + path, auth=(tm1.username, tm1.password), timeout=30)


def test_starts_a_tm1_server_and_reports_its_version(tm1_server: Tm1ServerContainer) -> None:
    assert tm1_server.rest_url.startswith("http://") and tm1_server.rest_url.endswith("/api/v1/")
    assert tm1_server.tm1_version > Tm1Version.parse("11")
    version = get(tm1_server, "Configuration/ProductVersion/$value")
    assert version.status_code == 200
    assert Tm1Version.parse(version.text) == tm1_server.tm1_version


def test_starts_with_an_empty_database(tm1_server: Tm1ServerContainer) -> None:
    # Only control cubes (named with a leading '}') in a new database
    cubes = get(tm1_server, "Cubes?$select=Name&$filter=not%20startswith(Name,'%7D')")
    assert cubes.status_code == 200
    assert '"value":[]' in cubes.text.replace(" ", "")


def test_shares_one_container_across_tests(tm1_server: Tm1ServerContainer) -> None:
    assert tm1_server is Tm1ServerContainer.shared()
    assert tm1_server.is_running


def test_refuses_to_start_on_the_wrong_version(tm1_server: Tm1ServerContainer) -> None:
    tm1 = Tm1ServerContainer(tm1_server.source).with_expected_version(Tm1Version.parse("99.0"))
    try:
        with pytest.raises(RuntimeError, match="Expected TM1 99.0 but the server from"):
            tm1.start()
        assert not tm1.is_running  # and it doesn't leave the container behind
    finally:
        tm1.stop()

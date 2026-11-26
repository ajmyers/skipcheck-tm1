# SPDX-License-Identifier: Apache-2.0

"""Java's SharedAdminServerExampleTest: several TM1 servers, one Admin Server, as in a real
deployment. The servers register with a Tm1AdminServerContainer, and TM1 Web lists them all from
it. Skipped without a TM1 source (and, for TM1 Web, its source)."""

from __future__ import annotations

import time
from contextlib import closing

import httpx2
import pytest
from tm1_rest import Tm1Rest
from tm1_web_session import Tm1WebSession

from skipcheck_tm1_testcontainers import Tm1AdminServerContainer, Tm1ServerContainer, Tm1Source, Tm1WebContainer, Tm1WebSource


@pytest.mark.requires_tm1
def test_servers_register_with_one_admin_server() -> None:
    source = Tm1Source.discover()
    assert source is not None
    admin = Tm1AdminServerContainer(source)
    planning = Tm1ServerContainer(source).with_env("TM1_SERVER_NAME", "planning").with_admin_server(admin)
    reporting = Tm1ServerContainer(source).with_env("TM1_SERVER_NAME", "reporting").with_admin_server(admin)
    with closing(admin), closing(planning), closing(reporting):
        planning.start()
        reporting.start()

        registered = servers(admin)
        assert '"Name":"planning"' in registered
        assert '"Name":"reporting"' in registered
        with Tm1Rest(planning) as rest:
            assert rest.get("Configuration/ServerName/$value") == "planning"


@pytest.mark.requires_tm1_web
def test_tm1_web_lists_every_registered_server() -> None:
    source = Tm1Source.discover()
    web_source = Tm1WebSource.discover()
    assert source is not None and web_source is not None
    admin = Tm1AdminServerContainer(source)
    planning = Tm1ServerContainer(source).with_env("TM1_SERVER_NAME", "planning").with_admin_server(admin)
    reporting = Tm1ServerContainer(source).with_env("TM1_SERVER_NAME", "reporting").with_admin_server(admin)
    web = Tm1WebContainer(web_source).with_admin_server(admin)
    with closing(admin), closing(planning), closing(reporting), closing(web):
        planning.start()
        reporting.start()
        web.start()

        listed = Tm1WebSession(web).servers(web.admin_host)
        assert "planning" in listed and "reporting" in listed


def servers(admin: Tm1AdminServerContainer) -> str:
    """The Admin Server's list of registered servers. TM1 servers register within seconds."""
    body = ""
    for _ in range(30):
        if "planning" in body and "reporting" in body:
            break
        time.sleep(1)
        body = httpx2.get(admin.rest_url + "Servers?$select=Name", timeout=30).text
    return body

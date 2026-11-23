"""Java's Tm1WebContainerTest and PawContainerTest: against TM1 Web and PAW in Docker, skipped
without their sources."""

from __future__ import annotations

import httpx
import pytest
from tm1_web_session import Tm1WebSession

from tm1_testcontainers import PawContainer, PawSession, Tm1ServerContainer, Tm1WebContainer


def server_name(tm1: Tm1ServerContainer) -> str:
    response = httpx.get(tm1.rest_url + "Configuration/ServerName/$value", auth=(tm1.username, tm1.password), timeout=30)
    assert response.status_code == 200
    return response.text


def registered_host(tm1: Tm1ServerContainer) -> str:
    return f"http://{tm1.container_info['Config']['Hostname']}:{Tm1ServerContainer.REST_PORT}"


# --- Tm1WebContainerTest -----------------------------------------------------------------------


@pytest.mark.requires_tm1_web
def test_lists_and_logs_on_to_the_shared_tm1_server(tm1_web: Tm1WebContainer, tm1_server: Tm1ServerContainer) -> None:
    server = server_name(tm1_server)
    session = Tm1WebSession(tm1_web)
    assert server in session.servers(tm1_web.admin_host)
    assert session.login(tm1_web.admin_host, server, tm1_server.username, tm1_server.password).strip()


@pytest.mark.requires_tm1_web
def test_evaluates_mdx_through_the_evalservice(tm1_web: Tm1WebContainer, tm1_server: Tm1ServerContainer) -> None:
    server = server_name(tm1_server)
    session = Tm1WebSession(tm1_web)
    tm1_session_id = session.login(tm1_web.admin_host, server, tm1_server.username, tm1_server.password)
    # The shared server is on a network of its own, where TM1's registered host name resolves
    result = session.evaluate_mdx(
        registered_host(tm1_server), server, tm1_session_id, "SELECT {[}Groups].Members} ON 0, {[}Clients].Members} ON 1 FROM [}ClientGroups]"
    )
    assert '"success":true' in result


@pytest.mark.requires_tm1_web
def test_shares_one_container_wired_to_the_shared_server(tm1_web: Tm1WebContainer, tm1_server: Tm1ServerContainer) -> None:
    assert tm1_web is Tm1WebContainer.shared()
    assert tm1_web.tm1_server is tm1_server
    assert tm1_web.url.startswith("http://") and tm1_web.url.endswith("/tm1web/")


# --- PawContainerTest --------------------------------------------------------------------------


@pytest.mark.requires_paw
def test_logs_on_and_calls_tm1_through_paw(paw: PawContainer, tm1_server: Tm1ServerContainer) -> None:
    session = PawSession(paw.url).login(tm1_server.username, tm1_server.password)
    server = server_name(tm1_server)
    assert server in session.servers()
    user = session.tm1_get(server, "ActiveUser?$select=Name")
    assert user.status_code == 200
    assert f'"name":"{tm1_server.username}"' in user.text.lower().replace(" ", "")


@pytest.mark.requires_paw
def test_serves_the_workspace_app_once_logged_on(paw: PawContainer, tm1_server: Tm1ServerContainer) -> None:
    session = PawSession(paw.url).login(tm1_server.username, tm1_server.password)
    app = session.get("")
    assert app.status_code == 200
    assert "Planning Analytics" in app.text


@pytest.mark.requires_paw
def test_refuses_a_wrong_password(paw: PawContainer, tm1_server: Tm1ServerContainer) -> None:
    with pytest.raises(OSError):
        PawSession(paw.url).login(tm1_server.username, "not-the-password")


@pytest.mark.requires_paw
def test_shares_one_paw_wired_to_the_shared_server(paw: PawContainer, tm1_server: Tm1ServerContainer) -> None:
    assert paw is PawContainer.shared()
    assert paw.tm1_server is tm1_server
    assert paw.is_running
    assert paw.url.startswith("http://") and paw.url.endswith("/")

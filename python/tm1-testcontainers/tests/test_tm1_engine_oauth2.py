# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineOAuth2Test: OAuth 2.0 logins against real TM1 v12 and Keycloak. An engine of the
module's own, taking tokens from a Keycloak beside it. Skipped without a TM1 v12 source."""

from __future__ import annotations

import time
from collections.abc import Iterator

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import KeycloakContainer, RequiresTm1Engine, Tm1Database, Tm1EngineContainer, Tm1EngineSource

pytestmark = pytest.mark.requires_tm1_engine(share_database=True)


# Java's static @BeforeAll and @AfterAll
@pytest.fixture(scope="module")
def keycloak() -> Iterator[KeycloakContainer]:
    # Not started here: the engine starts it first, with the realm it gives it
    container = KeycloakContainer()
    try:
        yield container
    finally:
        container.stop()


@pytest.fixture(scope="module")
def planning(keycloak: KeycloakContainer) -> Iterator[Tm1Database]:
    source = Tm1EngineSource.discover()
    if source is None:
        pytest.skip(RequiresTm1Engine.NO_SOURCE_MESSAGE)
    engine = (
        Tm1EngineContainer(source)
        .with_database("planning")
        .with_user("analyst", "Analysts")
        .with_login("analyst", "analyst$pw")
        .with_login("admin", "adminpw")
        .with_oauth2(keycloak)
    )
    try:
        engine.start()
        yield engine.database("planning")
    finally:
        # Before its Keycloak, which the keycloak fixture stops
        engine.stop()


def test_a_keycloak_token_logs_the_user_in(planning: Tm1Database) -> None:
    analyst = planning.oauth2_login("analyst")
    # The token on a request of its own: TM1 answers, and gives a session
    response = tm1_engine_rest.send("GET", planning.rest_url + "ActiveUser?$select=Name,Type", headers=analyst.authorize_with_token({}))
    assert response.status_code == 200
    assert '"name":"analyst"' in response.text.lower() and '"Type":"User"' in response.text
    assert any(cookie.startswith("TM1SessionId=") for cookie in response.headers.get_list("set-cookie"))
    # And the session that gives serves later requests
    assert analyst.cookie().startswith("TM1SessionId=")
    assert tm1_engine_rest.get(planning, analyst, "ActiveUser").status_code == 200


def test_the_administrator_logs_in_through_o_auth2_too(planning: Tm1Database) -> None:
    response = tm1_engine_rest.get(planning, planning.oauth2_login("admin"), "ActiveUser?$select=Name,Type")
    assert response.status_code == 200
    assert '"Type":"Admin"' in response.text


def test_a_token_of_another_realm_is_refused(planning: Tm1Database, keycloak: KeycloakContainer) -> None:
    # Signed by the same Keycloak, but not by the issuer TM1 trusts, and without its audience
    other = keycloak._access_token("master", "admin-cli", "admin", "admin")
    response = tm1_engine_rest.send("GET", planning.rest_url + "ActiveUser", headers={"Authorization": "Bearer " + other})
    assert response.status_code == 401


def test_something_that_isnt_a_token_is_refused(planning: Tm1Database) -> None:
    response = tm1_engine_rest.send("GET", planning.rest_url + "ActiveUser", headers={"Authorization": "Bearer not.a.token"})
    assert response.status_code == 401


def test_an_expired_token_is_refused(planning: Tm1Database, keycloak: KeycloakContainer) -> None:
    # Tokens that live a second, for as long as it takes to get one
    admin = keycloak._access_token("master", "admin-cli", "admin", "admin")
    set_token_life(keycloak, admin, 1)
    try:
        token = planning.oauth2_login("analyst").token()
    finally:
        set_token_life(keycloak, admin, 300)
    # TM1 may allow a clock some slack: wait for it to refuse, for up to two minutes
    started = time.monotonic()
    status = 0
    while time.monotonic() - started < 120:
        status = tm1_engine_rest.send("GET", planning.rest_url + "ActiveUser", headers={"Authorization": "Bearer " + token}).status_code
        if status == 401:
            break
        time.sleep(2)
    print(f"TM1 refused the expired token after {time.monotonic() - started:.0f} s")
    assert status == 401


def set_token_life(keycloak: KeycloakContainer, admin_token: str, seconds: int) -> None:
    """Sets how long the realm's access tokens live, through Keycloak's admin API."""
    response = tm1_engine_rest.send(
        "PUT",
        keycloak.url + "/admin/realms/tm1",
        headers={"Authorization": "Bearer " + admin_token, "Content-Type": "application/json"},
        content=f'{{"accessTokenLifespan":{seconds}}}',
    )
    assert response.status_code == 204


def test_logins_through_the_application_still_work(planning: Tm1Database) -> None:
    analyst = planning.login("analyst")
    assert not analyst.is_oauth2
    assert tm1_engine_rest.get(planning, analyst, "ActiveUser").status_code == 200
    # TM1's own token isn't one to send on a request
    with pytest.raises(RuntimeError):
        analyst.authorize_with_token({})
    # And an OAuth 2.0 login has no session endpoint to ask
    with pytest.raises(RuntimeError):
        planning.oauth2_login("analyst").session("Bearer x")


def test_the_login_says_where_its_tokens_come_from(planning: Tm1Database, keycloak: KeycloakContainer) -> None:
    analyst = planning.oauth2_login("analyst")
    assert analyst.token_url == keycloak.url + "/realms/tm1/protocol/openid-connect/token"
    assert analyst.client_id == "tm1"
    assert analyst.password == "analyst$pw"
    assert len(analyst.token().split(".")) == 3

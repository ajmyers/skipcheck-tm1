# SPDX-License-Identifier: Apache-2.0

"""Java's OAuth2ExampleTest: OAuth 2.0 logins, with Keycloak, the way TM1 v12 logs users in from an
identity provider. The engine takes tokens from a Keycloak beside it, which knows the users who
were given a password. A test gets a token as the user and sends it to TM1. Skipped without a TM1
v12 source."""

from __future__ import annotations

from contextlib import closing

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import KeycloakContainer, Tm1EngineContainer, Tm1EngineSource


@pytest.mark.requires_tm1_engine(share_database=True)
def test_a_user_logs_in_with_a_token_from_keycloak() -> None:
    source = Tm1EngineSource.discover()
    assert source is not None
    # Made, not started: the engine starts its Keycloak first, with the realm it gives it
    keycloak = KeycloakContainer()
    engine = Tm1EngineContainer(source).with_user("analyst", "Analysts").with_login("analyst", "analystpw").with_oauth2(keycloak)
    # Entering the block starts both; leaving it stops the engine, then Keycloak
    with closing(keycloak), engine:
        db = engine.database("tm1")

        response = tm1_engine_rest.send("GET", db.rest_url + "ActiveUser?$select=Name", headers=db.oauth2_login("analyst").authorize_with_token({}))

        assert response.status_code == 200
        assert '"name":"analyst"' in response.text.lower()

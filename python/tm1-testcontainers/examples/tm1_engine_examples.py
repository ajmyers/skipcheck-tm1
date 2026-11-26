# SPDX-License-Identifier: Apache-2.0

"""Examples for RequiresTm1Engine and Tm1EngineContainer, quoted in the guide: type-checked but never
run here."""

from __future__ import annotations

from contextlib import closing

import httpx2
import pytest

from skipcheck_tm1_testcontainers import KeycloakContainer, Tm1Database, Tm1EngineContainer, Tm1EngineSource, Tm1Login

# @start region = test-class
# Every test in the module needs TM1 v12, and is skipped without one
pytestmark = pytest.mark.requires_tm1_engine


# A database of this module's own, dropped when the module is done
def test_lists_cubes(tm1_database: Tm1Database) -> None:
    db = tm1_database
    response = httpx2.get(db.rest_url + "Cubes?$select=Name", headers=db.login().authorize({}))
    assert response.status_code == 200
    # @end


# @start region = shared-database
# The engine's one shared database instead: nothing to create, but give what you make a unique name
@pytest.mark.requires_tm1_engine(share_database=True)
def test_reads_the_servers_name(tm1_database: Tm1Database) -> None:
    db = tm1_database
    response = httpx2.get(db.rest_url + "Configuration/ServerName/$value", headers=db.login().authorize({}))
    assert response.status_code == 200
    # @end


def oauth2(source: Tm1EngineSource) -> None:
    # @start region = oauth2
    # A Keycloak that isn't started: the engine starts it first, with the users' passwords
    keycloak = KeycloakContainer()
    engine = Tm1EngineContainer(source).with_user("analyst", "Analysts").with_login("analyst", "analystpw").with_oauth2(keycloak)
    # Entering the block starts Keycloak, then the engine; the Keycloak is yours to close
    with closing(keycloak), engine:
        db: Tm1Database = engine.database("tm1")
        # A token from Keycloak, with the analyst's password, sent to TM1 as Bearer
        headers = db.oauth2_login("analyst").authorize_with_token({})
        response = httpx2.get(db.rest_url + "Cubes?$select=Name", headers=headers)
        # @end
        assert response.status_code == 200


def container(source: Tm1EngineSource) -> None:
    # @start region = container
    # Entering the block starts the engine, and leaving it stops it
    with Tm1EngineContainer(source).with_database("planning").with_user("analyst", "Analysts") as engine:
        planning: Tm1Database = engine.database("planning")
        # http://localhost:54321/tm1/api/v1/Databases('planning')/
        url: str = planning.rest_url
        analyst: Tm1Login = planning.login("analyst")
        # ...
        # @end
        assert analyst.user == "analyst", url

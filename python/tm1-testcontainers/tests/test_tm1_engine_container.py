# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineContainerTest: the engine against real TM1 v12, its databases and the
service-to-service logins. One engine for the module, with two databases in the default instance
and one in another. Skipped without a TM1 v12 source."""

from __future__ import annotations

import base64
import time
from collections.abc import Iterator

import pytest
import tm1_engine_rest

from skipcheck_tm1 import Tm1Version
from skipcheck_tm1_testcontainers import RequiresTm1Engine, Tm1Database, Tm1EngineContainer, Tm1EngineSource

pytestmark = pytest.mark.requires_tm1_engine(share_database=True)


# Java's static @BeforeAll and @AfterAll
@pytest.fixture(scope="module")
def engine() -> Iterator[Tm1EngineContainer]:
    source = Tm1EngineSource.discover()
    if source is None:
        pytest.skip(RequiresTm1Engine.NO_SOURCE_MESSAGE)
    container = Tm1EngineContainer(source).with_database("planning").with_database("sales").with_database("other", "elsewhere").with_user("analyst", "Analysts")
    try:
        container.start()
        yield container
    finally:
        container.stop()


@pytest.fixture(scope="module")
def planning(engine: Tm1EngineContainer) -> Tm1Database:
    return engine.database("planning")


@pytest.fixture(scope="module")
def sales(engine: Tm1EngineContainer) -> Tm1Database:
    return engine.database("sales")


@pytest.fixture(scope="module")
def elsewhere(engine: Tm1EngineContainer) -> Tm1Database:
    return engine.database("other", "elsewhere")


def basic(client_id: str, client_secret: str) -> str:
    return "Basic " + base64.b64encode(f"{client_id}:{client_secret}".encode()).decode("ascii")


def test_starts_with_the_databases_it_was_given(engine: Tm1EngineContainer, planning: Tm1Database, sales: Tm1Database, elsewhere: Tm1Database) -> None:
    databases = engine.databases()
    assert len(databases) == 3 and set(databases) == {planning, sales, elsewhere}
    assert planning.rest_url == engine.url + "tm1/api/v1/Databases('planning')/"
    assert elsewhere.rest_url == engine.url + "other/api/v1/Databases('elsewhere')/"
    assert engine.tm1_version.is_at_least(Tm1Version.parse("12"))
    with pytest.raises(ValueError, match="No database tm1/missing in this engine"):
        engine.database("missing")


def test_logs_in_with_the_applications_credentials_as_basic(planning: Tm1Database, sales: Tm1Database) -> None:
    admin = planning.login()
    assert admin.cookie().startswith("TM1SessionId=")
    # The one session serves every database of the instance
    for database in (planning, sales):
        response = tm1_engine_rest.get(database, admin, "ActiveUser?$select=Name,Type")
        assert response.status_code == 200
        assert '"Type":"Admin"' in response.text


def test_logs_in_with_a_bearer_token(planning: Tm1Database) -> None:
    analyst = planning.login("analyst")
    token = analyst.token()
    assert len(token.split(".")) == 3

    cookie = analyst.session("Bearer " + token)
    response = tm1_engine_rest.send("GET", planning.rest_url + "ActiveUser?$select=Name", headers={"Cookie": cookie})
    assert response.status_code == 200
    assert '"name":"analyst"' in response.text.lower()


def test_a_user_tm1_doesnt_know_gets_a_session_and_is_then_refused(planning: Tm1Database) -> None:
    nobody = planning.login("nobody")
    assert nobody.cookie().startswith("TM1SessionId=")
    assert tm1_engine_rest.get(planning, nobody, "ActiveUser").status_code == 403


def test_a_user_missing_from_one_database_is_refused_there_only(planning: Tm1Database, sales: Tm1Database) -> None:
    # Users belong to a database: take the analyst out of one
    assert tm1_engine_rest.delete(sales, sales.login(), "Users('analyst')").status_code == 204

    analyst = planning.login("analyst")
    assert tm1_engine_rest.get(planning, analyst, "ActiveUser").status_code == 200
    assert tm1_engine_rest.get(sales, analyst, "ActiveUser").status_code == 403


def test_another_instances_credentials_are_refused(planning: Tm1Database, elsewhere: Tm1Database) -> None:
    # An application belongs to its instance: the other's can't log in here
    other = elsewhere.login()
    assert tm1_engine_rest.get(elsewhere, other, "ActiveUser").status_code == 200

    response = tm1_engine_rest.send(
        "POST",
        planning.login().auth_url + "session",
        headers={"Authorization": basic(other.client_id, other.client_secret), "Content-Type": "application/json"},
        content='{"User":"admin"}',
    )
    assert response.status_code == 401


def test_creates_and_drops_a_database(engine: Tm1EngineContainer) -> None:
    started = time.monotonic()
    made = engine.create_database("scratch")
    created = time.monotonic()
    assert made in engine.databases()
    # With the engine's users, like the databases it started with
    assert tm1_engine_rest.get(made, made.login("analyst"), "ActiveUser").status_code == 200

    before = time.monotonic()
    engine.drop_database(made)
    dropped = time.monotonic()
    assert made not in engine.databases()
    print(f"Created a database in {created - started:.1f} s and dropped it in {dropped - before:.1f} s")


def test_gives_the_root_credentials_for_the_management_api(engine: Tm1EngineContainer) -> None:
    response = tm1_engine_rest.send(
        "GET", engine.management_url + "Instances", headers={"Authorization": basic(engine.root_client_id, engine.root_client_secret)}
    )
    assert response.status_code == 200
    assert '"tm1"' in response.text and '"other"' in response.text

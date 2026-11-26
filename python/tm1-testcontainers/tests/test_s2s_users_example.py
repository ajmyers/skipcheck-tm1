# SPDX-License-Identifier: Apache-2.0

"""Java's S2sUsersExampleTest: users with different rights. An engine of this module's own, with two
users besides ``admin``. TM1 v12 keeps no passwords, so a test logs in as any of them by name,
through the engine's application ("service-to-service"). An engine of your own takes about 40
seconds to start. Skipped without a TM1 v12 source."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import RequiresTm1Engine, Tm1Database, Tm1EngineContainer, Tm1EngineSource

pytestmark = pytest.mark.requires_tm1_engine(share_database=True)


# Java's static @BeforeAll and @AfterAll
@pytest.fixture(scope="module")
def planning() -> Iterator[Tm1Database]:
    source = Tm1EngineSource.discover()
    if source is None:
        pytest.skip(RequiresTm1Engine.NO_SOURCE_MESSAGE)
    engine = Tm1EngineContainer(source).with_database("planning").with_user("analyst", "Analysts").with_user("viewer")
    try:
        engine.start()
        yield engine.database("planning")
    finally:
        engine.stop()


def test_the_administrator_is_an_administrator(planning: Tm1Database) -> None:
    response = tm1_engine_rest.get(planning, planning.login(), "ActiveUser?$select=Name,Type")

    assert '"Type":"Admin"' in response.text


def test_a_user_is_in_the_groups_they_were_given(planning: Tm1Database) -> None:
    response = tm1_engine_rest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)")

    assert response.status_code == 200
    assert '"Type":"User"' in response.text and '"Name":"Analysts"' in response.text


def test_a_user_without_groups_can_still_log_in(planning: Tm1Database) -> None:
    response = tm1_engine_rest.get(planning, planning.login("viewer"), "ActiveUser?$select=Name&$expand=Groups($select=Name)")

    assert response.status_code == 200
    assert '"Groups":[]' in response.text


def test_a_name_tm1_doesnt_know_gets_a_session_but_no_access(planning: Tm1Database) -> None:
    # The application vouches for any name, so the login itself works
    nobody = planning.login("nobody")
    assert nobody.cookie().startswith("TM1SessionId=")

    assert tm1_engine_rest.get(planning, nobody, "ActiveUser").status_code == 403

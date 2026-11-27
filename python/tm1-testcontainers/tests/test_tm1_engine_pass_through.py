# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EnginePassThroughTest: pass-through logins against real TM1 v12 and TM1 11. An engine of
the module's own, asking a TM1 11 server beside it who a name and password are. Skipped without a
TM1 source and a TM1 v12 source."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import (
    RequiresTm1,
    RequiresTm1Engine,
    Tm1Database,
    Tm1EngineContainer,
    Tm1EngineSource,
    Tm1Seed,
    Tm1ServerContainer,
    Tm1Source,
)

pytestmark = [pytest.mark.requires_tm1, pytest.mark.requires_tm1_engine(share_database=True)]


# Java's static @BeforeAll and @AfterAll
@pytest.fixture(scope="module")
def planning() -> Iterator[Tm1Database]:
    tm1 = Tm1Source.discover()
    if tm1 is None:
        pytest.skip(RequiresTm1.NO_SOURCE_MESSAGE)
    source = Tm1EngineSource.discover()
    if source is None:
        pytest.skip(RequiresTm1Engine.NO_SOURCE_MESSAGE)
    # Not started here: the engine starts it first, under the name it reaches it by
    server = Tm1ServerContainer(tm1).with_seed(Tm1Seed.SDATA)
    engine = Tm1EngineContainer(source).with_database("planning").with_user("analyst", "Analysts").with_pass_through(server)
    try:
        engine.start()
        # Two users of the TM1 11 server's own: one the engine has too, and one it lacks
        for name in ("analyst", "stranger"):
            created = tm1_engine_rest.send(
                "POST",
                server.rest_url + "Users",
                headers={"Authorization": server.authorization, "Content-Type": "application/json"},
                content=f'{{"Name":"{name}","Password":"{name}pw"}}',
            )
            assert created.status_code == 201, created.text
        yield engine.database("planning")
    finally:
        try:
            engine.stop()
        finally:
            server.stop()


def test_the_servers_own_user_logs_in(planning: Tm1Database) -> None:
    # admin / apple of the sample database, sent to TM1 v12, which keeps no password
    response = tm1_engine_rest.get(planning, planning.pass_through_login(), "ActiveUser?$select=Name,Type")
    assert response.status_code == 200
    assert '"Type":"Admin"' in response.text


def test_a_user_of_both_logs_in_as_themselves(planning: Tm1Database) -> None:
    analyst = planning.pass_through_login("analyst", "analystpw")
    response = tm1_engine_rest.get(planning, analyst, "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)")
    assert response.status_code == 200
    assert '"name":"analyst"' in response.text.lower()
    assert '"Type":"User"' in response.text
    assert "Analysts" in response.text


def test_the_header_logs_in_on_any_request(planning: Tm1Database) -> None:
    # As a client that logs in itself sends it: TM1 answers, and gives a session
    analyst = planning.pass_through_login("analyst", "analystpw")
    response = tm1_engine_rest.send("GET", planning.rest_url + "ActiveUser?$select=Name", headers={"Authorization": analyst.authorization})
    assert response.status_code == 200
    assert any(cookie.startswith("TM1SessionId=") for cookie in response.headers.get_list("set-cookie"))
    # It has no token: the addresses are TM1's own, where an application gets one
    assert analyst.auth_url == planning.engine.url + "tm1/auth/v1/"
    assert analyst.token_url == planning.engine.url + "tm1/auth/v1/token"
    # A header of the caller's own making is passed on the same
    same = planning.pass_through_login_with(analyst.authorization)
    assert tm1_engine_rest.get(planning, same, "ActiveUser").status_code == 200


def test_a_wrong_password_is_refused(planning: Tm1Database) -> None:
    with pytest.raises(RuntimeError, match="HTTP 401"):
        planning.pass_through_login("analyst", "not-it").cookie()


def test_a_user_the_database_lacks_is_refused(planning: Tm1Database) -> None:
    # The TM1 11 server knows the name and password; the TM1 v12 database has no such user
    with pytest.raises(RuntimeError, match="HTTP 403"):
        planning.pass_through_login("stranger", "strangerpw").cookie()


def test_logins_through_the_application_still_work_beside_pass_through(planning: Tm1Database) -> None:
    response = tm1_engine_rest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name")
    assert response.status_code == 200
    assert '"name":"analyst"' in response.text.lower()

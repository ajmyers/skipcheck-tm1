# SPDX-License-Identifier: Apache-2.0

"""Pass-through Basic authentication on a TM1 v12 engine backed by a TM1 11 server. A port of the
Java client's Tm1EnginePassThroughAuthTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1AuthenticationError, Tm1Client, Tm1Dialect, Tm1Error, Tm1ErrorKind, Tm1ForbiddenError, auth
from skipcheck_tm1.model import UserCreate, UserType

testcontainers = pytest.importorskip("skipcheck_tm1_testcontainers", reason="tm1-testcontainers starts TM1 11 and the TM1 v12 engine")

pytestmark = [pytest.mark.requires_tm1, pytest.mark.requires_tm1_engine]


class Stack:
    """A TM1 11 server and a TM1 v12 engine passing Basic logins through to it."""

    def __init__(self) -> None:
        tm1_source = testcontainers.Tm1Source.discover()
        if tm1_source is None:
            pytest.skip(testcontainers.RequiresTm1.NO_SOURCE_MESSAGE)
        engine_source = testcontainers.Tm1EngineSource.discover()
        if engine_source is None:
            pytest.skip(testcontainers.RequiresTm1Engine.NO_SOURCE_MESSAGE)
        self.server = testcontainers.Tm1ServerContainer(tm1_source).with_seed(testcontainers.Tm1Seed.SDATA)
        self.engine = testcontainers.Tm1EngineContainer(engine_source).with_database("planning").with_user("analyst", "Analysts").with_pass_through(self.server)

    def start(self) -> Stack:
        self.engine.start()
        with Tm1Client.builder().base_url(self.server.rest_url).basic_auth(self.server.username, self.server.password).build() as tm11:
            tm11.users.create(UserCreate.builder().name("analyst").password("analystpw").build())
            tm11.users.create(UserCreate.builder().name("stranger").password("strangerpw").build())
        return self

    def stop(self) -> None:
        try:
            self.engine.stop()
        finally:
            self.server.stop()

    @property
    def database(self) -> Any:
        return self.engine.database("planning")

    def client(self, authenticator: Any) -> Tm1Client:
        db = self.database
        return Tm1Client.builder().base_url(db.rest_url).dialect(Tm1Dialect.v12(db.instance, db.name)).authenticator(authenticator).build()


@pytest.fixture(scope="module")
def stack() -> Iterator[Stack]:
    made = Stack()
    try:
        yield made.start()
    finally:
        made.stop()


def close_quietly(client: Tm1Client) -> None:
    try:
        client.close()
    except Tm1Error:
        pass


def test_logs_on_as_an_administrator_and_a_user_through_pass_through(stack: Stack) -> None:
    with stack.client(auth.basic(stack.server.username, stack.server.password)) as admin:
        assert admin.users.active_user().type is UserType.ADMIN

    with stack.client(auth.basic("analyst", "analystpw")) as analyst:
        user = analyst.users.active_user(expand="Groups")
        assert (user.name or "").lower() == "analyst"
        assert user.type is UserType.USER
        assert "Analysts" in [g.name for g in (user.groups or [])]


def test_refuses_a_wrong_password_and_a_user_the_database_lacks(stack: Stack) -> None:
    wrong_password = stack.client(auth.basic("analyst", "not-it"))
    try:
        with pytest.raises(Tm1AuthenticationError) as exc:
            wrong_password.users.active_user()
        assert exc.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    finally:
        close_quietly(wrong_password)

    # TM1 11 accepts stranger's password; the TM1 v12 database has no such user
    stranger = stack.client(auth.basic("stranger", "strangerpw"))
    try:
        with pytest.raises(Tm1ForbiddenError) as forbidden:
            stranger.users.active_user()
        assert forbidden.value.kind is Tm1ErrorKind.FORBIDDEN
    finally:
        close_quietly(stranger)

# SPDX-License-Identifier: Apache-2.0

"""Pass-through CAM authentication on a TM1 v12 engine backed by a TM1 11 server in Cognos (CAM)
security: ``CAMNamespace`` and ``CAMPassport`` (via Cognos). A port of the Java client's
Tm1EnginePassThroughCamAuthTm1Test."""

from __future__ import annotations

from collections.abc import Callable, Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1AuthenticationError, Tm1Client, Tm1Dialect, Tm1Error, Tm1ErrorKind, Tm1ForbiddenError, auth
from skipcheck_tm1.auth import CognosPassports
from skipcheck_tm1.model import UserType

testcontainers = pytest.importorskip("skipcheck_tm1_testcontainers", reason="tm1-testcontainers starts Cognos, TM1 11 and the TM1 v12 engine")

pytestmark = [pytest.mark.requires_cognos, pytest.mark.requires_tm1, pytest.mark.requires_tm1_engine]

ANALYST = 'CAMID("LDAP:u:uid=analyst,ou=people")'


class Stack:
    """Cognos, a TM1 11 server in CAM security against it, and a TM1 v12 engine passing logins
    through."""

    def __init__(self) -> None:
        cognos_source = testcontainers.CognosSource.discover()
        tm1_source = testcontainers.Tm1Source.discover()
        if cognos_source is None or tm1_source is None:
            pytest.skip(testcontainers.RequiresCognos.NO_SOURCE_MESSAGE)
        engine_source = testcontainers.Tm1EngineSource.discover()
        if engine_source is None:
            pytest.skip(testcontainers.RequiresTm1Engine.NO_SOURCE_MESSAGE)
        self.cognos = (
            testcontainers.CognosContainer(cognos_source)
            .with_user("admin", "adminpw", "Admins")
            .with_user("analyst", "analystpw", "Analysts")
            .with_user("viewer", "viewerpw", "Viewers")
        )
        self.server = testcontainers.Tm1ServerContainer(tm1_source).with_cam_security(self.cognos, "admin")
        self.engine = testcontainers.Tm1EngineContainer(engine_source).with_user("analyst", "Analysts").with_pass_through(self.server)

    def stop(self) -> None:
        try:
            self.engine.stop()
        finally:
            try:
                self.server.stop()
            finally:
                self.cognos.stop()

    @property
    def database(self) -> Any:
        return self.engine.database("tm1")

    def client(self, authenticator: Any) -> Tm1Client:
        db = self.database
        return Tm1Client.builder().base_url(db.rest_url).dialect(Tm1Dialect.v12(db.instance, db.name)).authenticator(authenticator).build()


@pytest.fixture(scope="module")
def stack() -> Iterator[Stack]:
    made = Stack()
    try:
        made.engine.start()
        yield made
    finally:
        made.stop()


def counting(passports: Callable[[], str], asked: list[int]) -> Callable[[], str]:
    def passport() -> str:
        asked[0] += 1
        return passports()

    return passport


def close_quietly(client: Tm1Client) -> None:
    try:
        client.close()
    except Tm1Error:
        pass


def test_logs_on_with_cam_namespace_through_pass_through(stack: Stack) -> None:
    with stack.client(auth.cam_namespace("analyst", "analystpw", "LDAP")) as analyst:
        user = analyst.users.active_user(expand="Groups")
        assert user.name == ANALYST
        assert user.type is UserType.USER
        assert "Analysts" in [g.name for g in (user.groups or [])]

    with stack.client(auth.cam_namespace("admin", "adminpw", "LDAP")) as admin:
        user = admin.users.active_user()
        assert "uid=admin," in (user.name or "")
        assert user.type is UserType.ADMIN


def test_logs_on_with_a_cognos_passport_through_pass_through(stack: Stack) -> None:
    with stack.client(auth.cognos_logon(stack.cognos.url, "LDAP", "analyst", "analystpw")) as analyst:
        user = analyst.users.active_user(expand="Groups")
        assert user.name == ANALYST
        assert user.type is UserType.USER
        assert "Analysts" in [g.name for g in (user.groups or [])]


def test_refuses_a_wrong_password_and_a_cognos_user_the_database_lacks(stack: Stack) -> None:
    asked = [0]
    wrong_passport = stack.client(auth.cam_passport(counting(CognosPassports.password(stack.cognos.url, "LDAP", "analyst", "not-it"), asked)))
    try:
        with pytest.raises(Tm1AuthenticationError) as passport_err:
            wrong_passport.users.active_user()
        assert passport_err.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
        with pytest.raises(Tm1AuthenticationError):
            wrong_passport.users.active_user()
        assert asked[0] == 1, "Cognos asked once: a refusal isn't retried at once"
    finally:
        close_quietly(wrong_passport)

    wrong_namespace = stack.client(auth.cam_namespace("analyst", "not-it", "LDAP"))
    try:
        with pytest.raises(Tm1AuthenticationError) as exc:
            wrong_namespace.users.active_user()
        assert exc.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    finally:
        close_quietly(wrong_namespace)

    # Cognos and TM1 11 know viewer; the TM1 v12 database has no such user
    viewer = stack.client(auth.cam_namespace("viewer", "viewerpw", "LDAP"))
    try:
        with pytest.raises(Tm1ForbiddenError) as forbidden:
            viewer.users.active_user()
        assert forbidden.value.kind is Tm1ErrorKind.FORBIDDEN
    finally:
        close_quietly(viewer)

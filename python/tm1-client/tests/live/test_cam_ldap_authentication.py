# SPDX-License-Identifier: Apache-2.0

"""Logging on to a TM1 in CAM security through Cognos's LDAP namespace: with the password
(``CAMNamespace``), and with a passport the client gets from Cognos. A port of the Java client's
CamLdapAuthenticationTm1Test. Cognos takes a couple of minutes to start, so the containers are
shared by the module."""

from __future__ import annotations

from collections.abc import Callable, Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1AuthenticationError, Tm1Client, Tm1Error, auth
from skipcheck_tm1.auth import CognosPassports

from .conftest import CONFIGURED, REASON

testcontainers = pytest.importorskip("skipcheck_tm1_testcontainers", reason="tm1-testcontainers starts Cognos and the TM1 in CAM security")

# Skipped without Docker, a TM1 source or a Cognos source (Java's @RequiresCognos); never run
# against the TM1 at TM1_URL, which isn't in this Cognos's CAM security
pytestmark = [pytest.mark.skipif(not CONFIGURED, reason=REASON), pytest.mark.requires_cognos]

VIEWER = 'CAMID("LDAP:u:uid=viewer,ou=people")'


class Stack:
    """Cognos with two LDAP users, a TM1 in CAM security against it, and the administrator's
    client."""

    def __init__(self) -> None:
        source = testcontainers.CognosSource.discover()
        tm1_source = testcontainers.Tm1Source.discover()
        if source is None or tm1_source is None:
            pytest.skip(testcontainers.RequiresCognos.NO_SOURCE_MESSAGE)
        self.cognos = testcontainers.CognosContainer(source).with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers")
        self.tm1 = testcontainers.Tm1ServerContainer(tm1_source).with_cam_security(self.cognos, "admin")
        self.admin: Tm1Client | None = None

    def start(self) -> Stack:
        self.tm1.start()
        self.admin = self.client(auth.cam_namespace("admin", "adminpw", "LDAP"))
        return self

    def stop(self) -> None:
        try:
            if self.admin is not None:
                self.admin.close()
        finally:
            self.tm1.stop()
            self.cognos.stop()

    def client(self, authenticator: Any) -> Tm1Client:
        return Tm1Client.builder().base_url(self.tm1.rest_url).authenticator(authenticator).build()


@pytest.fixture(scope="module")
def stack() -> Iterator[Stack]:
    made = Stack()
    try:
        yield made.start()
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


def test_logs_on_with_the_password(stack: Stack) -> None:
    with stack.client(auth.cam_namespace("viewer", "viewerpw", "LDAP")) as viewer:
        assert viewer.users.active_user().name == VIEWER


def test_logs_on_with_a_passport_from_cognos_and_a_fresh_one_after_tm1_drops_the_session(stack: Stack) -> None:
    asked = [0]
    passports = CognosPassports.password(stack.cognos.url, "LDAP", "viewer", "viewerpw")
    with stack.client(auth.cam_passport(counting(passports, asked))) as viewer:
        assert viewer.users.active_user().name == VIEWER
        first = viewer.session.session_id()
        assert stack.admin is not None
        stack.admin.sessions.close(first)

        assert viewer.users.active_user().name == VIEWER, "logged on again"
        assert viewer.session.session_id() != first
        assert asked[0] == 2, "a fresh passport for the new login"


def test_refuses_a_wrong_password_once_without_retrying(stack: Stack) -> None:
    asked = [0]
    wrong = stack.client(auth.cam_passport(counting(CognosPassports.password(stack.cognos.url, "LDAP", "viewer", "wrong"), asked)))
    try:
        with pytest.raises(Tm1AuthenticationError):
            wrong.users.active_user()
        with pytest.raises(Tm1AuthenticationError):
            wrong.users.active_user()
        assert asked[0] == 1, "Cognos asked once: a refusal isn't retried at once"
    finally:
        close_quietly(wrong)
    wrong_namespace = stack.client(auth.cam_namespace("viewer", "wrong", "LDAP"))
    try:
        with pytest.raises(Tm1AuthenticationError):
            wrong_namespace.users.active_user()
    finally:
        close_quietly(wrong_namespace)

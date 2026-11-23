"""Logging on to a TM1 in CAM security through Cognos's OpenID Connect namespace, with Keycloak as
the provider: a password Cognos checks with Keycloak, and an ID token the application holds,
exchanged at Cognos for a passport. A port of the Java client's CamOidcAuthenticationTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1Client, auth

from .conftest import CONFIGURED, REASON

testcontainers = pytest.importorskip("skipcheck_tm1_testcontainers", reason="tm1-testcontainers starts Cognos and the TM1 in CAM security")

# Skipped without Docker, a TM1 source or a Cognos source (Java's @RequiresCognos); never run
# against the TM1 at TM1_URL, which isn't in this Cognos's CAM security
pytestmark = [pytest.mark.skipif(not CONFIGURED, reason=REASON), pytest.mark.requires_cognos]


class Stack:
    """Keycloak, Cognos with an OIDC namespace on it, and a TM1 in CAM security against that
    namespace."""

    def __init__(self) -> None:
        source = testcontainers.CognosSource.discover()
        tm1_source = testcontainers.Tm1Source.discover()
        if source is None or tm1_source is None:
            pytest.skip(testcontainers.RequiresCognos.NO_SOURCE_MESSAGE)
        self.keycloak = testcontainers.KeycloakContainer()
        self.cognos = (
            testcontainers.CognosContainer(source)
            .with_user("admin", "adminpw", "Admins")
            .with_user("viewer", "viewerpw", "Readers")
            .with_keycloak(self.keycloak)
        )
        self.tm1 = testcontainers.Tm1ServerContainer(tm1_source).with_cam_security(self.cognos, testcontainers.Namespace.OIDC, "admin")

    def stop(self) -> None:
        try:
            self.tm1.stop()
        finally:
            try:
                self.cognos.stop()
            finally:
                self.keycloak.stop()

    def client(self, authenticator: Any) -> Tm1Client:
        return Tm1Client.builder().base_url(self.tm1.rest_url).authenticator(authenticator).build()


@pytest.fixture(scope="module")
def stack() -> Iterator[Stack]:
    made = Stack()
    try:
        made.tm1.start()
        yield made
    finally:
        made.stop()


def test_logs_on_with_a_password_cognos_checks_with_the_provider(stack: Stack) -> None:
    with stack.client(auth.cam_namespace("admin", "adminpw", "OIDC")) as admin:
        assert admin.users.active_user().name == 'CAMID("OIDC:u:admin")'


def test_exchanges_an_id_token_for_a_passport(stack: Stack) -> None:
    id_token = auth.cognos_id_token(stack.cognos.url, "OIDC", lambda: stack.keycloak.id_token("viewer", "viewerpw"))
    with stack.client(id_token) as viewer:
        assert 'CAMID("OIDC:g:readers")' in [g.name for g in viewer.users.groups('CAMID("OIDC:u:viewer")')]

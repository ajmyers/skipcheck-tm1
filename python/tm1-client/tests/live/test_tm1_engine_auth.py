# SPDX-License-Identifier: Apache-2.0

"""Logging on to a TM1 v12 engine through its application (service-to-service) and through OAuth 2.0
bearer tokens from Keycloak, including real error classification and refusal caching. A port of the
Java client's Tm1EngineAuthTm1Test."""

from __future__ import annotations

import json
import urllib.parse
import urllib.request
from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1AuthenticationError, Tm1Client, Tm1Dialect, Tm1Error, Tm1ErrorKind, Tm1ForbiddenError, auth

testcontainers = pytest.importorskip("skipcheck_tm1_testcontainers", reason="tm1-testcontainers starts Keycloak and the TM1 v12 engine")

pytestmark = [pytest.mark.requires_tm1_engine]


class Stack:
    """Keycloak and a TM1 v12 engine taking OAuth 2.0 tokens from it, alongside application logins."""

    def __init__(self) -> None:
        source = testcontainers.Tm1EngineSource.discover()
        if source is None:
            pytest.skip(testcontainers.RequiresTm1Engine.NO_SOURCE_MESSAGE)
        self.keycloak = testcontainers.KeycloakContainer()
        self.engine = testcontainers.Tm1EngineContainer(source).with_user("analyst", "Analysts").with_login("analyst", "analystpw").with_oauth2(self.keycloak)

    def stop(self) -> None:
        try:
            self.engine.stop()
        finally:
            self.keycloak.stop()

    @property
    def database(self) -> Any:
        return self.engine.database(testcontainers.Tm1EngineContainer.DEFAULT_DATABASE)

    def client(self, authenticator: Any) -> Tm1Client:
        db = self.database
        return Tm1Client.builder().base_url(db.rest_url).dialect(Tm1Dialect.v12(db.instance, db.name)).authenticator(authenticator).build()

    def master_realm_token(self) -> str:
        """A real JWT signed by Keycloak's master realm, not the realm TM1 trusts."""
        data = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": "admin", "password": "admin"}).encode()
        req = urllib.request.Request(
            f"{self.keycloak.url}/realms/master/protocol/openid-connect/token",
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            assert resp.status == 200
            return str(json.loads(resp.read().decode())["access_token"])


@pytest.fixture(scope="module")
def stack() -> Iterator[Stack]:
    made = Stack()
    try:
        made.engine.start()
        yield made
    finally:
        made.stop()


def close_quietly(client: Tm1Client) -> None:
    try:
        client.close()
    except Tm1Error:
        pass


def test_logs_on_through_application_credentials(stack: Stack) -> None:
    login = stack.database.login("analyst")
    with stack.client(auth.application(login.client_id, login.client_secret, login.user)) as analyst:
        user = analyst.users.active_user(expand="Groups")
        assert (user.name or "").lower() == "analyst"
        assert "Analysts" in [g.name for g in (user.groups or [])]


def test_refuses_wrong_application_secret_and_unknown_user(stack: Stack) -> None:
    login = stack.database.login("analyst")
    wrong_secret = stack.client(auth.application(login.client_id, "wrong-secret", login.user))
    try:
        with pytest.raises(Tm1AuthenticationError) as exc:
            wrong_secret.users.active_user()
        assert exc.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
        assert exc.value.error is not None and exc.value.error.code == "invalid_client"
        with pytest.raises(Tm1AuthenticationError):
            wrong_secret.users.active_user()
    finally:
        close_quietly(wrong_secret)

    # The gateway creates a session for the unknown user; the database refuses it (403)
    unknown_user = stack.client(auth.application(login.client_id, login.client_secret, "nobody_here"))
    try:
        with pytest.raises(Tm1ForbiddenError) as forbidden:
            unknown_user.users.active_user()
        assert forbidden.value.kind is Tm1ErrorKind.FORBIDDEN
    finally:
        close_quietly(unknown_user)


def test_logs_on_with_a_bearer_token(stack: Stack) -> None:
    oauth2 = stack.database.oauth2_login("analyst")
    with stack.client(auth.bearer(oauth2.token)) as supplied:
        user = supplied.users.active_user(expand="Groups")
        assert (user.name or "").lower() == "analyst"
        assert "Analysts" in [g.name for g in (user.groups or [])]

    with stack.client(auth.bearer(oauth2.token())) as fixed:
        assert (fixed.users.active_user().name or "").lower() == "analyst"


def test_refuses_invalid_and_wrong_realm_bearer_tokens_once_without_retrying(stack: Stack) -> None:
    asked = [0]

    def wrong_realm_token() -> str:
        asked[0] += 1
        return stack.master_realm_token()

    wrong_realm = stack.client(auth.bearer(wrong_realm_token))
    try:
        with pytest.raises(Tm1AuthenticationError) as exc:
            wrong_realm.users.active_user()
        assert exc.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
        with pytest.raises(Tm1AuthenticationError):
            wrong_realm.users.active_user()
        assert asked[0] == 1, "supplier asked once: a refusal isn't retried at once"
    finally:
        close_quietly(wrong_realm)

    malformed = stack.client(auth.bearer("not.a.token"))
    try:
        with pytest.raises(Tm1AuthenticationError) as bad:
            malformed.users.active_user()
        assert bad.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED
    finally:
        close_quietly(malformed)

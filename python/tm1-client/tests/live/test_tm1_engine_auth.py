# SPDX-License-Identifier: Apache-2.0

"""Logging on to a TM1 v12 engine through its application (service-to-service) and through OAuth 2.0
bearer tokens from Keycloak. A port of the Java client's Tm1EngineAuthTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1Client, Tm1Dialect, auth

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


@pytest.fixture(scope="module")
def stack() -> Iterator[Stack]:
    made = Stack()
    try:
        made.engine.start()
        yield made
    finally:
        made.stop()


def test_logs_on_through_application_credentials(stack: Stack) -> None:
    login = stack.database.login("analyst")
    with stack.client(auth.application(login.client_id, login.client_secret, login.user)) as analyst:
        assert (analyst.users.active_user().name or "").lower() == "analyst"


def test_logs_on_with_a_bearer_token(stack: Stack) -> None:
    oauth2 = stack.database.oauth2_login("analyst")
    with stack.client(auth.bearer(oauth2.token)) as analyst:
        assert (analyst.users.active_user().name or "").lower() == "analyst"

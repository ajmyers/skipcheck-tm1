"""Pins how TM1 handles ``TM1-Impersonate``, which the session pool relies on: a port of the Java
client's Tm1ImpersonationBehaviorTest. Over bare HTTP, like test_tm1_session_behavior.py. Observed
on 11.8.03500.6 with TM1's own security; CAM may behave differently."""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from typing import Any
from urllib.parse import quote

import httpx
import pytest

from .conftest import CONFIGURED, REASON
from .raw_tm1 import RawTm1

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

ACTIVE_USER = "ActiveUser?$select=Name"


class Users:
    """The test's users, alice (password a) and bob (password b), and what the tests ask as them."""

    def __init__(self, raw: RawTm1, admin: str) -> None:
        self.raw = raw
        self.admin = admin
        run = uuid.uuid4().hex[:6]
        self.alice = "zz_alice_" + run
        self.bob = "zz_bob_" + run

    def impersonate(self, user: str, password: str, target: Any) -> httpx.Response:
        return self.raw.send("GET", ACTIVE_USER, headers={"Authorization": RawTm1.basic(user, password), "TM1-Impersonate": target})

    def create_user(self, name: str, group: str) -> None:
        self.raw.send("POST", "Users", cookie=self.admin, body={"Name": name, "Password": "p", "Groups@odata.bind": [f"Groups('{group}')"]})

    def name(self, **request: Any) -> str:
        return self.raw.send("GET", ACTIVE_USER, **request).json()["Name"]

    def close(self, response: httpx.Response) -> None:
        cookie = RawTm1.cookie(response)
        if cookie is not None:
            self.raw.close(cookie)


@pytest.fixture
def users(raw: RawTm1, admin: str) -> Iterator[Users]:
    made = Users(raw, admin)
    raw.create_user(admin, made.alice, "a")
    raw.create_user(admin, made.bob, "b")
    try:
        yield made
    finally:
        raw.delete_user(admin, made.alice)
        raw.delete_user(admin, made.bob)


def test_decides_the_identity_at_login_and_ignores_the_header_afterwards(raw: RawTm1, users: Users) -> None:
    """Impersonation is decided at login: the session becomes that user, and the header on later
    requests is ignored, whatever it says. So it's a session setting, and one identity means one
    session."""
    alice, bob = users.alice, users.bob
    login = raw.send("GET", ACTIVE_USER, basic=True, headers={"TM1-Impersonate": alice})
    assert login.status_code == 200
    assert login.json()["Name"] == alice
    session = RawTm1.cookie(login)
    assert session is not None
    try:
        assert users.name(cookie=session) == alice
        assert users.name(cookie=session, headers={"TM1-Impersonate": bob}) == alice, "a different user on a later request"

        plain = raw.login()
        assert users.name(cookie=plain, headers={"TM1-Impersonate": alice}) == "Admin", "impersonation on a later request of an admin session"
        raw.close(plain)
    finally:
        raw.close(session)


def test_impersonates_on_a_login_that_reuses_the401s_session(raw: RawTm1, users: Users) -> None:
    """It also works when logging in the session a 401 opened, as the client's re-login does."""
    seed = RawTm1.cookie(raw.send("GET", ACTIVE_USER))
    assert seed is not None
    try:
        assert users.name(cookie=seed, basic=True, headers={"TM1-Impersonate": users.alice}) == users.alice
    finally:
        raw.close(seed)


def test_refuses_unknown_users_and_non_administrators_with_plain_text_errors(raw: RawTm1, users: Users) -> None:
    """Impersonating a user that doesn't exist is a 400, and a non-administrator trying to
    impersonate is a 403. Both bodies are plain text, not OData errors: a status line, then the
    message. The client reads such a body as the error message, so the classifier's message rules
    apply. Each leaves a session with no user behind (refused on use, so not a way in as the
    administrator)."""
    unknown = raw.send("GET", ACTIVE_USER, basic=True, headers={"TM1-Impersonate": "zz_nobody"})
    assert unknown.status_code == 400
    assert unknown.text.startswith("Error: Bad Request")
    assert "The user specified in the impersonation request does not exist." in unknown.text
    left = RawTm1.cookie(unknown)
    assert left is not None
    assert raw.send("GET", ACTIVE_USER, cookie=left).status_code == 401, "the session a refused impersonation leaves"

    non_admin = users.impersonate(users.alice, "a", users.bob)
    assert non_admin.status_code == 403
    assert non_admin.text.startswith("Error: Forbidden")
    assert "You need to be a (data-)administrator to be able to impersonate another user." in non_admin.text


def test_refuses_to_impersonate_any_administrator(raw: RawTm1, admin: str, users: Users) -> None:
    """Nobody can impersonate an administrator, not even another administrator, and that includes
    the built-in Admin. TM1 itself blocks this escalation, with a 403 that says so."""
    other_admin = users.alice + "_adm"
    data_admin = users.alice + "_data"
    users.create_user(other_admin, "ADMIN")
    users.create_user(data_admin, "DataAdmin")
    try:
        for user, password, target in (("admin", "", other_admin), ("admin", "", "Admin"), (data_admin, "p", other_admin), (data_admin, "p", "Admin")):
            refused = users.impersonate(user, password, target)
            assert refused.status_code == 403, f"{user} impersonating {target}"
            assert "You have insufficient rights to impersonate the user specified!" in refused.text
    finally:
        raw.delete_user(admin, other_admin)
        raw.delete_user(admin, data_admin)


def test_lets_data_administrators_impersonate_but_not_other_administrator_roles(raw: RawTm1, admin: str, users: Users) -> None:
    """Data administrators can impersonate ordinary users; security and operations administrators
    can't, with a different 403 message."""
    data_admin = users.alice + "_data"
    security_admin = users.alice + "_sec"
    operations_admin = users.alice + "_ops"
    users.create_user(data_admin, "DataAdmin")
    users.create_user(security_admin, "SecurityAdmin")
    users.create_user(operations_admin, "OperationsAdmin")
    try:
        allowed = users.impersonate(data_admin, "p", users.bob)
        assert allowed.status_code == 200
        users.close(allowed)
        for role in (security_admin, operations_admin):
            refused = users.impersonate(role, "p", users.bob)
            assert refused.status_code == 403, role
            assert "You need to be a (data-)administrator to be able to impersonate another user." in refused.text
    finally:
        raw.delete_user(admin, data_admin)
        raw.delete_user(admin, security_admin)
        raw.delete_user(admin, operations_admin)


def test_refuses_a_disabled_user_like_wrong_credentials(raw: RawTm1, admin: str, users: Users) -> None:
    """Impersonating a disabled user is a 401 with an empty body: exactly what wrong credentials
    look like. The client can't tell the two apart, so it reports both."""
    raw.send("PATCH", f"Users('{users.bob}')", cookie=admin, body={"Enabled": False})
    refused = users.impersonate("admin", "", users.bob)
    assert refused.status_code == 401
    assert refused.text == ""


def test_treats_an_empty_header_as_no_impersonation(users: Users) -> None:
    """An empty ``TM1-Impersonate`` means no impersonation: the session runs as the user who
    logged in, here the administrator. So the client must never send a blank name."""
    login = users.impersonate("admin", "", "")
    try:
        assert login.status_code == 200
        assert login.json()["Name"] == "Admin"
    finally:
        users.close(login)


def test_matches_user_names_ignoring_case_and_spaces(users: Users) -> None:
    """User names match ignoring case and spaces, as TM1 object names do, so the pool must compare
    identities the same way, or one user would get two sessions."""
    for written in (users.alice.upper(), users.alice.replace("_", "_ ")):
        login = users.impersonate("admin", "", written)
        try:
            assert login.status_code == 200, written
            assert login.json()["Name"] == users.alice, written
        finally:
            users.close(login)


def test_cannot_impersonate_users_with_non_ascii_names(raw: RawTm1, admin: str, users: Users) -> None:
    """A user whose name has non-ASCII characters can't be impersonated by a name sent as a header
    is read (ISO 8859-1), or percent-encoded: TM1 reports them unknown. The client refuses such
    names before sending anything.

    Sent as UTF-8 bytes, TM1 does find the user. Java's test says it doesn't, but its HTTP client
    can't put those bytes in a header, so it never asks TM1 that."""
    accented = users.alice + "_ë"
    raw.create_user(admin, accented, "p")
    try:
        for written in (accented.encode("iso-8859-1"), quote(accented, safe="")):
            refused = users.impersonate("admin", "", written)
            assert refused.status_code == 400
            assert "The user specified in the impersonation request does not exist." in refused.text
        found = users.impersonate("admin", "", accented.encode("utf-8"))
        try:
            assert found.status_code == 200
            assert found.json()["Name"] == accented
        finally:
            users.close(found)
    finally:
        raw.delete_user(admin, quote(accented, safe=""))

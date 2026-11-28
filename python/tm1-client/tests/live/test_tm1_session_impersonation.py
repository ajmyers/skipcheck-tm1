# SPDX-License-Identifier: Apache-2.0

"""Impersonation through Tm1Session, against a real TM1: a port of the Java client's
Tm1SessionImpersonationTm1Test."""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from urllib.parse import quote

import pytest

from skipcheck_tm1 import ODataPath, ODataQuery, Tm1ApiError, Tm1ErrorKind, Tm1Request, Tm1Session

from .conftest import CONFIGURED, IS_ENGINE, REASON, session
from .raw_tm1 import RawTm1

pytestmark = [
    pytest.mark.skipif(not CONFIGURED, reason=REASON),
    pytest.mark.skipif(IS_ENGINE, reason="TM1 v12 does not support TM1-Impersonate; pass the user to application auth"),
]

ACTIVE_USER = Tm1Request.get(ODataPath.of("ActiveUser"), ODataQuery.none().select("Name"), dict)


@pytest.fixture
def alice(raw: RawTm1, admin: str) -> Iterator[str]:
    name = "zz_alice_" + uuid.uuid4().hex[:6]
    raw.create_user(admin, name, "a")
    try:
        yield name
    finally:
        raw.delete_user(admin, name)


def kind_of(s: Tm1Session) -> Tm1ErrorKind:
    with pytest.raises(Tm1ApiError) as refused:
        s.execute(ACTIVE_USER)
    return refused.value.kind


def test_runs_as_the_impersonated_user(alice: str) -> None:
    with session(impersonate=alice.upper()) as s:
        assert s.execute(ACTIVE_USER)["Name"] == alice


def test_runs_as_a_user_whose_name_is_outside_ascii(raw: RawTm1, admin: str, alice: str) -> None:
    for name in (alice + "_ë", alice + "_日本"):
        raw.create_user(admin, name, "a")
        try:
            with session(impersonate=name) as s:
                assert s.execute(ACTIVE_USER)["Name"] == name
        finally:
            raw.delete_user(admin, quote(name, safe=""))


def test_reports_an_unknown_user() -> None:
    assert kind_of(session(impersonate="zz_nobody")) is Tm1ErrorKind.UNKNOWN_USER


def test_reports_that_administrators_cant_be_impersonated() -> None:
    assert kind_of(session(impersonate="Admin")) is Tm1ErrorKind.IMPERSONATION_REFUSED


def test_reports_a_disabled_user_as_refused_credentials(raw: RawTm1, admin: str, alice: str) -> None:
    raw.send("PATCH", f"Users('{alice}')", cookie=admin, body={"Enabled": False})
    assert kind_of(session(impersonate=alice)) is Tm1ErrorKind.CREDENTIAL_REJECTED


def test_doesnt_log_in_again_while_a_refusal_is_remembered(raw: RawTm1, admin: str) -> None:
    """Each refused login leaves a session on TM1; a remembered refusal doesn't add more."""
    s = session(impersonate="zz_nobody")
    assert kind_of(s) is Tm1ErrorKind.UNKNOWN_USER
    after = raw.all_session_count(admin)
    for _ in range(5):
        assert kind_of(s) is Tm1ErrorKind.UNKNOWN_USER
    assert raw.all_session_count(admin) == after

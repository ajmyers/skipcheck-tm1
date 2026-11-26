# SPDX-License-Identifier: Apache-2.0

"""The example tests' own TM1 REST session (tm1_rest.py), without TM1."""

from __future__ import annotations

from typing import Any

import httpx2
import pytest
from tm1_rest import Tm1Rest


class _Tm1:
    rest_url = "http://localhost:1/api/v1/"
    authorization = "Basic YWRtaW46YXBwbGU="


class _Answer:
    status_code = 200
    text = '{"Name":"Admin"}'

    def __init__(self, cookies: dict[str, str]) -> None:
        self.cookies = cookies


def _record(monkeypatch: pytest.MonkeyPatch, cookies: dict[str, str]) -> list[tuple[str, str, dict[str, str]]]:
    sent: list[tuple[str, str, dict[str, str]]] = []

    def answer(method: str) -> Any:
        def send(url: str, *, headers: dict[str, str], **_: Any) -> _Answer:
            sent.append((method, url, headers))
            return _Answer(cookies)

        return send

    monkeypatch.setattr(httpx2, "get", answer("GET"))
    monkeypatch.setattr(httpx2, "post", answer("POST"))
    return sent


def test_posts_with_the_session_cookie_of_its_logon(monkeypatch: pytest.MonkeyPatch) -> None:
    sent = _record(monkeypatch, {"TM1SessionId": "abc"})

    Tm1Rest(_Tm1()).post("Cubes", "{}")  # type: ignore[arg-type]

    assert [(method, url) for method, url, _ in sent] == [
        ("GET", "http://localhost:1/api/v1/ActiveUser?$select=Name"),
        ("POST", "http://localhost:1/api/v1/Cubes"),
    ]
    assert sent[0][2] == {"Authorization": _Tm1.authorization}
    assert sent[1][2] == {"Content-Type": "application/json", "Cookie": "TM1SessionId=abc"}


def test_refuses_to_post_when_the_logon_sent_no_session_cookie(monkeypatch: pytest.MonkeyPatch) -> None:
    sent = _record(monkeypatch, {})

    with pytest.raises(RuntimeError, match="TM1 sent no TM1SessionId cookie at logon"):
        Tm1Rest(_Tm1()).post("Cubes", "{}")  # type: ignore[arg-type]

    assert [method for method, _, _ in sent] == ["GET"], "no POST without a session"

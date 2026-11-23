"""CAM passports from Cognos, as the Java client's CognosPassportsTest, in both flavors, against a
stand-in for the Cognos dispatcher."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import httpx
import pytest

from tm1_client import Tm1AuthenticationError, Tm1ErrorKind, auth
from tm1_client.aio import cognos as aio_cognos


class Cognos:
    def __init__(self, status: int = 200) -> None:
        self.bodies: list[dict[str, Any]] = []
        self.status = status

    def handler(self, request: httpx.Request) -> httpx.Response:
        assert request.method == "PUT" and request.url.path == "/api/v1/session"
        self.bodies.append(json.loads(request.content))
        if self.status != 200:
            return httpx.Response(self.status, json={"errorCodeString": "camAuthUserRecoverable"})
        return httpx.Response(200, json={}, headers={"Set-Cookie": f"cam_passport=passport-{len(self.bodies)}; Path=/"})


def run(value: Any) -> Any:
    return asyncio.run(value) if asyncio.iscoroutine(value) else value


FLAVORS = {
    "sync": (auth.CognosPassports, auth.cognos_logon, httpx.Client),
    "async": (aio_cognos.CognosPassports, aio_cognos.cognos_logon, httpx.AsyncClient),
}


@pytest.mark.parametrize("flavor", FLAVORS)
def test_logs_on_for_a_fresh_passport_each_time(flavor: str) -> None:
    passports_of, _, client = FLAVORS[flavor]
    cognos = Cognos()
    passports = passports_of.password(
        "http://cognos:9300/bi/", "LDAP", "alice", "secret", http_client=lambda: client(transport=httpx.MockTransport(cognos.handler))
    )
    assert run(passports()) == "passport-1"
    assert run(passports()) == "passport-2"
    assert cognos.bodies[0] == {
        "parameters": [
            {"name": "CAMNamespace", "value": "LDAP"},
            {"name": "CAMUsername", "value": "alice"},
            {"name": "CAMPassword", "value": "secret"},
        ]
    }


@pytest.mark.parametrize("flavor", FLAVORS)
def test_sends_an_id_token_rather_than_a_password(flavor: str) -> None:
    passports_of, _, client = FLAVORS[flavor]
    cognos = Cognos()
    passports = passports_of.id_token(
        "http://cognos:9300", "OIDC", lambda: "eyJ.token", http_client=lambda: client(transport=httpx.MockTransport(cognos.handler))
    )
    assert run(passports()) == "passport-1"
    assert {"name": "CAMOIDCIdToken", "value": "eyJ.token"} in cognos.bodies[0]["parameters"]


@pytest.mark.parametrize("flavor", FLAVORS)
def test_refuses_wrong_credentials_as_rejected(flavor: str) -> None:
    passports_of, _, client = FLAVORS[flavor]
    cognos = Cognos(status=401)
    passports = passports_of.password("http://cognos:9300", "LDAP", "alice", "wrong", http_client=lambda: client(transport=httpx.MockTransport(cognos.handler)))
    with pytest.raises(Tm1AuthenticationError) as refused:
        run(passports())
    assert refused.value.kind is Tm1ErrorKind.CREDENTIAL_REJECTED


@pytest.mark.parametrize("flavor", FLAVORS)
def test_describes_without_the_secret(flavor: str) -> None:
    _, logon, client = FLAVORS[flavor]
    cognos = Cognos()
    cam = logon("http://cognos:9300", "LDAP", "viewer", "viewerpw", http_client=lambda: client(transport=httpx.MockTransport(cognos.handler)))
    assert cam.describe() == "CAMPassport from Cognos LDAP/viewer"
    assert "viewerpw" not in repr(cam)
    assert run(cam.authorization()) == "CAMPassport passport-1"

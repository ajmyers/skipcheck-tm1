"""CAM passports from Cognos itself, as the Java client's ``CognosPassports``: a logon to the
Cognos dispatcher (``PUT <dispatcher>/api/v1/session``) by password, or by exchanging an
OpenID Connect ID token, for each TM1 login, so an expired passport is never reused::

    authenticator = cognos_logon("http://cognos:9300", "LDAP", "alice", "secret")

Unlike ``cam_namespace``, TM1 never sees the password, only Cognos does. A logon Cognos refuses
(401 or 403) is a ``Tm1AuthenticationError`` (``CREDENTIAL_REJECTED``), which the session
doesn't retry at once. Cognos gets its own HTTP client: it's another server, with no TM1 cookie.

This module is the source of truth for both flavors (``tm1_pygen.unasync``).
"""

from __future__ import annotations

import inspect
import re
from typing import Any, Awaitable, Callable

import httpx

from .._compat import AsyncIO as runtime
from ..auth import Authenticator, _Passport
from ..errors import ODataError, Tm1ApiError, Tm1ErrorKind, Tm1ProtocolError

__all__ = ["CognosPassports", "cognos_id_token", "cognos_logon"]


def _session(dispatcher: str) -> str:
    root = re.sub(r"/bi$", "", re.sub(r"/+$", "", dispatcher))
    return root + "/api/v1/session"


class _Logon:
    """A passport supplier: logs on to Cognos each time it's called."""

    def __init__(self, dispatcher: str, namespace: str, credentials: Callable[[], Any], kind: str, http: Callable[[], Any] | None):
        if dispatcher is None or namespace is None:
            raise TypeError("dispatcher and namespace are required")
        self._url = _session(dispatcher)
        self._namespace = namespace
        self._credentials = credentials
        self._kind = kind
        self._http = http or runtime.HttpClient

    async def __call__(self) -> str:
        parameters = self._credentials()
        if inspect.isawaitable(parameters):
            parameters = await parameters
        body = {"parameters": [{"name": "CAMNamespace", "value": self._namespace}, *parameters]}
        async with self._http() as http:
            try:
                response = await http.put(self._url, json=body)
            except httpx.HTTPError as e:
                raise Tm1ProtocolError(f"Logging on to Cognos failed: {e}", None, "PUT", self._url) from e
        if response.status_code in (401, 403):
            raise Tm1ApiError.of_kind(
                response.status_code, Tm1ErrorKind.CREDENTIAL_REJECTED, ODataError(None, "Cognos refused the logon"), "PUT", self._url
            )
        passport = response.cookies.get("cam_passport")
        if response.status_code >= 300 or not passport:
            raise Tm1ProtocolError(
                f"Cognos answered the logon with {response.status_code} and no cam_passport cookie", None, "PUT", self._url
            )
        return passport

    def __repr__(self) -> str:
        # Never the password or the token
        return f"CognosPassports[{self._namespace}, {self._kind}]"


class CognosPassports:
    """Passport suppliers for ``cam_passport``: each call logs on to Cognos for a fresh one."""

    @staticmethod
    def password(
        dispatcher: str, namespace: str, user: str, password: str, *, http_client: Callable[[], Any] | None = None
    ) -> Callable[[], Awaitable[str]]:
        """By user name and password (``CAMUsername``, ``CAMPassword``). ``http_client`` makes the
        HTTP client to reach Cognos with (for TLS settings, proxies, tests)."""
        if user is None or password is None:
            raise TypeError("user and password are required")
        params = [{"name": "CAMUsername", "value": user}, {"name": "CAMPassword", "value": password}]
        return _Logon(dispatcher, namespace, lambda: params, "password", http_client)

    @staticmethod
    def id_token(
        dispatcher: str, namespace: str, id_token: Callable[[], Any], *, http_client: Callable[[], Any] | None = None
    ) -> Callable[[], Awaitable[str]]:
        """By an OpenID Connect ID token (``CAMOIDCIdToken``) the caller already holds; asked
        for at each logon."""
        if id_token is None:
            raise TypeError("id_token is required")

        async def parameters() -> list[dict[str, str]]:
            token = id_token()
            if inspect.isawaitable(token):
                token = await token
            return [{"name": "CAMOIDCIdToken", "value": token}]

        return _Logon(dispatcher, namespace, parameters, "ID token", http_client)


def cognos_logon(
    dispatcher: str, namespace: str, user: str, password: str, *, http_client: Callable[[], Any] | None = None
) -> Authenticator:
    """CAM security with a passport from Cognos, logging on with a user name and password:
    a fresh passport for each login (Java's ``Tm1Authenticator.cognosLogon``)."""
    passports = CognosPassports.password(dispatcher, namespace, user, password, http_client=http_client)
    return _Passport(passports, f"CAMPassport from Cognos {namespace}/{user}")


def cognos_id_token(
    dispatcher: str, namespace: str, id_token: Callable[[], Any], *, http_client: Callable[[], Any] | None = None
) -> Authenticator:
    """CAM security with a passport from Cognos for an OpenID Connect ID token (Java's
    ``Tm1Authenticator.cognosIdToken``)."""
    passports = CognosPassports.id_token(dispatcher, namespace, id_token, http_client=http_client)
    return _Passport(passports, f"CAMPassport from Cognos {namespace} (ID token)")

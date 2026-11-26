# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1Rest (a test helper there too): a minimal TM1 REST session for the example tests. It
logs in once, reuses the session cookie, and closes the session when done, since TM1 opens a new
session for every request that doesn't send one back."""

from __future__ import annotations

import re
from typing import Any, Self

import httpx2

from skipcheck_tm1_testcontainers import Tm1ServerContainer

_COUNT = re.compile(r"\d+")
_TIMEOUT = 30


class Tm1Rest:
    def __init__(self, tm1: Tm1ServerContainer, authorization: str | None = None) -> None:
        # A session with its own login, e.g. a Cognos user's CognosContainer.cam_authorization
        self._base_url = tm1.rest_url
        self._authorization = tm1.authorization if authorization is None else authorization
        self._cookie: str | None = None

    def get(self, path: str) -> str:
        """GETs a path under the service root and returns the body. Fails on anything but 200."""
        headers = {"Authorization": self._authorization} if self._cookie is None else {"Cookie": self._cookie}
        response = httpx2.get(self._base_url + path, headers=headers, timeout=_TIMEOUT)
        if response.status_code != 200:
            raise RuntimeError(f"GET {path} returned {response.status_code}: {response.text}")
        if self._cookie is None:
            session = response.cookies.get("TM1SessionId")
            self._cookie = None if session is None else f"TM1SessionId={session}"
        return response.text

    def post(self, path: str, json: str) -> httpx2.Response:
        """POSTs a JSON body to a path under the service root and returns the response, whatever
        its status."""
        if self._cookie is None:
            self.get("ActiveUser?$select=Name")  # logs in and keeps the session cookie
            if self._cookie is None:
                # Without it TM1 would open a session for the POST, and one for every request after
                raise RuntimeError("TM1 sent no TM1SessionId cookie at logon")
        return httpx2.post(self._base_url + path, content=json, headers=self._session_headers(), timeout=_TIMEOUT)

    def model_cube_count(self) -> int:
        """The number of cubes, excluding control cubes (whose names start with '}')."""
        count = self.get("Cubes/$count?$filter=not%20startswith(Name,'%7D')").strip()
        if _COUNT.fullmatch(count) is None:
            raise RuntimeError(f"Not a count: {count}")
        return int(count)

    def close(self) -> None:
        if self._cookie is not None:
            httpx2.post(self._base_url + "ActiveSession/tm1.Close", content="{}", headers=self._session_headers(), timeout=_TIMEOUT)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def _session_headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self._cookie is not None:
            headers["Cookie"] = self._cookie
        return headers

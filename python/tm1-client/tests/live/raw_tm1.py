"""Bare HTTP to TM1, as the Java tests' RawTm1: for tests that pin how TM1 itself behaves,
independent of the client.

The HTTP client keeps no cookies, so a request carries exactly the credentials and cookie it's
given. That matters: a request with Basic credentials and no cookie silently opens a new TM1
session, which would hide what a cookie-only request does.
"""

from __future__ import annotations

import base64
import os
import time
import uuid
from concurrent.futures import Future, ThreadPoolExecutor
from http.cookiejar import CookieJar, DefaultCookiePolicy
from typing import Any

import httpx

from .conftest import target

COOKIE = "TM1SessionId"


class RawTm1:
    def __init__(self) -> None:
        url, user, password = target()
        self.root = url.rstrip("/") + "/"
        self.user = user
        self.password = password
        self._basic = RawTm1.basic(user, password)
        verify = os.environ.get("TM1_VERIFY", "true").lower() != "false"
        # A jar that takes no cookie: httpx would otherwise send back what TM1 sets
        nothing = CookieJar(DefaultCookiePolicy(allowed_domains=[]))
        self._http = httpx.Client(cookies=nothing, verify=verify, timeout=120)
        self._threads = ThreadPoolExecutor(max_workers=8)

    def shutdown(self) -> None:
        self._threads.shutdown(wait=False)
        self._http.close()

    def login(self) -> str:
        """Logs in with Basic credentials and no cookie, and returns the new session's cookie."""
        cookie = RawTm1.cookie(self.send("GET", "ActiveSession?$select=ID", basic=True))
        assert cookie is not None, "a login sets the session's cookie"
        return cookie

    def session_id(self, cookie: str) -> int:
        """The ID of the session behind a cookie."""
        return int(self.send("GET", "ActiveSession?$select=ID", cookie=cookie).json()["ID"])

    def close(self, cookie: str) -> None:
        """Closes the session behind a cookie."""
        self.send("POST", "ActiveSession/tm1.Close", cookie=cookie, body="{}")

    def kill(self, admin: str, id: int) -> int:
        """Kills another session by ID, through ``Sessions(id)/tm1.Close``, from ``admin``'s."""
        return self.send("POST", f"Sessions({id})/tm1.Close", cookie=admin, body="{}").status_code

    def await_gone(self, admin: str, id: int, timeout: float = 30) -> bool:
        """Waits until session ``id`` is no longer listed, since TM1 takes a few seconds to drop a
        closed session. Whether it went within ``timeout`` seconds."""
        deadline = time.monotonic() + timeout
        while self.send("GET", f"Sessions({id})?$select=ID", cookie=admin).status_code == 200:
            if time.monotonic() > deadline:
                return False
            time.sleep(0.25)
        return True

    def await_session_count(self, admin: str, context: str, expected: int, timeout: float = 30) -> int:
        """Waits until TM1 lists ``expected`` sessions labelled ``context``, and returns the last
        count seen."""
        deadline = time.monotonic() + timeout
        count = self.session_count(admin, context)
        while count != expected and time.monotonic() < deadline:
            time.sleep(0.25)
            count = self.session_count(admin, context)
        return count

    def create_user(self, admin: str, name: str, password: str) -> None:
        """Creates a TM1 user with no groups (so not an administrator), from ``admin``'s session."""
        created = self.send("POST", "Users", cookie=admin, body={"Name": name, "Password": password})
        if created.status_code != 201:
            raise RuntimeError(f"Couldn't create user {name}: {created.status_code} {created.text}")

    def delete_user(self, admin: str, name: str) -> None:
        """Deletes a TM1 user, if it exists."""
        self.send("DELETE", f"Users('{name}')", cookie=admin)

    @staticmethod
    def basic(user: str, password: str) -> str:
        """``Basic`` credentials for a user."""
        return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode("ascii")

    def session_count(self, admin: str, context: str) -> int:
        """How many sessions labelled ``context`` (their ``TM1-SessionContext``) TM1 has open, seen
        from ``admin``'s session. Leak checks count a label unique to the test: the server is
        shared, so the total moves for reasons the test doesn't control (closed sessions TM1 lists
        for a few seconds more, sessions every 401 and anonymous read opens)."""
        sessions = self.send("GET", "Sessions?$select=ID,Context", cookie=admin).json()["value"]
        return sum(1 for s in sessions if (s.get("Context") or "") == context)

    @staticmethod
    def unique_context() -> str:
        """A label unique to one test, for :meth:`session_count`."""
        return f"test-{uuid.uuid4()}"

    def all_session_count(self, admin: str) -> int:
        """How many sessions TM1 has open, those with no user included, for tests about those."""
        return len(self.send("GET", "Sessions?$select=ID", cookie=admin).json()["value"])

    def send(
        self,
        method: str,
        path: str,
        *,
        basic: bool = False,
        cookie: str | None = None,
        headers: dict[str, Any] | None = None,
        body: Any = None,
    ) -> httpx.Response:
        """Sends one request: a method, a path relative to the service root, and optionally the
        Basic credentials, a cookie (``name=value``), headers, and a JSON body (text, or a value to
        encode). A GET or DELETE whose reused connection turns out to be closed, with no response
        at all, is sent once more: TM1 now and then closes a kept-alive connection."""
        sent: dict[str, Any] = {}
        if basic:
            sent["Authorization"] = self._basic
        if cookie is not None:
            sent["Cookie"] = cookie
        sent.update(headers or {})
        content = None
        if body is not None:
            content = body.encode("utf-8") if isinstance(body, str) else httpx.Request("POST", self.root, json=body).content
            sent["Content-Type"] = "application/json"
        request = self._http.build_request(method, self.root + path, headers=sent, content=content)
        try:
            return self._http.send(request)
        except httpx.RemoteProtocolError:
            if method in ("GET", "DELETE"):
                return self._http.send(self._http.build_request(method, self.root + path, headers=sent, content=content))
            raise

    def send_async(self, method: str, path: str, **settings: Any) -> Future[httpx.Response]:
        """Sends a request on another thread, for requests that run at the same time."""
        return self._threads.submit(self.send, method, path, **settings)

    @staticmethod
    def cookie(response: httpx.Response) -> str | None:
        """The ``TM1SessionId`` cookie a response sets, as ``name=value``."""
        for value in response.headers.get_list("set-cookie"):
            if value.startswith(COOKIE + "="):
                return value.split(";", 1)[0]
        return None

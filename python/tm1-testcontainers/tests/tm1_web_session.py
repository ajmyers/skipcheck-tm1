# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1WebSession (a test helper there too): TM1 Web's DWR calls, as its pages make them."""

from __future__ import annotations

import json
import re
import uuid
from urllib.parse import urljoin, urlsplit

import httpx

from skipcheck_tm1_testcontainers import Tm1WebContainer

# One line per callback: "." stops at the line end, so the match ends at its ");"
_CALLBACK = re.compile(r'handleCallback\("\d+","0",(.*)\);')
_ERROR = re.compile(r"handle(?:Batch)?Exception\((.*?)\);", re.S)
_NAME = re.compile(r'name:"([^"]*)"')
_SESSION_ID = re.compile(r'tm1SessionId:"([^"]*)"')


class Tm1WebSession:
    def __init__(self, web: Tm1WebContainer) -> None:
        self._base = web.url
        self._path = urlsplit(self._base).path
        self._http = httpx.Client(timeout=30)
        self._batch = 0
        self._script_session_id = ""
        self._http.get(self._base)
        session = self._call("__System", "generateId").strip().strip('"')
        self._http.cookies.set("DWRSESSIONID", session, path=self._path.rstrip("/"))
        self._script_session_id = f"{session}/{uuid.uuid4()}"

    def servers(self, admin_host: str) -> list[str]:
        return _NAME.findall(self._call("TM1Service", "getTM1Servers", "string:" + admin_host))

    def login(self, admin_host: str, server: str, username: str, password: str) -> str:
        result = self._call("TM1Service", "login", "string:" + admin_host, "string:" + server, "string:" + username, "string:" + password)
        match = _SESSION_ID.search(result)
        if match is None:
            raise RuntimeError(f"TM1 Web logged on but sent no TM1 session ID: {result}")
        return match.group(1)

    def evaluate_mdx(self, service_root: str, server: str, tm1_session_id: str, mdx: str) -> str:
        body = {
            "tokens": [{"host": service_root, "type": "tm1", "id": tm1_session_id}],
            "mappings": [{"server": server, "host": service_root, "datasource": server}],
            "workEntries": [{"type": "mdx", "id": "1", "server": server, "args": [mdx]}],
        }
        return self._http.post(urljoin(self._base, "/evalservice/batch"), content=json.dumps(body), headers={"Content-Type": "application/json"}).text

    def _call(self, script: str, method: str, *params: str) -> str:
        self._batch += 1
        lines = ["callCount=1", "nextReverseAjaxIndex=0", f"c0-scriptName={script}", f"c0-methodName={method}", "c0-id=0"]
        lines += [f"c0-param{i}={p}" for i, p in enumerate(params)]
        lines += [
            f"batchId={self._batch}",
            "instanceId=0",
            f"page={self._path}",
            f"httpSessionId={self._http.cookies.get('JSESSIONID') or ''}",
            f"scriptSessionId={self._script_session_id}",
        ]
        response = self._http.post(
            urljoin(self._base, f"dwr/call/plaincall/{script}.{method}.dwr"), content="\n".join(lines) + "\n", headers={"Content-Type": "text/plain"}
        )
        result = _CALLBACK.search(response.text)
        if response.status_code == 200 and result is not None:
            return result.group(1)
        error = _ERROR.search(response.text)
        raise RuntimeError(f"{script}.{method} failed (HTTP {response.status_code}): {error.group(1) if error else response.text}")

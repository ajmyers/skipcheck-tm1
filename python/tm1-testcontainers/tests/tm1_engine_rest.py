# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineRest (a test helper there too): plain HTTP against a TM1 v12 database, for the
tests: no client library, so a client bug can't break the tests of the containers."""

from __future__ import annotations

from typing import Any

import httpx2

from skipcheck_tm1_testcontainers import Tm1Database, Tm1Login


def get(database: Tm1Database, login: Tm1Login, path: str) -> httpx2.Response:
    """GETs a path of the database's REST API, logged in."""
    return send("GET", database.rest_url + path, headers=login.authorize({}))


def post(database: Tm1Database, login: Tm1Login, path: str, json: str) -> httpx2.Response:
    """POSTs JSON to a path of the database's REST API, logged in."""
    return send("POST", database.rest_url + path, headers={**login.authorize({}), "Content-Type": "application/json"}, content=json)


def delete(database: Tm1Database, login: Tm1Login, path: str) -> httpx2.Response:
    """DELETEs a path of the database's REST API, logged in."""
    return send("DELETE", database.rest_url + path, headers=login.authorize({}))


def send(method: str, url: str, **request: Any) -> httpx2.Response:
    """Sends a request of your own making."""
    return httpx2.request(method, url, timeout=60, **request)

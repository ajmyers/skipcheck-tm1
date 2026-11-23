# SPDX-License-Identifier: Apache-2.0

"""The few primitives the asyncio and sync flavors do differently.

The asyncio code (:mod:`skipcheck_tm1.aio`) is the source of truth; the sync flavor
(``skipcheck_tm1._sync``) is generated from it by ``tm1_pygen.unasync``, which turns
``AsyncIO`` into ``SyncIO``, drops ``async`` and ``await``, and renames ``Async*`` types.
"""

from __future__ import annotations

import asyncio
import inspect
import threading
import time
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

import httpx

T = TypeVar("T")


class Expired(Exception):
    """``within`` gave up waiting. Its own type, so that a ``TimeoutError`` the call raises itself
    isn't taken for it."""


class AsyncIO:
    Lock = asyncio.Lock
    HttpClient = httpx.AsyncClient
    sleep = staticmethod(asyncio.sleep)

    @staticmethod
    def shield(awaitable: Awaitable[T]) -> Awaitable[T]:
        """Runs ``awaitable`` to the end even if the caller is cancelled (cleanup)."""
        return asyncio.shield(awaitable)

    @staticmethod
    def monotonic() -> float:
        return time.monotonic()

    @staticmethod
    async def resolve(value: Any) -> Any:
        """What a caller's function returned: awaited, if it's awaitable."""
        return await value if inspect.isawaitable(value) else value

    @staticmethod
    async def within(seconds: float, call: Callable[[], Any]) -> Any:
        """What a caller's function returns, awaited if it's awaitable, or :class:`Expired` if that
        takes longer than ``seconds``; what it was awaiting is cancelled then. Only the awaiting
        is timed: a function that blocks instead of awaiting holds the event loop, and nothing
        here can interrupt it."""
        value = call()
        if not inspect.isawaitable(value):
            return value

        async def outcome() -> tuple[bool, Any]:
            # Its own failures as values, so that a TimeoutError of its own isn't taken for ours
            try:
                return True, await value
            except Exception as e:  # noqa: BLE001 (raised again below)
                return False, e

        try:
            ok, result = await asyncio.wait_for(outcome(), seconds)
        except TimeoutError:
            raise Expired() from None
        if not ok:
            raise result
        return result


class _SyncLock:
    """A re-entrant-free lock used with ``with``, as ``asyncio.Lock`` is with ``async with``."""

    def __init__(self) -> None:
        self._lock = threading.Lock()

    def __enter__(self) -> _SyncLock:
        self._lock.acquire()
        return self

    def __exit__(self, *exc: Any) -> None:
        self._lock.release()


class SyncIO:
    Lock = _SyncLock
    HttpClient = httpx.Client
    sleep = staticmethod(time.sleep)

    @staticmethod
    def shield(value: T) -> T:
        return value

    @staticmethod
    def resolve(value: Any) -> Any:
        return value

    @staticmethod
    def within(seconds: float, call: Callable[[], Any]) -> Any:
        """What a caller's function returns, however long it takes: a call that blocks can only be
        given up on from another thread, and the library starts none (a thread it left behind
        would run outside the caller's call). So the sync flavor never raises :class:`Expired`."""
        return call()

    @staticmethod
    def monotonic() -> float:
        return time.monotonic()

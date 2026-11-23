"""The few primitives the asyncio and sync flavors do differently.

The asyncio code (:mod:`skipcheck_tm1.aio`) is the source of truth; the sync flavor
(``skipcheck_tm1._sync``) is generated from it by ``tm1_pygen.unasync``, which turns
``AsyncIO`` into ``SyncIO``, drops ``async`` and ``await``, and renames ``Async*`` types.
"""

from __future__ import annotations

import asyncio
import threading
import time
from collections.abc import Awaitable
from typing import Any, TypeVar

import httpx

T = TypeVar("T")


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
    def monotonic() -> float:
        return time.monotonic()

# SPDX-License-Identifier: Apache-2.0

"""Sessions on one TM1 server, one per :class:`~skipcheck_tm1.identity.Tm1Identity`, for
applications that act on behalf of many users, as the Java client's ``Tm1SessionPool``. In
TM1 identity is the session, so the number of sessions grows with the number of users; the
pool keeps that bounded::

    pool = AsyncTm1SessionPool.builder(
        AsyncTm1Session.builder().base_url("http://tm1:8010/api/v1/").basic_auth("service", secret)
    ).idle_timeout(600).build()
    session = pool.session(Tm1Identity.impersonating(user))   # each time it's needed

* **Get a session for each piece of work** and don't keep it: the pool may evict it. Getting
  one never blocks and sends nothing; the session logs in on its first request.
* **Eviction** happens when a session has been idle for ``idle_timeout``, is older than
  ``max_age``, or the pool is over ``max_size`` (least recently used first). It's checked on
  each :meth:`session` call and by :meth:`evict_expired`. A session with a request running
  isn't idle, however long ago it was asked for.
* **An evicted session is closed after** ``close_grace``, or when the requests running on it
  have finished, if that's later: closing it would end them on TM1. The next request for that
  identity gets a new session.
* :meth:`close` closes every session at once, and the pool refuses new ones.
* **Nothing falls back:** a refused identity fails, and the pool never uses another
  identity in its place.

Unlike the Java pool, which closes evicted sessions on its own scheduler, this one closes
them when :meth:`evict_expired` (or :meth:`close`) runs: call it now and then, or run
:meth:`sweep_forever` alongside (every ``sweep_interval``). Getting a session stays free of I/O.
So an evicted session that still had a request running is looked at again at the next
:meth:`evict_expired`, where the Java pool looks every second.

A request is running from when it's sent, or waits for the login, until it's answered in full,
fails or is cancelled; an async request until its result is read; a collection until its last
item is taken or its iterator is closed. An iterator that is neither finished nor closed keeps
its session open: that is a leak in the caller, and :meth:`close` still closes every session at
once.

This module is the source of truth for both flavors: the sync ``Tm1SessionPool`` is
generated from it (``tm1_pygen.unasync``).
"""

from __future__ import annotations

import logging
import time
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta

from .._compat import AsyncIO as runtime
from ..errors import Tm1SessionClosedError
from ..identity import Tm1Identity
from .session import AsyncTm1Session, AsyncTm1SessionBuilder

__all__ = ["AsyncTm1SessionPool", "AsyncTm1SessionPoolBuilder", "Tm1PoolStats"]

log = logging.getLogger("skipcheck_tm1")


def _seconds(value: float | timedelta) -> float:
    return value.total_seconds() if isinstance(value, timedelta) else float(value)


@dataclass(frozen=True)
class Tm1PoolStats:
    """What a pool holds and has done (Java's ``Tm1SessionPool.Stats``)."""

    held: int
    """Sessions in the pool now."""
    closing: int
    """Evicted sessions in their grace period, not yet closed."""
    created: int
    evicted_idle: int
    evicted_age: int
    evicted_size: int
    closed: int
    """Sessions closed on TM1 so far."""


class _Entry:
    __slots__ = ("session", "created_at", "last_used")

    def __init__(self, session: AsyncTm1Session, now: float):
        self.session = session
        self.created_at = now
        self.last_used = now


class AsyncTm1SessionPool:
    """One session per identity, bounded; see the module's documentation."""

    Stats = Tm1PoolStats

    def __init__(self, builder: AsyncTm1SessionPoolBuilder):
        self._sessions = builder._sessions
        self._idle_timeout = builder._idle_timeout
        self._max_age = builder._max_age
        self._max_size = builder._max_size
        self._close_grace = builder._close_grace
        self.sweep_interval = builder._sweep_interval
        self._clock = builder._clock
        self._entries: OrderedDict[Tm1Identity, _Entry] = OrderedDict()
        self._closing: list[tuple[AsyncTm1Session, float]] = []
        self._closed = False
        self._created = 0
        self._evicted = {"idle": 0, "age": 0, "size": 0}
        self._closed_sessions = 0

    @staticmethod
    def builder(sessions: AsyncTm1SessionBuilder) -> AsyncTm1SessionPoolBuilder:
        """A builder for a pool of sessions with these settings; each identity's session is
        built from a copy of them."""
        if sessions is None:
            raise TypeError("sessions")
        return AsyncTm1SessionPoolBuilder(sessions.copy())

    def session(self, identity: Tm1Identity | None = None) -> AsyncTm1Session:
        """The session for ``identity`` (the service account if none): the pool's, or a new one
        if it has none, or the one it had has expired. Sends nothing."""
        identity = Tm1Identity.service_account() if identity is None else identity
        now = self._clock()
        if self._closed:
            raise Tm1SessionClosedError(self._service_root())
        entry = self._entries.get(identity)
        if entry is not None:
            reason = self._expiry(entry, now)
            if reason is not None:
                self._evict(identity, entry, reason, now)
                entry = None
        if entry is None:
            settings = identity.apply_to(self._sessions.settings())
            settings["clock"] = self._clock
            entry = _Entry(AsyncTm1Session(**settings), now)
            self._entries[identity] = entry
            self._created += 1
            log.debug("Opened a TM1 session at %s for %s", self._service_root(), identity)
            self._evict_over_size(identity, now)
        entry.last_used = now
        self._entries.move_to_end(identity)
        return entry.session

    async def evict_expired(self) -> None:
        """Evicts every session that has been idle too long or is too old, and closes the
        evicted ones whose grace period is over and that have no request running."""
        now = self._clock()
        for identity, entry in list(self._entries.items()):
            reason = self._expiry(entry, now)
            if reason is not None:
                self._evict(identity, entry, reason, now)
        due, waiting = [], []
        for session, at in self._closing:
            # One with a request still running is looked at again next time: closing it now would
            # end that request on TM1
            (due if at <= now and not session._is_busy() else waiting).append((session, at))
        self._closing = waiting
        for session, _ in due:
            await self._close_session(session)

    async def sweep_forever(self) -> None:
        """Evicts expired sessions every ``sweep_interval`` until the pool is closed (run it
        alongside, as a task or a thread); a failure is logged and the sweep goes on."""
        while not self._closed:
            await runtime.sleep(self.sweep_interval)
            try:
                await self.evict_expired()
            except Exception as e:  # noqa: BLE001
                log.warning("Evicting expired TM1 sessions failed: %s", e)

    def stats(self) -> Tm1PoolStats:
        """What the pool holds and has done so far, for metrics."""
        return Tm1PoolStats(
            len(self._entries),
            len(self._closing),
            self._created,
            self._evicted["idle"],
            self._evicted["age"],
            self._evicted["size"],
            self._closed_sessions,
        )

    @property
    def is_closed(self) -> bool:
        """Whether :meth:`close` has been called."""
        return self._closed

    async def close(self) -> None:
        """Closes every session, including evicted ones still in their grace period, and
        refuses new ones."""
        if self._closed:
            return
        self._closed = True
        sessions = [e.session for e in self._entries.values()] + [s for s, _ in self._closing]
        self._entries.clear()
        self._closing.clear()
        log.info("Closing %s TM1 session(s) at %s", len(sessions), self._service_root())
        for session in sessions:
            await self._close_session(session)

    async def __aenter__(self) -> AsyncTm1SessionPool:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    def __repr__(self) -> str:
        return f"{type(self).__name__}[{self._service_root()}, {len(self._entries)} sessions]"

    # Internals

    async def _close_session(self, session: AsyncTm1Session) -> None:
        try:
            await session.close()
            self._closed_sessions += 1
        except Exception as e:  # noqa: BLE001 (logged, not lost)
            log.warning("Couldn't close an evicted TM1 session at %s: %s", self._service_root(), e)

    def _service_root(self) -> str:
        settings = self._sessions.settings()
        return str(settings.get("base_url") or settings.get("address") or "(no server set)")

    def _expiry(self, entry: _Entry, now: float) -> str | None:
        if now - entry.created_at >= self._max_age:
            return "age"
        if entry.session._is_busy():
            # A request running on it is use, however long ago the session was asked for
            entry.last_used = now
            return None
        if now - entry.last_used >= self._idle_timeout:
            return "idle"
        return None

    def _evict_over_size(self, keep: Tm1Identity, now: float) -> None:
        while len(self._entries) > self._max_size:
            eldest = next(iter(self._entries))
            if eldest == keep:
                return
            self._evict(eldest, self._entries[eldest], "size", now)

    def _evict(self, identity: Tm1Identity, entry: _Entry, reason: str, now: float) -> None:
        self._entries.pop(identity, None)
        self._evicted[reason] += 1
        log.debug(
            "Evicting the TM1 session at %s for %s (%s); closing it in %ss",
            self._service_root(),
            identity,
            reason,
            self._close_grace,
        )
        self._closing.append((entry.session, now + self._close_grace))


class AsyncTm1SessionPoolBuilder:
    """Builds a pool (Java's ``Tm1SessionPool.Builder``); durations are seconds or timedeltas."""

    def __init__(self, sessions: AsyncTm1SessionBuilder):
        self._sessions = sessions
        self._idle_timeout = 600.0
        self._max_age = 8 * 3600.0
        self._max_size = 100
        self._close_grace = 30.0
        self._sweep_interval = 60.0
        self._clock: Callable[[], float] = time.monotonic

    def idle_timeout(self, idle_timeout: float | timedelta) -> AsyncTm1SessionPoolBuilder:
        """How long a session may go unused before it's evicted. Default 10 minutes; keep it
        below TM1's own session timeout."""
        self._idle_timeout = _positive(idle_timeout, "idle_timeout")
        return self

    def max_age(self, max_age: float | timedelta) -> AsyncTm1SessionPoolBuilder:
        """How long a session may live, used or not. Default 8 hours. A session picks up
        security changes, such as new rights, only when it's new."""
        self._max_age = _positive(max_age, "max_age")
        return self

    def max_size(self, max_size: int) -> AsyncTm1SessionPoolBuilder:
        """The most sessions the pool holds (default 100); beyond it, the least recently asked
        for is evicted, with a request running or not: it leaves the pool, and is closed when its
        work is done. Evicted sessions in their grace period don't count."""
        if max_size < 1:
            raise ValueError(f"max_size must be at least 1: {max_size}")
        self._max_size = max_size
        return self

    def close_grace(self, close_grace: float | timedelta) -> AsyncTm1SessionPoolBuilder:
        """How long an evicted session stays open before it's closed, for a caller that got it
        just before and hasn't sent its request yet. A session with a request running stays open
        until that finishes, however long the grace period. Default 30 s; 0 closes at the next
        sweep."""
        value = _seconds(close_grace)
        if value < 0:
            raise ValueError(f"close_grace must not be negative: {value}")
        self._close_grace = value
        return self

    def sweep_interval(self, sweep_interval: float | timedelta) -> AsyncTm1SessionPoolBuilder:
        """How often :meth:`AsyncTm1SessionPool.sweep_forever` looks for expired sessions.
        Default 1 minute."""
        self._sweep_interval = _positive(sweep_interval, "sweep_interval")
        return self

    def clock(self, clock: Callable[[], float]) -> AsyncTm1SessionPoolBuilder:
        """The pool's clock, in seconds (Java's ``scheduler``): tests pass one they move."""
        self._clock = clock
        return self

    def build(self) -> AsyncTm1SessionPool:
        """The pool. It opens sessions only when asked."""
        return AsyncTm1SessionPool(self)


def _positive(value: float | timedelta, name: str) -> float:
    seconds = _seconds(value)
    if seconds <= 0:
        raise ValueError(f"{name} must be positive: {seconds}")
    return seconds

# SPDX-License-Identifier: Apache-2.0

"""What the sync and asyncio sessions share and that sends nothing: poll intervals, the
capability refusal, a batch's queue and how its answers are handed out."""

from __future__ import annotations

import functools
from collections.abc import Iterator
from dataclasses import replace
from datetime import timedelta
from typing import Any

from . import _protocol as p
from .errors import Tm1ProtocolError


def poll_interval(every: float | timedelta) -> float:
    seconds = every.total_seconds() if isinstance(every, timedelta) else float(every)
    if seconds <= 0:
        raise ValueError(f"The poll interval must be positive: {every}")
    return seconds


CAPABILITIES: dict[tuple[str, str], Any] = {}
"""Capabilities by (service root, product version), for every session in the process."""


def as_call(request: Any) -> p.Call:
    """A :class:`~skipcheck_tm1.request.Tm1Request` as a call; a call as is."""
    return request if isinstance(request, p.Call) else request.to_call()


@functools.lru_cache(maxsize=1)
def _operations() -> dict[str, Any]:
    from ._generated.operations import Tm1Operations

    return {op.endpoint(): op for op in Tm1Operations.ALL}


def capability_of(call: p.Call) -> Any:
    """What a call needs the server to have: its own requirement, or its operation's."""
    if call.requires is not None:
        return call.requires
    if call.operation is not None:
        op = _operations().get(call.operation)
        return None if op is None else op.capability()
    return None


def items_only(call: p.Call) -> p.Call:
    """A paged call read as its first page's items, for a batch, which can't follow links."""

    def read(reply: p.Reply) -> Any:
        page = call.read(reply)
        if page.next_link is not None:
            raise Tm1ProtocolError(f"{call.path}: TM1 sent more than one page, which a batch can't follow; ask for fewer with top=")
        return page.items

    return replace(call, read=read)


class Queue:
    def __init__(self) -> None:
        self.entries: list[tuple[p.Call, p.Context, p.Deferred[Any]]] = []
        self.ran = False

    def add(self, call: p.Call, context: p.Context) -> p.Deferred[Any]:
        if self.ran:
            raise RuntimeError(
                "The batch has already run, so this call can't join it: a call whose request depends on "
                f"another's result can't be batched. {call.method} {call.path}"
            )
        if call.raw:
            raise ValueError(f"Bytes can't go in a JSON batch: {call.method} {call.path}")
        deferred: p.Deferred[Any] = p.Deferred(call)
        self.entries.append((call, context, deferred))
        return deferred

    def chunks(self, per_request: int) -> Iterator[list[tuple[p.Call, p.Context, p.Deferred[Any]]]]:
        if self.ran:
            raise RuntimeError("The batch has already run")
        self.ran = True
        for start in range(0, len(self.entries), per_request):
            yield self.entries[start : start + per_request]


def settle(chunk: list[tuple[p.Call, p.Context, p.Deferred[Any]]], reply: p.Reply, url: Any, classifier: Any = None) -> None:
    replies = p.batch_replies(reply.json(), len(chunk))
    for (call, context, deferred), item in zip(chunk, replies):
        deferred._settle(url(p.target(call, context)), item, classifier)

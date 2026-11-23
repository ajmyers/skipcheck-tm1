# SPDX-License-Identifier: Apache-2.0

"""What the sync and asyncio sessions share and that sends nothing: poll intervals, the
capability refusal, a batch's queue and how its answers are handed out."""

from __future__ import annotations

import functools
import json
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


class Queue:
    def __init__(self) -> None:
        self.entries: list[tuple[p.Call, p.Context, p.Deferred[Any]]] = []
        self.paged: list[p.Deferred[Any]] = []
        """The results of calls for a whole collection: a page, until the batch has read the rest."""
        self.ran = False

    def add(self, call: p.Call, context: p.Context, paged: bool = False) -> p.Deferred[Any]:
        if self.ran:
            raise RuntimeError(
                "The batch has already run, so this call can't join it: a call whose request depends on "
                f"another's result can't be batched. {call.method} {call.path}"
            )
        if call.raw:
            raise ValueError(f"Bytes can't go in a JSON batch: {call.method} {call.path}")
        if call.json_body is not None:
            # Written now, so a body that can't be written fails its own call, where it was made, rather
            # than the run and every other call with it. What's sent is the body as it is now.
            try:
                call = replace(call, json_body=json.loads(json.dumps(call.json_body, ensure_ascii=False)))
            except (TypeError, ValueError) as e:
                raise ValueError(f"The request body isn't JSON, so it can't go in a batch: {call.method} {call.path}") from e
        deferred: p.Deferred[Any] = p.Deferred(call)
        self.entries.append((call, context, deferred))
        if paged:
            self.paged.append(deferred)
        return deferred

    def take(self) -> list[tuple[p.Call, p.Context, p.Deferred[Any]]]:
        """Every queued call, for the one run: afterwards the queue takes no more."""
        if self.ran:
            raise RuntimeError("The batch has already run")
        self.ran = True
        return list(self.entries)


def settle(chunk: list[tuple[p.Call, p.Context, p.Deferred[Any]]], reply: p.Reply, url: Any, classifier: Any = None) -> None:
    """Hands each call of a ``$batch`` request its answer. Raises, with no call answered, if the
    response has no body or isn't a batch response: the caller fails every call with that."""
    doc = reply.json()
    if doc is None:
        raise Tm1ProtocolError("TM1 answered the batch with no body", None, "POST", url("$batch"))
    replies = p.batch_replies(doc, len(chunk))
    for (call, context, deferred), item in zip(chunk, replies):
        deferred._settle(url(p.target(call, context)), item, classifier)

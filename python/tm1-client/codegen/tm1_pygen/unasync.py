# SPDX-License-Identifier: Apache-2.0

"""Generates the sync flavor from the asyncio one, as httpx and httpcore do: the hand-written
code lives once, in ``skipcheck_tm1/aio``, and ``skipcheck_tm1/_sync`` is made from it at build time.

The transform works on tokens: code changes by its grammar, strings and comments by the
same renames (so examples read right in each flavor):

* ``async def``, ``async for``, ``async with`` lose ``async``; ``await`` goes, and
  ``Awaitable[X]`` becomes ``X``;
* names are mapped: ``AsyncTm1Session`` → ``Tm1Session`` (any ``AsyncTm1…``),
  ``AsyncIterator`` → ``Iterator``, ``__aenter__`` → ``__enter__``, ``AsyncIO`` → ``SyncIO``
  (the primitives in ``skipcheck_tm1._compat``), ``aio_api`` → ``api``…;
* docstrings and comments get the same treatment, so examples read right in each flavor.

``_compat.py`` and ``__init__.py`` files are hand-written per flavor and not transformed.
"""

from __future__ import annotations

import io
import re
import tokenize
from pathlib import Path

NAMES = {
    "AsyncIterator": "Iterator",
    "AsyncIterable": "Iterable",
    "AsyncGenerator": "Generator",
    "AsyncContextManager": "ContextManager",
    "asynccontextmanager": "contextmanager",
    "AsyncClient": "Client",
    "AsyncIO": "SyncIO",
    "__aenter__": "__enter__",
    "__aexit__": "__exit__",
    "__aiter__": "__iter__",
    "__anext__": "__next__",
    "aclose": "close",
    "StopAsyncIteration": "StopIteration",
    "aio_api": "api",
    "aio": "_sync",
    "iscoroutinefunction": "isfunction",
}

_ASYNC_TM1 = re.compile(r"\bAsync(Tm1\w*)")

# What every generated file starts with, as the hand-written sources do: the license it's under
LICENSE = "# SPDX-License-Identifier: Apache-2.0\n\n"

HEADER = "# Generated from skipcheck_tm1/aio/{source} by tm1_pygen.unasync: don't edit, don't commit.\n"


def _name(name: str) -> str:
    if name in NAMES:
        return NAMES[name]
    m = _ASYNC_TM1.fullmatch(name)
    return m.group(1) if m else name


_TEXT_RULES = [
    (re.compile(r"\basync (def|for|with)\b"), r"\1"),
    (re.compile(r"\bawait "), ""),
    (_ASYNC_TM1, r"\1"),
    (re.compile(r"\btm1_client\.aio\b"), "skipcheck_tm1"),
    (re.compile(r"\baio_api\b"), "api"),
    (re.compile(r"\bAsyncIterator\b"), "Iterator"),
    (re.compile(r"\b__aenter__\b"), "__enter__"),
    (re.compile(r"\b__aexit__\b"), "__exit__"),
]


def _text(text: str) -> str:
    for pattern, replacement in _TEXT_RULES:
        text = pattern.sub(replacement, text)
    return text


def transform(source: str) -> str:
    """The sync version of an asyncio module."""
    tokens = list(tokenize.generate_tokens(io.StringIO(source).readline))
    lines = source.splitlines(keepends=True)
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))

    def at(pos: tuple[int, int]) -> int:
        return offsets[pos[0] - 1] + pos[1]

    edits: list[tuple[int, int, str]] = []
    for i, tok in enumerate(tokens):
        start, end = at(tok.start), at(tok.end)
        if tok.type == tokenize.NAME:
            following = tokens[i + 1] if i + 1 < len(tokens) else None
            if tok.string == "async" and following is not None and following.string in ("def", "for", "with"):
                edits.append((start, at(following.start), ""))
            elif tok.string == "await":
                edits.append((start, at(following.start) if following is not None else end, ""))
            elif tok.string == "Awaitable" and following is not None and following.string == "[":
                # Awaitable[str] → str: what's awaited there is the value itself here
                depth, j = 0, i + 1
                while True:
                    depth += {"[": 1, "]": -1}.get(tokens[j].string, 0)
                    if depth == 0:
                        break
                    j += 1
                edits.append((start, at(following.end), ""))
                edits.append((at(tokens[j].start), at(tokens[j].end), ""))
            else:
                renamed = _name(tok.string)
                if renamed != tok.string:
                    edits.append((start, end, renamed))
        elif tok.type in (tokenize.STRING, tokenize.COMMENT):
            changed = _text(tok.string)
            if changed != tok.string:
                edits.append((start, end, changed))
    out = source
    for start, end, text in sorted(edits, reverse=True):
        out = out[:start] + text + out[end:]
    return out


def generate(aio_dir: str | Path, sync_dir: str | Path) -> list[Path]:
    """Writes the sync flavor of every module under ``aio_dir`` into ``sync_dir``, replacing it.

    ``__init__.py`` files are transformed too, so the packages export the same names; only
    ``_compat`` is exempt, being shared.
    """
    aio_dir, sync_dir = Path(aio_dir), Path(sync_dir)
    if sync_dir.exists():
        for old in sorted(sync_dir.rglob("*.py"), reverse=True):
            old.unlink()
    written = []
    for path in sorted(aio_dir.rglob("*.py")):
        relative = path.relative_to(aio_dir)
        target = sync_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        # The license line stays first, above the note that the file is generated
        body = transform(path.read_text(encoding="utf-8")).removeprefix(LICENSE)
        target.write_text(LICENSE + HEADER.format(source=relative.as_posix()) + body, encoding="utf-8")
        written.append(target)
    return written

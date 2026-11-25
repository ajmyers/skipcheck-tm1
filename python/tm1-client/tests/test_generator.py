# SPDX-License-Identifier: Apache-2.0

"""The generator: deterministic output, and the sync and asyncio APIs alike member for member."""

from __future__ import annotations

import inspect
import re
import sys
from pathlib import Path

import pytest

from skipcheck_tm1._generated import aio_api, api

from .conftest import SPEC

ROOT = Path(__file__).resolve().parents[1]


def test_generates_the_same_code_every_time(tmp_path: Path) -> None:
    sys.path.insert(0, str(ROOT / "codegen"))
    try:
        from tm1_pygen import generate

        first = {p.name: p.read_text() for p in generate(SPEC, tmp_path / "a")}
        second = {p.name: p.read_text() for p in generate(SPEC, tmp_path / "b")}
    finally:
        sys.path.pop(0)
    assert first == second
    # And it's what the package was built with
    built = ROOT / "src" / "skipcheck_tm1" / "_generated"
    for name, text in first.items():
        assert (built / name).read_text() == text, f"{name} is stale: reinstall or run tm1_pygen"


def test_refuses_a_spec_version_it_doesnt_read(tmp_path: Path) -> None:
    sys.path.insert(0, str(ROOT / "codegen"))
    try:
        from tm1_pygen.spec import Spec
    finally:
        sys.path.pop(0)
    spec = tmp_path / "spec.json"
    spec.write_text('{"specVersion": 1}', encoding="utf-8")
    with pytest.raises(ValueError, match="spec version 1; this generator reads 2"):
        Spec.load(spec)


def test_generated_code_starts_with_its_license() -> None:
    package = ROOT / "src" / "skipcheck_tm1"
    generated = [*(package / "_generated").glob("*.py"), *(package / "_sync").rglob("*.py")]
    assert generated
    for path in generated:
        assert path.read_text().startswith("# SPDX-License-Identifier: Apache-2.0\n"), path


def _classes(module: object) -> dict[str, type]:
    return {n: c for n, c in vars(module).items() if inspect.isclass(c) and c.__module__ == module.__name__}


def test_the_asyncio_api_matches_the_sync_one() -> None:
    sync, aio = _classes(api), _classes(aio_api)
    assert sync.keys() == aio.keys()
    for name, cls in sync.items():
        other = aio[name]
        own = {k for k in vars(cls) if not k.startswith("_")}
        assert own == {k for k in vars(other) if not k.startswith("_")}, name
        for member in own:
            a, b = vars(cls)[member], vars(other)[member]
            if isinstance(a, property):
                assert isinstance(b, property), f"{name}.{member}"
            elif inspect.isfunction(a) and getattr(a, "__tm1_endpoints__", None):
                # Operations send requests: async in the asyncio API, with the same parameters
                assert inspect.iscoroutinefunction(b), f"{name}.{member}"
                assert inspect.signature(a).parameters.keys() == inspect.signature(b).parameters.keys()


def test_names_members_without_numbers() -> None:
    for cls in _classes(api).values():
        for member in vars(cls):
            assert not re.search(r"[a-z]\d+$", member), f"{cls.__name__}.{member}"


def test_unasync_makes_the_sync_flavor() -> None:
    sys.path.insert(0, str(ROOT / "codegen"))
    try:
        from tm1_pygen.unasync import transform
    finally:
        sys.path.pop(0)
    source = (
        "from .._compat import AsyncIO as runtime\n"
        "class AsyncTm1Thing:\n"
        "    async def __aenter__(self) -> AsyncTm1Thing:\n"
        '        """``async with AsyncTm1Thing() as t: await t.go()``"""\n'
        "        async with runtime.Lock():\n"
        "            await runtime.sleep(1)\n"
        "        return [x async for x in self.items()]  # await AsyncIterator\n"
        "    async def items(self) -> AsyncIterator[int]:\n"
        "        await self.http.aclose()\n"
        '        s = "await t.go() in a string reads right too"\n'
    )
    assert transform(source) == (
        "from .._compat import SyncIO as runtime\n"
        "class Tm1Thing:\n"
        "    def __enter__(self) -> Tm1Thing:\n"
        '        """``with Tm1Thing() as t: t.go()``"""\n'
        "        with runtime.Lock():\n"
        "            runtime.sleep(1)\n"
        "        return [x for x in self.items()]  # Iterator\n"
        "    def items(self) -> Iterator[int]:\n"
        "        self.http.close()\n"
        '        s = "t.go() in a string reads right too"\n'
    )


def test_value_types_are_shared_by_both_flavors() -> None:
    """Value types live once (skipcheck_tm1.values), so a sync ContentScope is an asyncio one too."""
    from skipcheck_tm1 import services
    from skipcheck_tm1.aio import services as aio_services

    for name in ("ContentPath", "ContentScope", "ViewScope", "SubsetScope", "Tuples", "AttributeOwner", "AttributeSet", "GitCredentials", "GitCommitDetails"):
        assert getattr(services, name) is getattr(aio_services, name), name

# SPDX-License-Identifier: Apache-2.0

"""tm1_pygen: generates the Python client's model and API from the language-neutral API spec
(``spec/tm1-api.json``, exported from the ``$metadata`` snapshot by ``tm1-codegen``).

Run ``python -m tm1_pygen <spec.json> <package directory>``; the Python package's build runs
it (``hatch_build.py``). It needs nothing but the standard library.
"""

from __future__ import annotations

from pathlib import Path

from . import emit_api, emit_model, emit_operations, unasync
from .spec import Spec

__all__ = ["generate"]

_INIT = '''"""Generated from spec/tm1-api.json by tm1_pygen: don't edit, don't commit.

``model``, ``api`` (sync), ``aio_api`` (asyncio) and ``index``; import the one you need.
"""
'''


def generate(spec_path: str | Path, package_dir: str | Path) -> list[Path]:
    """Writes ``_generated/`` under ``package_dir`` (the ``skipcheck_tm1`` directory) from the
    spec, and ``_sync/`` from ``aio/`` (the sync flavor of the hand-written code), replacing both."""
    spec = Spec.load(spec_path)
    out = Path(package_dir) / "_generated"
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.py"):
        old.unlink()
    api, index = emit_api.emit(spec, "sync")
    aio_api, _ = emit_api.emit(spec, "async")
    files = {
        "__init__.py": _INIT,
        "model.py": emit_model.emit(spec),
        "api.py": api,
        "aio_api.py": aio_api,
        "index.py": index,
        "operations.py": emit_operations.emit(spec),
        "patches.py": emit_model.emit_patches(spec),
    }
    written = []
    for name, text in files.items():
        path = out / name
        path.write_text(unasync.LICENSE + text, encoding="utf-8")
        written.append(path)
    aio = Path(package_dir) / "aio"
    if aio.is_dir():
        written += unasync.generate(aio, Path(package_dir) / "_sync")
    return written

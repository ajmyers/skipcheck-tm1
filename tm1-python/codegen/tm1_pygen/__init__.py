"""tm1_pygen: generates the Python client's model and API from the language-neutral API spec
(``spec/tm1-api.json``, exported from the ``$metadata`` snapshot by ``tm1-codegen``).

Run ``python -m tm1_pygen <spec.json> <package directory>``; the Python package's build runs
it (``hatch_build.py``). It needs nothing but the standard library.
"""

from __future__ import annotations

from pathlib import Path

from . import emit_api, emit_model
from .spec import Spec

__all__ = ["generate"]

_INIT = '''"""Generated from spec/tm1-api.json by tm1_pygen: don't edit, don't commit.

``model``, ``api`` (sync), ``aio_api`` (asyncio) and ``index``; import the one you need.
"""
'''


def generate(spec_path: str | Path, package_dir: str | Path) -> list[Path]:
    """Writes ``_generated/`` under ``package_dir`` (the ``tm1_client`` directory), replacing it."""
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
    }
    written = []
    for name, text in files.items():
        path = out / name
        path.write_text(text, encoding="utf-8")
        written.append(path)
    return written

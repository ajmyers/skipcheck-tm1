"""Generates the model and API (src/skipcheck_tm1/_generated) from the API spec at build time,
as Gradle does for the Java client: generated code is never committed.

The spec is the repository's spec/tm1-api.json; an sdist carries a copy in spec/.
"""

from __future__ import annotations

import sys
from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

ROOT = Path(__file__).parent


def spec_path() -> Path:
    for candidate in (ROOT.parent / "spec" / "tm1-api.json", ROOT / "spec" / "tm1-api.json"):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("spec/tm1-api.json: not in the repository root or the sdist")


def generate() -> None:
    sys.path.insert(0, str(ROOT / "codegen"))
    try:
        from tm1_pygen import generate as run

        run(spec_path(), ROOT / "src" / "skipcheck_tm1")
    finally:
        sys.path.pop(0)


class CustomBuildHook(BuildHookInterface):
    def initialize(self, version: str, build_data: dict) -> None:
        # An sdist carries the spec instead (pyproject.toml's force-include)
        if self.target_name != "sdist":
            generate()


if __name__ == "__main__":
    generate()

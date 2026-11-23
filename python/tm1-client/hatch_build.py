# SPDX-License-Identifier: Apache-2.0

"""Generates the model and API (src/skipcheck_tm1/_generated) from the API spec at build time,
as Gradle does for the Java client: generated code is never committed.

The spec is the repository's spec/tm1-api.json. An sdist doesn't carry it: it carries the code
generated from it, so a wheel built from an sdist uses that as it is.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

ROOT = Path(__file__).parent


SPEC = ROOT.parent.parent / "spec" / "tm1-api.json"
GENERATED = ROOT / "src" / "skipcheck_tm1" / "_generated"


def generate() -> None:
    sys.path.insert(0, str(ROOT / "codegen"))
    try:
        from tm1_pygen import generate as run

        run(SPEC, ROOT / "src" / "skipcheck_tm1")
    finally:
        sys.path.pop(0)


def copy_license() -> None:
    """Puts the repository's LICENSE and NOTICE beside pyproject.toml, where its license-files
    looks for them. An sdist has them there already, and no repository above it."""
    for name in ("LICENSE", "NOTICE"):
        source = ROOT.parent.parent / name
        if source.is_file():
            shutil.copyfile(source, ROOT / name)


class CustomBuildHook(BuildHookInterface):
    def initialize(self, version: str, build_data: dict) -> None:
        copy_license()
        if SPEC.is_file():
            # In the repository: generate, for the sdist too, which carries the generated code
            generate()
        elif not GENERATED.is_dir():
            raise FileNotFoundError(f"{SPEC} isn't here to generate from, and there's no generated code (an sdist carries it)")


if __name__ == "__main__":
    generate()

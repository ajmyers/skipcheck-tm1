# SPDX-License-Identifier: Apache-2.0

"""Puts docker/paw/paw-local.sh in the package as it's built.

PawContainer runs that script in its runner container, so the package ships it, as the Java build
puts it in the jar. The script lives in the repository's docker/paw folder, which a build from the
repository reads; an sdist carries its own copy inside the package, so a wheel built from the sdist
(as pip builds one) finds it there and has nothing to add.
"""

import shutil
from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

PACKAGE = "skipcheck_tm1_testcontainers"


class PawScriptHook(BuildHookInterface):
    def initialize(self, version: str, build_data: dict) -> None:
        # The repository's LICENSE and NOTICE go beside pyproject.toml, where its license-files looks
        # for them. An sdist has them there already, and no repository above it.
        for name in ("LICENSE", "NOTICE"):
            source = Path(self.root, "..", "..", name).resolve()
            if source.is_file():
                shutil.copyfile(source, Path(self.root, name))
        in_repository = Path(self.root, "..", "..", "docker", "paw", "paw-local.sh").resolve()
        if not in_repository.is_file():
            if not Path(self.root, "src", PACKAGE, "paw-local.sh").is_file():
                raise FileNotFoundError("paw-local.sh is neither in the repository's docker/paw nor in the package")
            return
        target = f"src/{PACKAGE}/paw-local.sh" if self.target_name == "sdist" else f"{PACKAGE}/paw-local.sh"
        build_data["force_include"][str(in_repository)] = target

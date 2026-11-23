"""The type check, as part of the tests: ``pytest`` fails on a type error in either package's
sources, as the Java build fails on a NullAway or Error Prone finding. Pyright, configured in
python/pyrightconfig.json, over the hand-written and the generated modules alike (the tests run
after an install, which generates them)."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_the_code_type_checks() -> None:
    generated = ROOT / "tm1-client" / "src" / "skipcheck_tm1" / "_sync" / "session.py"
    assert generated.exists(), "the sync flavor isn't generated: pip install -e python/tm1-client"
    # --pythonpath: this interpreter's packages (httpx, pydantic, testcontainers), whichever is active
    command = [sys.executable, "-m", "pyright", "--project", str(ROOT), "--pythonpath", sys.executable]
    done = subprocess.run(command, capture_output=True, text=True)
    assert done.returncode == 0, done.stdout + done.stderr

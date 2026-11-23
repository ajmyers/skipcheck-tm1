# SPDX-License-Identifier: Apache-2.0

"""The license line check (scripts/license-headers.py), which the pre-commit and pre-push hooks
and CI run."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest
from conftest import REPO, load

headers = load("scripts/license-headers.py")
LINE = "SPDX-License-Identifier: Apache-2.0"
BYTE_ORDER_MARK = chr(0xFEFF)


def fix(root: Path, name: str, content: bytes | str) -> tuple[bool, str]:
    """Writes a file, fixes it, and returns whether it lacked the line and what it holds now."""
    file = root / name
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(content if isinstance(content, bytes) else content.encode())
    lacked = headers.missing(root, Path(name), True)
    return lacked, file.read_bytes().decode(errors="replace")


@pytest.mark.parametrize(
    ("name", "comment"),
    [
        ("A.java", f"// {LINE}"),
        ("build.gradle.kts", f"// {LINE}"),
        ("Program.cs", f"// {LINE}"),
        ("index.ts", f"// {LINE}"),
        ("tool.mjs", f"// {LINE}"),
        ("tool.py", f"# {LINE}"),
        ("setup.ps1", f"# {LINE}"),
        ("theme.css", f"/* {LINE} */"),
        ("docker/x/Dockerfile", f"# {LINE}"),
    ],
)
def test_adds_the_line_in_the_files_comment_syntax(tmp_path: Path, name: str, comment: str) -> None:
    lacked, text = fix(tmp_path, name, "code\n")
    assert lacked and text == f"{comment}\n\ncode\n"
    # And leaves it alone the second time
    assert headers.missing(tmp_path, Path(name), True) is False
    assert (tmp_path / name).read_text() == text


def test_keeps_a_shebang_and_a_dockerfiles_directives_first(tmp_path: Path) -> None:
    assert fix(tmp_path, "tool.sh", "#!/bin/sh\nset -e\n")[1] == f"#!/bin/sh\n# {LINE}\n\nset -e\n"
    assert fix(tmp_path, "Dockerfile", "# syntax=docker/dockerfile:1\n# check=error=true\nFROM scratch\n")[1] == (
        f"# syntax=docker/dockerfile:1\n# check=error=true\n# {LINE}\n\nFROM scratch\n"
    )


def test_a_script_without_an_extension_is_known_by_its_shebang(tmp_path: Path) -> None:
    assert fix(tmp_path, "pre-commit", "#!/usr/bin/env bash\nexit 0\n") == (True, f"#!/usr/bin/env bash\n# {LINE}\n\nexit 0\n")
    assert fix(tmp_path, "LICENSE", "Apache License\n") == (False, "Apache License\n")


def test_adds_no_second_blank_line(tmp_path: Path) -> None:
    assert fix(tmp_path, "tool.py", "#!/usr/bin/env python3\n\nimport sys\n")[1] == f"#!/usr/bin/env python3\n# {LINE}\n\nimport sys\n"


def test_keeps_line_endings_and_a_byte_order_mark(tmp_path: Path) -> None:
    assert fix(tmp_path, "setup.ps1", "param()\r\nexit 0\r\n")[1] == f"# {LINE}\r\n\r\nparam()\r\nexit 0\r\n"
    assert fix(tmp_path, "Program.cs", BYTE_ORDER_MARK + "class A {}\n")[1] == f"{BYTE_ORDER_MARK}// {LINE}\n\nclass A {{}}\n"


@pytest.mark.parametrize(
    ("name", "content"),
    [
        ("data.json", b"{}\n"),
        ("README.md", b"# Title\n"),
        ("config.yaml", b"a: 1\n"),
        ("empty.py", b""),
        ("blank.py", b"\n\n"),
        ("image.py", b"\xff\xfe\x00\x01"),
        ("spec/conformance/case.py", b"code\n"),
        ("docs/sample.java", b"code\n"),
        ("gradlew", b"#!/bin/sh\nexit 0\n"),
    ],
)
def test_leaves_what_isnt_source_or_isnt_ours(tmp_path: Path, name: str, content: bytes) -> None:
    lacked, _ = fix(tmp_path, name, content)
    assert lacked is False
    assert (tmp_path / name).read_bytes() == content


def test_checking_changes_nothing(tmp_path: Path) -> None:
    (tmp_path / "tool.py").write_text("code\n")
    assert headers.missing(tmp_path, Path("tool.py"), False) is True
    assert (tmp_path / "tool.py").read_text() == "code\n"
    assert headers.missing(tmp_path, Path("gone.py"), False) is False


def test_the_line_must_come_first_not_just_somewhere(tmp_path: Path) -> None:
    assert fix(tmp_path, "tool.py", f"import sys\n# {LINE}\n")[0] is True


def test_every_tracked_file_of_this_repository_has_it() -> None:
    result = subprocess.run([sys.executable, str(REPO / "scripts" / "license-headers.py")], capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stdout + result.stderr

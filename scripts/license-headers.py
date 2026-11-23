#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""Checks that every source file says what license it's under, or adds the line that says so.

    scripts/license-headers.py              check every tracked file; exit 1 if one lacks the line
    scripts/license-headers.py --fix        add the line where it's missing
    scripts/license-headers.py [--fix] a b  only these files (the pre-commit hook passes what's staged)

The line is "SPDX-License-Identifier: Apache-2.0" in the file's comment syntax, first in the file
(after a shebang, or a Dockerfile's parser directive) and followed by a blank line. One line and not
the Apache boilerplate, with no copyright year to go stale: the license doesn't ask for a header,
and the identifier is what license scanners read. It's there for a file that gets copied without
the repository around it.

Source files only: code, scripts, stylesheets and Dockerfiles. Not data and configuration (JSON,
YAML, TOML, properties), prose (Markdown, HTML, text), the recorded fixtures in spec/, nor files
that came from elsewhere with their own license (the Gradle wrapper). Empty files are left empty.
Generated code isn't tracked, so it isn't checked here: each generator writes the line itself.

Each client's own build checks its language too (./gradlew build, pytest, npm test, dotnet build),
and the pre-push hook and CI run this over everything. Needs only Python's standard library.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

IDENTIFIER = "SPDX-License-Identifier: Apache-2.0"

# How a one-line comment is written, by file extension
COMMENTS = {
    **dict.fromkeys([".java", ".kt", ".kts", ".cs", ".ts", ".mts", ".mjs", ".js"], "// {}"),
    **dict.fromkeys([".py", ".sh", ".ps1"], "# {}"),
    ".css": "/* {} */",
}

# Not ours to relicense, or not source
SKIPPED = ("spec/", "docs/", "gradlew", "gradlew.bat")

# Lines that have to stay first: a shebang, and the directives a Dockerfile's parser reads
LEADING = ("#!", "# syntax=", "# escape=", "# check=")


def comment(path: Path, first_line: str) -> str | None:
    """The license line for a file, or None for a file that doesn't get one."""
    if path.name == "Dockerfile":
        return "# " + IDENTIFIER
    template = COMMENTS.get(path.suffix)
    if template is None and not path.suffix and first_line.startswith("#!"):
        template = "# {}"  # a script without an extension, such as a git hook
    return template.format(IDENTIFIER) if template else None


def tracked(root: Path) -> list[Path]:
    listed = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True).stdout
    return [Path(name) for name in listed.decode().split("\0") if name]


def missing(root: Path, path: Path, fix: bool) -> bool:
    """Whether a file lacks its license line; with fix, adds it."""
    if str(path.as_posix()).startswith(SKIPPED) or not (root / path).is_file():
        return False
    try:
        with open(root / path, encoding="utf-8", newline="") as file:
            text = file.read()
    except UnicodeDecodeError:
        return False  # binary
    if not text.strip():
        return False
    mark = "\ufeff" if text.startswith("\ufeff") else ""  # a byte order mark stays first
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text[len(mark) :].split(newline)
    line = comment(path, lines[0])
    if line is None:
        return False
    at = 0
    while at < len(lines) and lines[at].startswith(LEADING):
        at += 1
    if at < len(lines) and lines[at] == line:
        return False
    if fix:
        after = [] if at < len(lines) and not lines[at].strip() else [""]
        with open(root / path, "w", encoding="utf-8", newline="") as file:  # newline="": line endings as they were
            file.write(mark + newline.join(lines[:at] + [line] + after + lines[at:]))
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Check or add the SPDX license line of source files.")
    parser.add_argument("--fix", action="store_true", help="add the line where it's missing")
    parser.add_argument("files", nargs="*", type=Path, help="paths from the repository root (default: every tracked file)")
    options = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    lacking = [path for path in (options.files or tracked(root)) if missing(root, path, options.fix)]
    if not lacking:
        return 0
    for path in lacking:
        print(("added the license line: " if options.fix else "no license line: ") + path.as_posix())
    if options.fix:
        return 0
    print(f"{len(lacking)} file(s) lack '{IDENTIFIER}'. Add it with: scripts/license-headers.py --fix", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

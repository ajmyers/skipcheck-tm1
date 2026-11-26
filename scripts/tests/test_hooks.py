# SPDX-License-Identifier: Apache-2.0

"""The git hooks (.githooks/), run in repositories made for the test. They need bash and git, as
the hooks do; the builds and formatters the hooks call are stood in for by small scripts."""

from __future__ import annotations

import shutil
import stat
import subprocess
from pathlib import Path

import pytest
from conftest import REPO, clean_environment, git

HOOKS = REPO / ".githooks"
ZERO = "0" * 40
LINE = "# SPDX-License-Identifier: Apache-2.0"

pytestmark = pytest.mark.skipif(
    shutil.which("bash") is None, reason="the hooks are bash scripts"
)


def script(path: Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


def hook(
    name: str, repository: Path, *arguments: str, stdin: str = "", **environment: str
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(repository / ".githooks" / name), *arguments],
        cwd=repository,
        env=clean_environment(**environment),
        input=stdin,
        capture_output=True,
        text=True,
        check=False,
    )


@pytest.fixture
def project(repository: Path) -> Path:
    """A repository with this one's hooks and license script, and one commit."""
    shutil.copytree(HOOKS, repository / ".githooks")
    (repository / "scripts").mkdir()
    shutil.copy(REPO / "scripts" / "license-headers.py", repository / "scripts")
    (repository / "README.md").write_text("A project\n", encoding="utf-8")
    git(repository, "add", "README.md")
    git(repository, "commit", "-q", "-m", "Start")
    return repository


# commit-msg


def test_commit_msg_refuses_a_message_that_breaks_the_rules(project: Path) -> None:
    message = project / "message"
    message.write_text("add a thing\n", encoding="utf-8")
    refused = hook("commit-msg", project, str(message))
    assert (
        refused.returncode == 1 and "doesn't follow the conventions" in refused.stderr
    )
    message.write_text(
        "Add a thing\n\nSo that there is one.\n\nRefs: #1\n", encoding="utf-8"
    )
    assert hook("commit-msg", project, str(message)).returncode == 0


# pre-commit


def staged(repository: Path, name: str) -> str:
    return git(repository, "show", f":{name}")


def test_pre_commit_adds_the_license_line_to_what_is_staged(project: Path) -> None:
    (project / "tool.sh").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    git(project, "add", "tool.sh")
    result = hook("pre-commit", project)
    assert result.returncode == 0, result.stderr
    assert staged(project, "tool.sh") == f"#!/bin/sh\n{LINE}\n\nexit 0"
    # What was staged before it changed anything can be had back
    assert (
        git(project, "show", "refs/format-hook/backup:tool.sh") == "#!/bin/sh\nexit 0"
    )


def test_pre_commit_leaves_a_file_with_unstaged_changes_too(project: Path) -> None:
    file = project / "tool.sh"
    file.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    git(project, "add", "tool.sh")
    file.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
    result = hook("pre-commit", project)
    assert result.returncode == 0 and "leaving tool.sh as staged" in result.stderr
    assert staged(project, "tool.sh") == "#!/bin/sh\nexit 0"
    assert file.read_text(encoding="utf-8") == "#!/bin/sh\nexit 1\n"


@pytest.mark.parametrize("variable", ["SKIP_FORMAT_HOOK", "CI", "GITHUB_ACTIONS"])
def test_pre_commit_does_nothing_when_told_to_skip_or_in_ci(
    project: Path, variable: str
) -> None:
    (project / "tool.sh").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    git(project, "add", "tool.sh")
    assert hook("pre-commit", project, **{variable: "1"}).returncode == 0
    assert staged(project, "tool.sh") == "#!/bin/sh\nexit 0"


def test_pre_commit_refuses_when_a_formatter_cant_run(
    project: Path, tmp_path: Path
) -> None:
    # A dotnet that fails, first on the path: CSharpier can't be found or restored
    script(tmp_path / "bin" / "dotnet", "exit 1\n")
    (project / "dotnet").mkdir()
    (project / "dotnet" / "A.cs").write_text(
        "// SPDX-License-Identifier: Apache-2.0\n\nclass A { }\n", encoding="utf-8"
    )
    git(project, "add", "dotnet/A.cs")
    environment = clean_environment()
    result = hook(
        "pre-commit", project, PATH=f"{tmp_path / 'bin'}:{environment['PATH']}"
    )
    assert result.returncode == 1
    assert (
        "CSharpier isn't installed" in result.stderr
        and "commit refused: .NET" in result.stderr
    )


def test_pre_commit_leaves_a_merge_alone(project: Path) -> None:
    (project / "tool.sh").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    git(project, "add", "tool.sh")
    (project / ".git" / "MERGE_HEAD").write_text(
        git(project, "rev-parse", "HEAD") + "\n", encoding="utf-8"
    )
    assert hook("pre-commit", project).returncode == 0
    assert staged(project, "tool.sh") == "#!/bin/sh\nexit 0"


# pre-push: the build is ./gradlew build, here a script that fails in a commit that has a file named BROKEN


def commit(repository: Path, name: str, message: str) -> str:
    (repository / name).write_text(message + "\n", encoding="utf-8")
    git(repository, "add", name)
    git(repository, "commit", "-q", "-m", message)
    return git(repository, "rev-parse", "HEAD")


@pytest.fixture
def built(repository: Path) -> Path:
    """A repository whose ./gradlew passes unless the commit has a file named BROKEN. The hook is
    run from this repository's .githooks, not committed there, as a clone's hooks aren't."""
    shutil.copytree(HOOKS, repository / ".githooks")
    (repository / ".git" / "info").mkdir(exist_ok=True)
    (repository / ".git" / "info" / "exclude").write_text(
        ".githooks/\n", encoding="utf-8"
    )
    script(
        repository / "gradlew",
        '[ ! -f BROKEN ] || { echo "BROKEN is here"; exit 1; }\n',
    )
    git(repository, "add", "gradlew")
    git(repository, "commit", "-q", "-m", "Add the build")
    return repository


def push(
    repository: Path, local: str, remote: str = ZERO, **environment: str
) -> subprocess.CompletedProcess[str]:
    return hook(
        "pre-push",
        repository,
        "origin",
        "git@example.invalid:x.git",
        stdin=f"refs/heads/main {local} refs/heads/main {remote}\n",
        **environment,
    )


def test_pre_push_builds_every_commit_the_remote_lacks_oldest_first(
    built: Path,
) -> None:
    base = git(built, "rev-parse", "HEAD")
    commit(built, "a", "First")
    tip = commit(built, "b", "Second")
    result = push(built, tip, base)
    assert result.returncode == 0, result.stderr
    assert "building 2 commit(s)" in result.stderr
    assert result.stderr.index("First") < result.stderr.index("Second")
    # It built in a worktree of its own, and left none behind
    assert git(built, "worktree", "list").count("\n") == 0
    assert git(built, "status", "--porcelain") == ""


def test_pre_push_refuses_a_push_with_a_commit_that_doesnt_build(built: Path) -> None:
    base = git(built, "rev-parse", "HEAD")
    commit(built, "BROKEN", "Break the build")
    git(built, "rm", "-q", "BROKEN")
    git(built, "commit", "-q", "-m", "Mend the build")
    result = push(built, git(built, "rev-parse", "HEAD"), base)
    assert result.returncode == 1
    # The tip builds; the commit before it doesn't, and that's enough
    assert (
        "commit 1 of 2 doesn't build" in result.stderr
        and "Break the build" in result.stderr
    )
    assert "BROKEN is here" in result.stderr and "push refused" in result.stderr


def test_pre_push_builds_nothing_for_a_deleted_branch_or_in_ci(built: Path) -> None:
    broken = commit(built, "BROKEN", "Break the build")
    deleted = hook(
        "pre-push",
        built,
        "origin",
        "url",
        stdin=f"(delete) {ZERO} refs/heads/gone {broken}\n",
    )
    assert deleted.returncode == 0 and "building" not in deleted.stderr
    assert push(built, broken, CI="true").returncode == 0


def test_pre_push_checks_the_other_clients_layout_only_in_commits_that_touch_them(
    built: Path, tmp_path: Path
) -> None:
    base = git(built, "rev-parse", "HEAD")
    # A ruff that refuses everything, first on the path
    script(
        tmp_path / "bin" / "ruff",
        '[ "$1" = --version ] && exit 0; echo "would reformat"; exit 1\n',
    )
    path = f"{tmp_path / 'bin'}:{clean_environment()['PATH']}"
    untouched = commit(built, "notes.txt", "Add notes")
    assert push(built, untouched, base, PATH=path).returncode == 0
    (built / "python").mkdir()
    touched = commit(built, "python/x.py", "Add Python")
    result = push(built, touched, untouched, PATH=path)
    assert result.returncode == 1
    assert (
        "fails a layout, lint or license line check" in result.stderr
        and "would reformat" in result.stderr
    )

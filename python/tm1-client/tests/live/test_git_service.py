# SPDX-License-Identifier: Apache-2.0

"""GitService against a real TM1: a port of the Java client's GitServiceTm1Test.

The remote is ``TM1_GIT_URL`` if it's set: a ``git://`` (or ``https://``) repository TM1 can
reach, whose ``main`` branch has a commit (TM1 can't make the first). Otherwise, when the tests
start TM1 in Docker themselves, it's a ``git daemon`` container sharing the TM1 container's
network, as the Java test's ``GitDaemon``, which the test can also change behind TM1's back. For a
TM1 at ``TM1_URL`` with no ``TM1_GIT_URL`` the test is skipped.
"""

from __future__ import annotations

import os
import uuid
from collections.abc import Iterator
from typing import Any

import pytest

from skipcheck_tm1 import Tm1BadRequestError, Tm1Client, Tm1Error, Tm1NotFoundError
from skipcheck_tm1._sync.services.git import GitCommitDetails, GitCredentials
from skipcheck_tm1.model import DimensionCreate, ElementCreate, GitPushPlan, HierarchyCreate

from .conftest import _RUNNING, CONFIGURED, IS_ENGINE, REASON
from .support import STALE_FIRST_PULL_FROM, is_at_least, is_v12

GIVEN = (os.environ.get("TM1_GIT_URL") or "").strip() or None

pytestmark = pytest.mark.skipif(
    not CONFIGURED or (GIVEN is None and bool(_RUNNING)),
    reason=REASON if not CONFIGURED else "No Git remote configured (TM1_GIT_URL) for the TM1 at TM1_URL",
)

COMMIT = GitCommitDetails("Add a dimension", "tm1-client", "tm1-client@example.com")

_SEED = " && ".join(
    [
        "apk add -q --no-cache git git-daemon",
        "git init -q --bare -b main /srv/model.git",
        "git clone -q /srv/model.git /tmp/seed",
        "cd /tmp/seed",
        "echo 'A TM1 model' > README.md",
        "git add README.md",
        "git -c user.name=seed -c user.email=seed@example.com commit -qm Seed",
        "git push -q origin main",
        "exec git daemon --verbose --reuseaddr --base-path=/srv --export-all --enable=receive-pack /srv",
    ]
)


class GitDaemon:
    """The Git remote: ``TM1_GIT_URL``, or a ``git daemon`` serving one bare repository,
    ``model.git``, with one commit on ``main``, in an Alpine container sharing the TM1
    container's network, so TM1 reaches it at ``git://localhost/model.git``. Not a ``file://``
    repository inside the TM1 container: TM1 can't push or pull through the local transport."""

    URL = "git://localhost/model.git"

    def __init__(self) -> None:
        self._container: Any = None
        self.url = GIVEN or GitDaemon.URL

    @property
    def local(self) -> bool:
        """Whether it's the daemon here, which the test can change behind TM1's back."""
        return self._container is not None

    def start(self) -> GitDaemon:
        if GIVEN is not None:
            return self
        from testcontainers.core.container import DockerContainer
        from testcontainers.core.waiting_utils import wait_for_logs

        from skipcheck_tm1_testcontainers import Tm1EngineContainer, Tm1ServerContainer

        tm1 = Tm1EngineContainer.shared() if IS_ENGINE else Tm1ServerContainer.shared()
        container = DockerContainer("alpine:3.21").with_kwargs(network_mode=f"container:{tm1.container_id}")
        container.with_command(["sh", "-c", _SEED])
        container.start()
        self._container = container
        wait_for_logs(container, "Ready to rumble", timeout=120)
        return self

    def stop(self) -> None:
        if self._container is not None:
            self._container.stop()
            self._container = None

    def _sh(self, *commands: str) -> str:
        code, output = self._container.exec(["sh", "-c", " && ".join(commands)])
        text = output.decode("utf-8", "replace") if isinstance(output, bytes) else str(output)
        if code != 0:
            raise RuntimeError(text)
        return text

    def files_on_main(self) -> str:
        """The files on ``main``, as Git sees them, one per line."""
        return self._sh("git --git-dir=/srv/model.git ls-tree -r --name-only main")

    def commit_copy_of(self, dimension: str, copy: str) -> None:
        """Commits, on the remote's ``main``, a copy of a dimension TM1 pushed, under another
        name: a change made elsewhere for TM1 to pull."""
        self._sh(
            "cd /tmp/seed",
            "git pull -q origin main",
            f"mkdir -p dimensions/{copy}.hierarchies",
            f"sed 's/{dimension}/{copy}/g' dimensions/{dimension}.json > dimensions/{copy}.json",
            f"sed 's/{dimension}/{copy}/g' dimensions/{dimension}.hierarchies/{dimension}.json > dimensions/{copy}.hierarchies/{copy}.json",
            "git add dimensions",
            f"git -c user.name=elsewhere -c user.email=elsewhere@example.com commit -qm 'Copy {dimension}'",
            "git push -q origin main",
        )


@pytest.fixture
def remote() -> Iterator[GitDaemon]:
    daemon = GitDaemon().start()
    try:
        yield daemon
    finally:
        daemon.stop()


def _delete_dimension(tm1: Tm1Client, name: str) -> None:
    try:
        tm1.dimensions.delete(name)
    except Tm1NotFoundError:
        pass


def test_pushes_the_model_and_pulls_it_back(tm1: Tm1Client, remote: GitDaemon) -> None:
    dimension = "git" + uuid.uuid4().hex[:8]
    copy = dimension + "copy"
    none = GitCredentials.none()
    with pytest.raises(Tm1BadRequestError):
        tm1.git.status(none)  # not initialized yet
    tied = tm1.git.init(remote.url, "dev", none, True)
    try:
        assert "main" in (tied.remote.branches or [])
        with pytest.raises(Tm1Error, match="Cannot create bare commit"):
            tm1.git.push("main", None, COMMIT, none, True)  # nothing has changed

        tm1.dimensions.create(
            DimensionCreate.builder()
            .name(dimension)
            .hierarchies([HierarchyCreate.builder().name(dimension).elements([ElementCreate.builder().name("North").build()]).build()])
            .build()
        )
        push = tm1.git.push("main", None, COMMIT, none, True)
        assert f"/dimensions/{dimension}.json" in (push.source_files or [])
        assert isinstance(tm1.git.plan(push.id), GitPushPlan)
        tm1.git.execute(push)
        assert tm1.git.plan(push.id) is None, "executing a plan uses it up"
        if remote.local:
            assert f"dimensions/{dimension}.json" in remote.files_on_main()
        assert tm1.git.status(none).deployed_commit.summary == "Add a dimension"

        tm1.dimensions.delete(dimension)
        skip = tm1.git.pull("main", none, None, True)
        assert skip.operations == [f"Skip Dimensions('{dimension}')"], "a pull compares commits, not the model"
        tm1.git.execute(skip)

        if remote.local:
            remote.commit_copy_of(dimension, copy)
            # The first pull after the remote moves: on TM1 11.8.034+ the commit TM1 had, then the
            # new one; before 11.8.034 and on TM1 v12, the new one at once
            pull = tm1.git.pull("main", none, None, True)
            if is_at_least(tm1, STALE_FIRST_PULL_FROM) and not is_v12(tm1):
                assert pull.commit.summary == "Add a dimension", "the commit TM1 had"
                pull = tm1.git.pull("main", none, None, True)
            assert pull.commit.summary == f"Copy {dimension}"
            assert f"Create Dimensions('{copy}')" in (pull.operations or [])
            tm1.git.execute(pull)
            assert tm1.dimensions.get(copy).name == copy

        with pytest.raises(Tm1Error, match="Cannot re-deploy"):
            tm1.git.deploy(remote.url, "main", "prod", none, True)  # refused while initialized
    finally:
        tm1.git.uninit(True)
        _delete_dimension(tm1, dimension)
        _delete_dimension(tm1, copy)

    if not remote.local:
        return  # the deploy below needs the remote's history as the local daemon made it
    tm1.git.deploy(remote.url, "main", "dev", none, True)
    try:
        assert tm1.git.status(none).deployed_commit.summary == f"Copy {dimension}"
        assert tm1.dimensions.get(dimension).name == dimension, "a deploy brings back the whole branch, the deleted dimension too"
    finally:
        tm1.git.uninit(True)
        _delete_dimension(tm1, dimension)
        _delete_dimension(tm1, copy)


def test_keeps_passwords_out_of_to_string() -> None:
    # repr is Python's toString
    assert repr(GitCredentials.password("me", "s3cret")) == "GitCredentials[username=me, password=***, sshKey=none]"

"""GitService against a real TM1: a port of the Java client's GitServiceTm1Test.

The Java test starts a ``git daemon`` container in TM1's network namespace; here the remote is
given: ``TM1_GIT_URL``, a ``git://`` (or ``https://``) repository TM1 can reach, whose ``main``
branch has a commit (TM1 can't make the first). The checks that need to change the remote
behind TM1's back (a commit made there) stay in the Java test.
"""

from __future__ import annotations

import os
import uuid

import pytest

from tm1_client import Tm1BadRequestError, Tm1Client, Tm1Error
from tm1_client._sync.services.git import GitCommitDetails, GitCredentials
from tm1_client.model import DimensionPatch, ElementPatch, GitPushPlan, HierarchyPatch

from .conftest import URL

REMOTE = os.environ.get("TM1_GIT_URL")

pytestmark = pytest.mark.skipif(URL is None or REMOTE is None, reason="no TM1 and Git remote configured (TM1_URL, TM1_GIT_URL)")

COMMIT = GitCommitDetails("Add a dimension", "tm1-client", "tm1-client@example.com")


def test_pushes_the_model_and_pulls_it_back(tm1: Tm1Client) -> None:
    assert REMOTE is not None
    dimension = "git" + uuid.uuid4().hex[:8]
    with pytest.raises(Tm1BadRequestError):
        tm1.git.status(GitCredentials.none())  # not initialized yet
    tied = tm1.git.init(REMOTE, "dev", GitCredentials.none(), True)
    try:
        assert "main" in (tied.remote.branches or [])
        with pytest.raises(Tm1Error, match="Cannot create bare commit"):
            tm1.git.push("main", None, COMMIT, GitCredentials.none(), True)  # nothing has changed

        # Through the generated API, so this doesn't depend on DimensionService
        tm1.api.dimensions.create(
            DimensionPatch.builder()
            .name(dimension)
            .hierarchies([HierarchyPatch.builder().name(dimension).elements([ElementPatch.builder().name("North").build()]).build()])
            .build()
        )
        push = tm1.git.push("main", None, COMMIT, GitCredentials.none(), True)
        assert f"/dimensions/{dimension}.json" in (push.source_files or [])
        assert isinstance(tm1.git.plan(push.id), GitPushPlan)
        tm1.git.execute(push)
        assert tm1.git.plan(push.id) is None, "executing a plan uses it up"
        assert tm1.git.status(GitCredentials.none()).deployed_commit.summary == "Add a dimension"

        tm1.api.dimensions[dimension].delete()
        skip = tm1.git.pull("main", GitCredentials.none(), None, True)
        assert skip.operations == [f"Skip Dimensions('{dimension}')"], "a pull compares commits, not the model"
        tm1.git.execute(skip)

        with pytest.raises(Tm1Error, match="Cannot re-deploy"):
            tm1.git.deploy(REMOTE, "main", "prod", GitCredentials.none(), True)  # refused while initialized
    finally:
        tm1.git.uninit(True)
        try:
            tm1.api.dimensions[dimension].delete()
        except Tm1Error:
            pass


def test_keeps_passwords_out_of_repr() -> None:
    assert repr(GitCredentials.password("me", "s3cret")) == "GitCredentials[username=me, password=***, sshKey=none]"

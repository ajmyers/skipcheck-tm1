# SPDX-License-Identifier: Apache-2.0

"""GitService, GitCredentials and GitCommitDetails, in both flavors, against a fake TM1."""

from __future__ import annotations

import json

import httpx
import pytest

from skipcheck_tm1._sync.services.git import GitCommitDetails, GitCredentials
from skipcheck_tm1.model import GitPlanExecutionMode, GitPullPlan, GitPushPlan

from ..conftest import Api, FakeTm1, target


def body(request: httpx.Request) -> object:
    return json.loads(request.content) if request.content else None


TOKEN = GitCredentials.password("tm1-bot", "s3cret")


def test_credentials_hide_their_secrets() -> None:
    assert repr(TOKEN) == "GitCredentials[username=tm1-bot, password=***, sshKey=none]"
    assert "s3cret" not in str(TOKEN) and TOKEN.password == "s3cret"
    ssh = GitCredentials.ssh_key("pub", "priv", None)
    assert repr(ssh) == "GitCredentials[username=None, password=none, sshKey=***]"
    assert GitCredentials.none() == GitCredentials()
    with pytest.raises(TypeError):
        GitCommitDetails("m", None, "e")  # type: ignore[arg-type]


def test_init_status_uninit_and_deploy(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"URL": "https://git.example.com/m.git", "Deployment": "dev"}
    tie = api.run(api.client.git.init("https://git.example.com/m.git", "dev", TOKEN, False))
    assert tie.deployment == "dev"
    assert (target(fake.last), body(fake.last)) == (
        "POST GitInit",
        {"URL": "https://git.example.com/m.git", "Username": "tm1-bot", "Password": "s3cret", "Deployment": "dev", "Force": False},
    )
    api.run(api.client.git.status(GitCredentials.none()))
    assert (target(fake.last), body(fake.last)) == ("POST GitStatus", {})
    fake.respond = lambda r: None
    api.run(api.client.git.uninit(True))
    assert (target(fake.last), body(fake.last)) == ("POST GitUninit", {"Force": True})
    api.run(api.client.git.deploy("git://h/m.git", "main", None, GitCredentials.ssh_key("pub", "priv", "pp"), True))
    assert (target(fake.last), body(fake.last)) == (
        "POST GitDeploy",
        {"URL": "git://h/m.git", "PublicKey": "pub", "PrivateKey": "priv", "Passphrase": "pp", "Branch": "main", "Force": True},
    )


def test_push_and_pull_make_plans_execute_carries_them_out(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"ID": "p1", "Branch": "main", "SourceFiles": ["dimensions/Region.json"]}
    commit = GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com")
    plan = api.run(api.client.git.push("main", None, commit, TOKEN, False))
    assert isinstance(plan, GitPushPlan) and plan.id == "p1"
    assert body(fake.last) == {
        "Username": "tm1-bot",
        "Password": "s3cret",
        "Branch": "main",
        "Message": "Add the Region dimension",
        "Author": "Ada Analyst",
        "Email": "ada@example.com",
        "Force": False,
    }
    fake.respond = lambda r: None
    api.run(api.client.git.execute(plan))
    assert (target(fake.last), body(fake.last)) == ("POST GitPlans('p1')/tm1.GitPushPlan/tm1.Execute", {})

    fake.respond = lambda r: {"ID": "p2", "Branch": "main"}
    pull = api.run(api.client.git.pull("main", TOKEN, GitPlanExecutionMode.SINGLE_COMMIT, True))
    assert isinstance(pull, GitPullPlan)
    assert body(fake.last)["ExecutionMode"] == "SingleCommit"  # type: ignore[index]
    fake.respond = lambda r: None
    api.run(api.client.git.execute(pull))
    assert target(fake.last) == "POST GitPlans('p2')/tm1.GitPullPlan/tm1.Execute"


def test_plans_are_found_in_the_list(api: Api, fake: FakeTm1) -> None:
    # TM1 (11.8) sends no @odata.type for GitPlans('id'); the list does
    fake.respond = lambda r: {
        "value": [
            {"@odata.type": "#ibm.tm1.api.v1.GitPushPlan", "ID": "a"},
            {"@odata.type": "#ibm.tm1.api.v1.GitPullPlan", "ID": "b"},
        ]
    }
    plans = api.run(api.client.git.plans())
    assert [type(p) for p in plans] == [GitPushPlan, GitPullPlan]
    assert target(fake.last) == "GET GitPlans"
    assert isinstance(api.run(api.client.git.plan("b")), GitPullPlan)
    assert api.run(api.client.git.plan("gone")) is None

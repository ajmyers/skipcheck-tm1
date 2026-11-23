"""Examples for GitService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import Git
from skipcheck_tm1.services import GitCommitDetails, GitCredentials


def init(tm1: Tm1Client, token: str) -> Git:
    # @start region = init
    # The remote's branch must already have a commit: TM1 can't make the first one
    credentials = GitCredentials.password("tm1-bot", token)
    return tm1.git.init("https://git.example.com/finance/model.git", "dev", credentials, False)
    # @end


def push(tm1: Tm1Client, credentials: GitCredentials) -> None:
    # @start region = push
    # Plan the commit, look at the files it would push, then push them
    commit = GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com")
    plan = tm1.git.push("main", None, commit, credentials, False)
    if plan.source_files:
        tm1.git.execute(plan)
    # @end


def pull(tm1: Tm1Client, credentials: GitCredentials, newest: str) -> None:
    # @start region = pull
    # TM1 11.8 plans the commit it had on the first pull after the remote moves on:
    # pull again until the plan is for the commit wanted, then carry it out
    for _ in range(3):
        plan = tm1.git.pull("main", credentials, None, False)
        if plan.commit is not None and plan.commit.id and newest.startswith(plan.commit.id):
            tm1.git.execute(plan)
            break
    # @end

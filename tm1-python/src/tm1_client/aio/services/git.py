"""TM1 Git: putting a model under version control, and moving it between servers (the Java
client's ``net.ajmyers.tm1.client.service.git``)."""

from __future__ import annotations

from typing import Any

from ..._calls import endpoint
from ..._generated.model import Git, GitPlan, GitPlanExecutionMode, GitPullPlan, GitPushPlan
from ..._generated.operations import Tm1Operations
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request
from ...values import GitCommitDetails, GitCredentials
from ._base import Service, _require

__all__ = ["GitCommitDetails", "GitCredentials", "GitService"]

_PLANS = ODataPath.of("GitPlans")


def _put(parameters: dict[str, Any], name: str, value: Any) -> None:
    if value is not None:
        parameters[name] = value


class GitService(Service):
    """TM1 Git (schema 11.5.0): ties the model to a Git repository, pushes its objects there as
    JSON files (``dimensions/Region.json``, …), and pulls them back, on this server or another.

    Pushing and pulling are two steps: :meth:`push` and :meth:`pull` make a plan saying what
    would happen (the files, or the operations, such as ``Skip Dimensions('Region')``), and
    :meth:`execute` carries it out.

    What TM1 (11.8) does here that you might not expect:

    * A plan belongs to the TM1 session that made it: another session can't see it or run it.
      Executing a plan uses it up; it's gone afterwards.
    * The remote needs a commit on the branch to start from: TM1 can't make a repository's
      first commit, and pushing when the model hasn't changed is refused too (both "Cannot
      create bare commit").
    * A local ``file://`` remote works for :meth:`init` and :meth:`status`, but not for pushing
      or pulling ("shallow fetch is not supported by the local transport"); use ``https://``,
      ``ssh://`` or ``git://``. TM1 also refuses a local repository its own user doesn't own.
    * :meth:`deploy` is refused while Git is initialized ("Cannot re-deploy"): it's for a
      server not yet tied to the repository.
    * A pull compares commits, not the model: it plans the changes between the commit last
      deployed and the branch, so an object deleted on the server since is skipped
      (``Skip Dimensions('Region')``), not brought back. :meth:`deploy` brings the whole branch.
    * The first pull after the remote moves on still plans the commit TM1 had; the next pull
      plans the new one. Check the plan's ``Commit`` before executing it.

    Tie the model to a repository (the remote's branch must already have a commit)::

        credentials = GitCredentials.password("tm1-bot", token)
        await tm1.git.init("https://git.example.com/finance/model.git", "dev", credentials, False)

    Push the model's changes: plan the commit, look at the files, then push them::

        commit = GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com")
        plan = await tm1.git.push("main", None, commit, credentials, False)
        if plan.source_files:
            await tm1.git.execute(plan)

    Pull a branch, once the plan is for the commit wanted (TM1 11.8 plans the commit it had on
    the first pull after the remote moves on)::

        for _ in range(3):
            plan = await tm1.git.pull("main", credentials, None, False)
            if plan.commit and newest.startswith(plan.commit.id):
                await tm1.git.execute(plan)
                break
    """

    @endpoint("ActionImport GitInit")
    async def init(
        self,
        url: str,
        deployment: str | None,
        credentials: GitCredentials,
        force: bool,
        config: Any = None,
    ) -> Git:
        """``GitInit``: ties the model to the repository at ``url``, as deployment ``deployment``
        (such as ``dev``) if given; ``force`` replaces an existing tie; ``config`` is a
        ``GitConfigurationPatch``, or None for TM1's. Returns the tie: URL, deployment, deployed
        commit, and the remote's branches and tags."""
        parameters: dict[str, Any] = {"URL": _require(url, "url")}
        _require(credentials, "credentials")._add_to(parameters)
        _put(parameters, "Deployment", deployment)
        parameters["Force"] = bool(force)
        _put(parameters, "Config", config)
        return await self._call(Tm1Operations.GIT_INIT, parameters, Git)

    @endpoint("ActionImport GitUninit")
    async def uninit(self, force: bool) -> None:
        """``GitUninit``: unties the model from its repository, even with a plan waiting if
        ``force``. The model is left as it is."""
        await self._call(Tm1Operations.GIT_UNINIT, {"Force": bool(force)}, None)

    @endpoint("ActionImport GitStatus")
    async def status(self, credentials: GitCredentials) -> Git:
        """``GitStatus``: the tie, and what's on the remote. A ``Tm1BadRequestError`` if Git
        isn't initialized."""
        parameters: dict[str, Any] = {}
        _require(credentials, "credentials")._add_to(parameters)
        return await self._call(Tm1Operations.GIT_STATUS, parameters, Git)

    @endpoint("ActionImport GitPush")
    async def push(
        self,
        branch: str,
        new_branch: str | None,
        commit: GitCommitDetails,
        credentials: GitCredentials,
        force: bool,
    ) -> GitPushPlan:
        """``GitPush``: plans a commit of the model's changes on top of ``branch`` (which must
        exist on the remote), pushed to ``new_branch`` or ``branch``; ``force`` pushes even if
        the remote moved on. The plan lists the files; :meth:`execute` pushes them."""
        parameters: dict[str, Any] = {}
        _require(credentials, "credentials")._add_to(parameters)
        parameters["Branch"] = _require(branch, "branch")
        _put(parameters, "NewBranch", new_branch)
        _require(commit, "commit")
        parameters["Message"] = commit.message
        parameters["Author"] = commit.author
        parameters["Email"] = commit.email
        parameters["Force"] = bool(force)
        return await self._call(Tm1Operations.GIT_PUSH, parameters, GitPushPlan)

    @endpoint("ActionImport GitPull")
    async def pull(
        self,
        branch: str,
        credentials: GitCredentials,
        mode: GitPlanExecutionMode | None,
        force: bool,
    ) -> GitPullPlan:
        """``GitPull``: plans bringing the model up to ``branch``, one commit or each in turn
        (``mode``, None for TM1's ``SingleCommit``); ``force`` pulls over local changes. Pull
        again if the plan's ``Commit`` isn't the branch's newest: TM1 (11.8) lags a pull behind
        the remote. The plan lists the operations; :meth:`execute` carries them out."""
        parameters: dict[str, Any] = {}
        _require(credentials, "credentials")._add_to(parameters)
        parameters["Branch"] = _require(branch, "branch")
        _put(parameters, "ExecutionMode", None if mode is None else mode.value)
        parameters["Force"] = bool(force)
        return await self._call(Tm1Operations.GIT_PULL, parameters, GitPullPlan)

    @endpoint("ActionImport GitDeploy")
    async def deploy(
        self,
        url: str,
        branch: str,
        deployment: str | None,
        credentials: GitCredentials,
        force: bool,
        config: Any = None,
    ) -> None:
        """``GitDeploy``: ties a server not yet tied to a repository, and pulls ``branch`` into
        it, in one step. Refused while Git is initialized. :meth:`status` then shows the commit."""
        parameters: dict[str, Any] = {"URL": _require(url, "url")}
        _require(credentials, "credentials")._add_to(parameters)
        parameters["Branch"] = _require(branch, "branch")
        _put(parameters, "Deployment", deployment)
        parameters["Force"] = bool(force)
        _put(parameters, "Config", config)
        await self._call(Tm1Operations.GIT_DEPLOY, parameters, None)

    @endpoint("EntitySet GitPlans")
    async def plans(self, query: ODataQuery | None = None, **options: Any) -> list[GitPlan]:
        """``GET GitPlans``: this session's plans not yet executed, each a ``GitPushPlan`` or
        ``GitPullPlan``. A ``Tm1BadRequestError`` if Git isn't initialized."""
        return await self._session.collect(Tm1Request.list(_PLANS, ODataQuery.of(query, **options), GitPlan))

    @endpoint("EntitySet GitPlans")
    async def plan(self, id: str) -> GitPlan | None:
        """One of this session's plans, by ID, or None if it has none by that ID (an executed
        plan is gone). TM1 (11.8) sends no ``@odata.type`` for ``GitPlans('id')``, so it
        couldn't be told apart as a push or a pull; the list says, so the plan is found there."""
        _require(id, "id")
        for plan in await self.plans():
            if plan.id == id:
                return plan
        return None

    @endpoint("Action GitPushPlan/Execute", "Action GitPullPlan/Execute")
    async def execute(self, plan: GitPlan) -> None:
        """``tm1.Execute``: carries out a plan (from :meth:`push`, :meth:`pull` or :meth:`plans`),
        pushing its files or pulling its operations. The plan is used up."""
        plan_id = _require(_require(plan, "plan").id, "the plan's ID")
        if isinstance(plan, GitPushPlan):
            operation, cast = Tm1Operations.GIT_PUSH_PLAN_EXECUTE, "tm1.GitPushPlan"
        elif isinstance(plan, GitPullPlan):
            operation, cast = Tm1Operations.GIT_PULL_PLAN_EXECUTE, "tm1.GitPullPlan"
        else:
            raise ValueError(f"Not a push or pull plan: {plan!r}")
        request = Tm1Request.post(_PLANS.key(plan_id).cast(cast).action(operation.name), None, None)
        await self._session.execute(request.requires(operation.capability()))

    async def _call(self, operation: Tm1Operation, parameters: dict[str, Any], type: Any) -> Any:
        path = ODataPath.of(_require(operation.import_name, operation.name))
        return await self._session.execute(Tm1Request.post(path, parameters, type).requires(operation.capability()))

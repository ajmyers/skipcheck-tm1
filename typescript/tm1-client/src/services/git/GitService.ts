// SPDX-License-Identifier: Apache-2.0

import type { Tm1Model } from "../../model.js";
import { Git, GitPlan, GitPullPlan, GitPushPlan, Tm1Operations, type GitConfigurationPatch, type GitPlanExecutionMode } from "../../generated/index.js";
import { typeOf } from "../../json.js";
import { ODataPath, ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import type { GitCommitDetails } from "./GitCommitDetails.js";
import { putIfSet, type GitCredentials } from "./GitCredentials.js";

const PLANS = ODataPath.of("GitPlans");

/** TM1 Git (schema 11.5.0): ties the model to a Git repository, pushes its objects there as JSON
 * files (`dimensions/Region.json`, …), and pulls them back, on this server or another.
 *
 * Pushing and pulling are two steps: {@link push} and {@link pull} make a plan saying what would
 * happen (the files, or the operations, such as `Skip Dimensions('Region')`), and {@link execute}
 * carries it out.
 *
 * What TM1 (11.8) does here that you might not expect:
 * - A plan belongs to the TM1 session that made it: another session can't see it or run it.
 *   Executing a plan uses it up; it's gone afterwards.
 * - The remote needs a commit on the branch to start from: TM1 can't make a repository's first
 *   commit, and pushing when the model hasn't changed is refused too (both "Cannot create bare
 *   commit").
 * - A local `file://` remote works for {@link init} and {@link status}, but not for pushing or
 *   pulling ("shallow fetch is not supported by the local transport"); use `https://`, `ssh://` or
 *   `git://`. TM1 also refuses a local repository its own user doesn't own.
 * - {@link deploy} is refused while Git is initialized ("Cannot re-deploy"): it's for a server not
 *   yet tied to the repository.
 * - A pull compares commits, not the model: it plans the changes between the commit last deployed
 *   and the branch, so an object deleted on the server since is skipped (`Skip Dimensions('Region')`),
 *   not brought back. {@link deploy} brings the whole branch.
 * - The first pull after the remote moves on still plans the commit TM1 had; the next pull plans
 *   the new one. Check the plan's `Commit` before executing it.
 *
 * Tie the model to a repository, push its changes, and pull a branch:
 * ```ts
 * const credentials = GitCredentials.password("me", token);
 * await tm1.git.init("https://github.com/me/model.git", "dev", credentials, false, null);
 * const push = await tm1.git.push("main", null, new GitCommitDetails("Add Region", "Me", "me@example.com"), credentials, false);
 * await tm1.git.execute(push);
 * const pull = await tm1.git.pull("main", credentials, null, false);
 * if (pull.Commit?.ID === expected) await tm1.git.execute(pull);
 * ```
 */
export class GitService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    init: ["ActionImport GitInit"],
    uninit: ["ActionImport GitUninit"],
    status: ["ActionImport GitStatus"],
    push: ["ActionImport GitPush"],
    pull: ["ActionImport GitPull"],
    deploy: ["ActionImport GitDeploy"],
    plans: ["EntitySet GitPlans"],
    plan: ["EntitySet GitPlans"],
    execute: ["Action GitPushPlan/Execute", "Action GitPullPlan/Execute"],
  } as const satisfies Tm1Endpoints<GitService>;

  /** `GitInit`: ties the model to the repository at `url`, under the deployment's name (such as
   * `dev`) if given, replacing an existing tie if `force`, with the Git configuration `config` or
   * TM1's. Returns the tie: URL, deployment, deployed commit, and the remote's branches and tags. */
  init(
    url: string,
    deployment: string | null | undefined,
    credentials: GitCredentials,
    force: boolean,
    config?: GitConfigurationPatch | null,
    options?: Tm1CallOptions,
  ): Promise<Git> {
    const parameters: Record<string, unknown> = { URL: required(url, "url") };
    required(credentials, "credentials").addTo(parameters);
    putIfSet(parameters, "Deployment", deployment);
    parameters["Force"] = flag(force);
    putIfSet(parameters, "Config", config);
    return this.call(Tm1Operations.GitInit, parameters, Git, options);
  }

  /** `GitUninit`: unties the model from its repository, even with a plan waiting if `force`. The
   * model is left as it is. */
  uninit(force: boolean, options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.GitUninit, { Force: flag(force) }, undefined, options);
  }

  /** `GitStatus`: the tie, and what's on the remote. Fails with a `Tm1BadRequestException` if Git
   * isn't initialized. */
  status(credentials: GitCredentials, options?: Tm1CallOptions): Promise<Git> {
    const parameters: Record<string, unknown> = {};
    required(credentials, "credentials").addTo(parameters);
    return this.call(Tm1Operations.GitStatus, parameters, Git, options);
  }

  /** `GitPush`: plans a commit of the model's changes on top of `branch` (which must exist on the
   * remote), pushed to `newBranch` or, with none, `branch`; even if the remote moved on if `force`.
   * The plan lists the files; {@link execute} pushes them. */
  async push(
    branch: string,
    newBranch: string | null | undefined,
    commit: GitCommitDetails,
    credentials: GitCredentials,
    force: boolean,
    options?: Tm1CallOptions,
  ): Promise<GitPushPlan> {
    const parameters: Record<string, unknown> = {};
    required(credentials, "credentials").addTo(parameters);
    parameters["Branch"] = required(branch, "branch");
    putIfSet(parameters, "NewBranch", newBranch);
    required(commit, "commit");
    parameters["Message"] = commit.message;
    parameters["Author"] = commit.author;
    parameters["Email"] = commit.email;
    parameters["Force"] = flag(force);
    return planOf(await this.call(Tm1Operations.GitPush, parameters, GitPushPlan, options), "GitPushPlan");
  }

  /** `GitPull`: plans bringing the model up to `branch`, one commit or each commit in turn (`mode`,
   * or TM1's, `SingleCommit`), over local changes if `force`. Pull again if the plan's `Commit` isn't
   * the branch's newest: TM1 (11.8) lags a pull behind the remote. The plan lists the operations;
   * {@link execute} carries them out. */
  async pull(
    branch: string,
    credentials: GitCredentials,
    mode: GitPlanExecutionMode | null | undefined,
    force: boolean,
    options?: Tm1CallOptions,
  ): Promise<GitPullPlan> {
    const parameters: Record<string, unknown> = {};
    required(credentials, "credentials").addTo(parameters);
    parameters["Branch"] = required(branch, "branch");
    putIfSet(parameters, "ExecutionMode", mode);
    parameters["Force"] = flag(force);
    return planOf(await this.call(Tm1Operations.GitPull, parameters, GitPullPlan, options), "GitPullPlan");
  }

  /** `GitDeploy`: ties a server not yet tied to a repository, and pulls `branch` into it, in one
   * step; over local changes if `force`. Refused while Git is initialized. {@link status} then shows
   * the commit. */
  deploy(
    url: string,
    branch: string,
    deployment: string | null | undefined,
    credentials: GitCredentials,
    force: boolean,
    config?: GitConfigurationPatch | null,
    options?: Tm1CallOptions,
  ): Promise<void> {
    const parameters: Record<string, unknown> = { URL: required(url, "url") };
    required(credentials, "credentials").addTo(parameters);
    parameters["Branch"] = required(branch, "branch");
    putIfSet(parameters, "Deployment", deployment);
    parameters["Force"] = flag(force);
    putIfSet(parameters, "Config", config);
    return this.call(Tm1Operations.GitDeploy, parameters, undefined, options);
  }

  /** `GET GitPlans`, streamed: this session's plans not yet executed, each a `GitPushPlan` or
   * `GitPullPlan` (`typeOf(GitPlan, plan)`). Fails with a `Tm1BadRequestException` if Git isn't
   * initialized. */
  plans(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<GitPlan> {
    return this.session.stream(Tm1Request.list(PLANS, query, GitPlan), options);
  }

  /** One of this session's plans, by ID, or undefined if this session has none by that ID (an
   * executed plan is gone). TM1 (11.8) sends no `@odata.type` for `GitPlans('id')`, so it couldn't
   * be told apart as a push or a pull; the list says, so the plan is found there. */
  plan(id: string, options?: Tm1CallOptions): Promise<GitPlan | undefined> {
    required(id, "id");
    return this.plans(ODataQuery.none(), options)
      .filter((plan) => plan.ID === id)
      .first();
  }

  /** `tm1.Execute`: carries out a plan, from {@link push}, {@link pull} or {@link plans}, pushing its
   * files or pulling its operations. The plan is used up. */
  async execute(plan: GitPlan, options?: Tm1CallOptions): Promise<void> {
    const id = required(required(plan, "plan").ID, "the plan's ID");
    const kind = typeOf(GitPlan, plan);
    let operation: Tm1Operation;
    if (kind === "GitPushPlan") operation = Tm1Operations.GitPushPlanExecute;
    else if (kind === "GitPullPlan") operation = Tm1Operations.GitPullPlanExecute;
    else throw new RangeError("Not a push or pull plan: " + JSON.stringify(plan));
    const path = PLANS.key(id)
      .cast("tm1." + kind)
      .action(operation.name);
    await this.session.execute(Tm1Request.post(path, null).requires(operation.capability()), options);
  }

  private call<T>(
    operation: Tm1Operation,
    parameters: Record<string, unknown>,
    type: Tm1Model<T> | undefined,
    options: Tm1CallOptions | undefined,
  ): Promise<T> {
    const path = ODataPath.of(required(operation.importName, operation.name));
    const request = (type ? Tm1Request.post(path, parameters, type) : (Tm1Request.post(path, parameters) as unknown as Tm1Request<T>)).requires(
      operation.capability(),
    );
    return this.session.execute(request, options);
  }
}

function flag(force: boolean): boolean {
  if (typeof force !== "boolean") throw new TypeError("force is true or false");
  return force;
}

/** The plan an action returned, marked as the kind it was read as (Java reads it as that class):
 * TM1 sends no `@odata.type` for an action's declared return type, and {@link GitService.execute}
 * tells pushes from pulls by it. */
function planOf<P extends GitPlan>(plan: P, kind: "GitPushPlan" | "GitPullPlan"): P {
  if (plan && typeof plan === "object" && plan["@odata.type"] === undefined) plan["@odata.type"] = "#ibm.tm1.api.v1." + kind;
  return plan;
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>TM1 Git (schema 11.5.0): ties the model to a Git repository, pushes its objects there as
/// JSON files (<c>dimensions/Region.json</c>, …), and pulls them back, on this server or another.
/// <para>Pushing and pulling are two steps: <see cref="PushAsync"/> and <see cref="PullAsync"/> make a
/// plan saying what would happen (the files, or the operations, such as
/// <c>Skip Dimensions('Region')</c>), and <see cref="ExecuteAsync"/> carries it out.</para>
/// <para>What TM1 (11.8) does here that you might not expect:</para>
/// <list type="bullet">
/// <item>A plan belongs to the TM1 session that made it: another session can't see it or run it.
/// Executing a plan uses it up; it's gone afterwards.</item>
/// <item>The remote needs a commit on the branch to start from: TM1 can't make a repository's first
/// commit, and pushing when the model hasn't changed is refused too (both "Cannot create bare
/// commit").</item>
/// <item>A local <c>file://</c> remote works for <see cref="InitAsync"/> and <see cref="StatusAsync"/>,
/// but not for pushing or pulling ("shallow fetch is not supported by the local transport"); use
/// <c>https://</c>, <c>ssh://</c> or <c>git://</c>. TM1 also refuses a local repository its own user
/// doesn't own.</item>
/// <item><see cref="DeployAsync"/> is refused while Git is initialized ("Cannot re-deploy"): it's for a
/// server not yet tied to the repository.</item>
/// <item>A pull compares commits, not the model: it plans the changes between the commit last deployed
/// and the branch, so an object deleted on the server since is skipped
/// (<c>Skip Dimensions('Region')</c>), not brought back. <see cref="DeployAsync"/> brings the whole
/// branch.</item>
/// <item>The first pull after the remote moves on still plans the commit TM1 had; the next pull plans
/// the new one. Check the plan's <c>Commit</c> before executing it.</item>
/// </list>
/// <para>Tie the model to a repository:</para>
/// <code>
/// // The remote's branch must already have a commit: TM1 can't make the first one
/// var credentials = GitCredentials.Password("tm1-bot", token);
/// var git = await tm1.Git.InitAsync("https://git.example.com/finance/model.git", "dev", credentials, false, null);
/// </code>
/// <para>Push the model's changes:</para>
/// <code>
/// // Plan the commit, look at the files it would push, then push them
/// var commit = new GitCommitDetails("Add the Region dimension", "Ada Analyst", "ada@example.com");
/// var plan = await tm1.Git.PushAsync("main", null, commit, credentials, false);
/// if (plan.SourceFiles is { Count: &gt; 0 })
/// {
///     await tm1.Git.ExecuteAsync(plan);
/// }
/// </code>
/// <para>Pull a branch, once the plan is for the commit wanted:</para>
/// <code>
/// // TM1 11.8 plans the commit it had on the first pull after the remote moves on:
/// // pull again until the plan is for the commit wanted, then carry it out
/// for (var attempt = 0; attempt &lt; 3; attempt++)
/// {
///     var plan = await tm1.Git.PullAsync("main", credentials, null, false);
///     if (plan.Commit?.Id is { } id &amp;&amp; newest.StartsWith(id, StringComparison.Ordinal))
///     {
///         await tm1.Git.ExecuteAsync(plan);
///         break;
///     }
/// }
/// </code>
/// </summary>
public sealed class GitService : Tm1Service
{
    private static readonly ODataPath Plans = ODataPath.Of("GitPlans");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public GitService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GitInit</c>: ties the model to the repository at <paramref name="url"/>, as the
    /// deployment <paramref name="deployment"/> (such as <c>dev</c>, or null for none), replacing an
    /// existing tie if <paramref name="force"/>, with <paramref name="config"/> or, if null, TM1's Git
    /// configuration. Returns the tie: URL, deployment, deployed commit, and the remote's branches and tags.</summary>
    [Tm1Endpoint("ActionImport GitInit")]
    public Task<Git> InitAsync(
        string url,
        string? deployment,
        GitCredentials credentials,
        bool force,
        GitConfigurationPatch? config,
        CancellationToken cancellationToken = default
    )
    {
        var parameters = new Dictionary<string, object?> { ["URL"] = Require(url, nameof(url)) };
        Require(credentials, nameof(credentials)).AddTo(parameters);
        GitCredentials.Put(parameters, "Deployment", deployment);
        parameters["Force"] = force;
        GitCredentials.Put(parameters, "Config", config);
        return Call<Git>(Tm1Operations.GitInit, parameters, cancellationToken);
    }

    /// <summary><c>GitUninit</c>: unties the model from its repository, even with a plan waiting if
    /// <paramref name="force"/>. The model is left as it is.</summary>
    [Tm1Endpoint("ActionImport GitUninit")]
    public Task UninitAsync(bool force, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.GitUninit;
        var request = Tm1Request.Post(ImportPath(operation), new Dictionary<string, object?> { ["Force"] = force }).Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }

    /// <summary><c>GitStatus</c>: the tie, and what's on the remote. Fails with a
    /// <c>Tm1BadRequestException</c> if Git isn't initialized.</summary>
    [Tm1Endpoint("ActionImport GitStatus")]
    public Task<Git> StatusAsync(GitCredentials credentials, CancellationToken cancellationToken = default)
    {
        var parameters = new Dictionary<string, object?>();
        Require(credentials, nameof(credentials)).AddTo(parameters);
        return Call<Git>(Tm1Operations.GitStatus, parameters, cancellationToken);
    }

    /// <summary><c>GitPush</c>: plans a commit of the model's changes on top of <paramref name="branch"/>,
    /// which must exist on the remote, pushed to <paramref name="newBranch"/> or, if null, to
    /// <paramref name="branch"/>; even if the remote moved on if <paramref name="force"/>. The plan lists
    /// the files; <see cref="ExecuteAsync"/> pushes them.</summary>
    [Tm1Endpoint("ActionImport GitPush")]
    public Task<GitPushPlan> PushAsync(
        string branch,
        string? newBranch,
        GitCommitDetails commit,
        GitCredentials credentials,
        bool force,
        CancellationToken cancellationToken = default
    )
    {
        Require(commit, nameof(commit));
        var parameters = new Dictionary<string, object?>();
        Require(credentials, nameof(credentials)).AddTo(parameters);
        parameters["Branch"] = Require(branch, nameof(branch));
        GitCredentials.Put(parameters, "NewBranch", newBranch);
        parameters["Message"] = commit.Message;
        parameters["Author"] = commit.Author;
        parameters["Email"] = commit.Email;
        parameters["Force"] = force;
        return Call<GitPushPlan>(Tm1Operations.GitPush, parameters, cancellationToken);
    }

    /// <summary><c>GitPull</c>: plans bringing the model up to <paramref name="branch"/>, one commit or
    /// each commit in turn (<paramref name="mode"/>, or null for TM1's <c>SingleCommit</c>), over local
    /// changes if <paramref name="force"/>. Pull again if the plan's <c>Commit</c> isn't the branch's
    /// newest: TM1 (11.8) lags a pull behind the remote. The plan lists the operations;
    /// <see cref="ExecuteAsync"/> carries them out.</summary>
    [Tm1Endpoint("ActionImport GitPull")]
    public Task<GitPullPlan> PullAsync(
        string branch,
        GitCredentials credentials,
        GitPlanExecutionMode? mode,
        bool force,
        CancellationToken cancellationToken = default
    )
    {
        var parameters = new Dictionary<string, object?>();
        Require(credentials, nameof(credentials)).AddTo(parameters);
        parameters["Branch"] = Require(branch, nameof(branch));
        GitCredentials.Put(parameters, "ExecutionMode", mode is { } m ? Tm1EnumConverter.Of(typeof(GitPlanExecutionMode)).Name(m) : null);
        parameters["Force"] = force;
        return Call<GitPullPlan>(Tm1Operations.GitPull, parameters, cancellationToken);
    }

    /// <summary><c>GitDeploy</c>: ties a server not yet tied to a repository, and pulls
    /// <paramref name="branch"/> into it, in one step. Refused while Git is initialized. Completes when
    /// it's deployed; <see cref="StatusAsync"/> then shows the commit.</summary>
    [Tm1Endpoint("ActionImport GitDeploy")]
    public Task DeployAsync(
        string url,
        string branch,
        string? deployment,
        GitCredentials credentials,
        bool force,
        GitConfigurationPatch? config,
        CancellationToken cancellationToken = default
    )
    {
        var parameters = new Dictionary<string, object?> { ["URL"] = Require(url, nameof(url)) };
        Require(credentials, nameof(credentials)).AddTo(parameters);
        parameters["Branch"] = Require(branch, nameof(branch));
        GitCredentials.Put(parameters, "Deployment", deployment);
        parameters["Force"] = force;
        GitCredentials.Put(parameters, "Config", config);
        var operation = Tm1Operations.GitDeploy;
        return Session.ExecuteAsync(Tm1Request.Post(ImportPath(operation), parameters).Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>GET GitPlans</c>, streamed: this session's plans not yet executed, each a
    /// <c>GitPushPlan</c> or <c>GitPullPlan</c>. Fails with a <c>Tm1BadRequestException</c> if Git
    /// isn't initialized.</summary>
    [Tm1Endpoint("EntitySet GitPlans")]
    public IAsyncEnumerable<GitPlan> PlansAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<GitPlan>(Plans, query), cancellationToken);

    /// <summary>One of this session's plans, by ID, or null if this session has none by that ID (an
    /// executed plan is gone). TM1 (11.8) sends no <c>@odata.type</c> for <c>GitPlans('id')</c>, so it
    /// couldn't be told apart as a push or a pull; the list says, so the plan is found there.</summary>
    [Tm1Endpoint("EntitySet GitPlans")]
    public async Task<GitPlan?> PlanAsync(string id, CancellationToken cancellationToken = default)
    {
        Require(id, nameof(id));
        await foreach (var plan in PlansAsync(null, cancellationToken).ConfigureAwait(false))
        {
            if (id == plan.Id)
            {
                return plan;
            }
        }
        return null;
    }

    /// <summary><c>tm1.Execute</c>: carries out a plan, from <see cref="PushAsync"/>,
    /// <see cref="PullAsync"/> or <see cref="PlansAsync"/>, pushing its files or pulling its operations.
    /// The plan is used up.</summary>
    /// <exception cref="ArgumentException">It's not a push or pull plan.</exception>
    [Tm1Endpoint("Action GitPushPlan/Execute")]
    [Tm1Endpoint("Action GitPullPlan/Execute")]
    public Task ExecuteAsync(GitPlan plan, CancellationToken cancellationToken = default)
    {
        var id = Require(Require(plan, nameof(plan)).Id, "the plan's ID");
        var (operation, cast) = plan switch
        {
            GitPushPlan => (Tm1Operations.GitPushPlanExecute, "tm1.GitPushPlan"),
            GitPullPlan => (Tm1Operations.GitPullPlanExecute, "tm1.GitPullPlan"),
            _ => throw new ArgumentException("Not a push or pull plan: " + id, nameof(plan)),
        };
        var request = Tm1Request.Post(Plans.Key(id).Cast(cast).Action(operation.Name), null).Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }

    private Task<T> Call<T>(Tm1Operation operation, Dictionary<string, object?> parameters, CancellationToken cancellationToken) =>
        Session.ExecuteAsync(Tm1Request.Post<T>(ImportPath(operation), parameters).Requires(operation.Capability()), cancellationToken);

    private static ODataPath ImportPath(Tm1Operation operation) => ODataPath.Of(operation.ImportName ?? throw new InvalidOperationException(operation.Name));
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>GitService against a fake TM1: each method sends what the Java client's does, with the
/// credentials it was given and no others.</summary>
public class GitServiceTests
{
    private static readonly GitCommitDetails Commit = new("Add a dimension", "tm1-client", "tm1-client@example.com");

    private readonly FakeTm1 _tm1 = new();

    private string Sent => $"{_tm1.Last.Method} {_tm1.Last.Target}";

    [Fact]
    public async Task InitsChecksAndUninits()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["URL"] = "git://localhost/model.git", ["Remote"] = new { Branches = new[] { "main" } } };
        var git = await tm1.Git.InitAsync("git://localhost/model.git", "dev", GitCredentials.Password("me", "s3cret"), true, null);
        Assert.Equal(["main"], git.Remote!.Branches!);
        Assert.Equal(
            ("POST GitInit", """{"URL":"git://localhost/model.git","Username":"me","Password":"s3cret","Deployment":"dev","Force":true}"""),
            (Sent, _tm1.Last.Text)
        );
        await tm1.Git.InitAsync("git://localhost/model.git", null, GitCredentials.None(), false, GitConfigurationPatch.Builder().Build());
        Assert.Equal("""{"URL":"git://localhost/model.git","Force":false,"Config":{}}""", _tm1.Last.Text);

        await tm1.Git.StatusAsync(GitCredentials.SshKey("pub", "priv"));
        Assert.Equal(("POST GitStatus", """{"PublicKey":"pub","PrivateKey":"priv"}"""), (Sent, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Git.UninitAsync(true);
        Assert.Equal(("POST GitUninit", """{"Force":true}"""), (Sent, _tm1.Last.Text));
        await tm1.Git.DeployAsync("git://localhost/model.git", "main", "prod", GitCredentials.None(), false, null);
        Assert.Equal(("POST GitDeploy", """{"URL":"git://localhost/model.git","Branch":"main","Deployment":"prod","Force":false}"""), (Sent, _tm1.Last.Text));
    }

    [Fact]
    public async Task PlansAndExecutesPushesAndPulls()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["@odata.type"] = "#ibm.tm1.api.v1.GitPushPlan",
            ["ID"] = "p1",
            ["SourceFiles"] = new[] { "/dimensions/Region.json" },
        };
        var push = await tm1.Git.PushAsync("main", "feature", Commit, GitCredentials.None(), false);
        Assert.Equal(["/dimensions/Region.json"], push.SourceFiles!);
        Assert.Equal("POST GitPush", Sent);
        Assert.Equal(
            """{"Branch":"main","NewBranch":"feature","Message":"Add a dimension","Author":"tm1-client","Email":"tm1-client@example.com","Force":false}""",
            _tm1.Last.Text
        );

        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["@odata.type"] = "#ibm.tm1.api.v1.GitPullPlan",
            ["ID"] = "p2",
            ["Operations"] = new[] { "Skip Dimensions('Region')" },
        };
        var pull = await tm1.Git.PullAsync("main", GitCredentials.None(), GitPlanExecutionMode.MultipleCommit, true);
        Assert.Equal(["Skip Dimensions('Region')"], pull.Operations!);
        Assert.Equal(("POST GitPull", """{"Branch":"main","ExecutionMode":"MultipleCommit","Force":true}"""), (Sent, _tm1.Last.Text));
        await tm1.Git.PullAsync("main", GitCredentials.None(), null, false);
        Assert.Equal("""{"Branch":"main","Force":false}""", _tm1.Last.Text);

        _tm1.Respond = _ => null;
        await tm1.Git.ExecuteAsync(push);
        Assert.Equal("POST GitPlans('p1')/tm1.GitPushPlan/tm1.Execute", Sent);
        await tm1.Git.ExecuteAsync(pull);
        Assert.Equal("POST GitPlans('p2')/tm1.GitPullPlan/tm1.Execute", Sent);
        await Assert.ThrowsAsync<ArgumentException>(() => tm1.Git.ExecuteAsync(new GitPlan { Id = "p3" }));
    }

    [Fact]
    public async Task FindsAPlanInTheList()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ =>
            FakeTm1.Collection(
                new Dictionary<string, object?> { ["@odata.type"] = "#ibm.tm1.api.v1.GitPushPlan", ["ID"] = "p1" },
                new Dictionary<string, object?> { ["@odata.type"] = "#ibm.tm1.api.v1.GitPullPlan", ["ID"] = "p2" }
            );
        var plans = await tm1.Git.PlansAsync(ODataQuery.None().Select("ID")).ToListAsync();
        Assert.Equal([typeof(GitPushPlan), typeof(GitPullPlan)], plans.Select(p => p.GetType()));
        Assert.Equal("GET GitPlans?$select=ID", Sent);
        Assert.IsType<GitPullPlan>(await tm1.Git.PlanAsync("p2"));
        Assert.Equal("GET GitPlans", Sent);
        Assert.Null(await tm1.Git.PlanAsync("gone"));
    }

    [Fact]
    public void KeepsPasswordsOutOfToString()
    {
        Assert.Equal("GitCredentials[username=me, password=***, sshKey=none]", GitCredentials.Password("me", "s3cret").ToString());
        Assert.Equal("GitCredentials[username=, password=none, sshKey=***]", GitCredentials.SshKey("pub", "priv", "phrase").ToString());
        Assert.Throws<ArgumentNullException>(() => new GitCommitDetails("message", null!, "a@example.com"));
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>GitService against a real TM1: a port of the Java client's GitServiceTm1Test.</summary>
public class GitServiceLiveTests
{
    private static readonly GitCommitDetails Commit = new("Add a dimension", "tm1-client", "tm1-client@example.com");

    [Tm1GitFact]
    public async Task PushesTheModelAndPullsItBack()
    {
        await using var tm1 = Live.Client();
        var dimension = "git" + Guid.NewGuid().ToString("N")[..8];
        var copy = dimension + "copy";
        await using var remote = GitDaemon.Start();
        var none = GitCredentials.None();
        await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.Git.StatusAsync(none)); // not initialized yet
        var tied = await tm1.Git.InitAsync(remote.Url, "dev", none, true, null);
        try
        {
            Assert.Contains("main", tied.Remote!.Branches!);
            await Fails(() => tm1.Git.PushAsync("main", null, Commit, none, true), "Cannot create bare commit"); // nothing has changed

            await tm1.Dimensions.CreateAsync(
                DimensionPatch
                    .Builder()
                    .Name(dimension)
                    .Hierarchies([HierarchyPatch.Builder().Name(dimension).Elements([ElementPatch.Builder().Name("North").Build()]).Build()])
                    .Build()
            );
            var push = await tm1.Git.PushAsync("main", null, Commit, none, true);
            Assert.Contains($"/dimensions/{dimension}.json", push.SourceFiles!);
            Assert.IsType<GitPushPlan>(await tm1.Git.PlanAsync(push.Id!));
            await tm1.Git.ExecuteAsync(push);
            Assert.Null(await tm1.Git.PlanAsync(push.Id!)); // executing a plan uses it up
            if (remote.Local)
            {
                Assert.Contains($"dimensions/{dimension}.json", remote.FilesOnMain());
            }
            Assert.Equal("Add a dimension", (await tm1.Git.StatusAsync(none)).DeployedCommit!.Summary);

            await DeleteDimension(tm1, dimension);
            var skip = await tm1.Git.PullAsync("main", none, null, true);
            // A pull compares commits, not the model: the local delete stands
            Assert.Equal([$"Skip Dimensions('{dimension}')"], skip.Operations!);
            await tm1.Git.ExecuteAsync(skip);

            if (remote.Local)
            {
                remote.CommitCopyOf(dimension, copy);
                var stale = await tm1.Git.PullAsync("main", none, null, true);
                // The first pull after the remote moves plans the commit TM1 had
                Assert.Equal("Add a dimension", stale.Commit!.Summary);
                var pull = await tm1.Git.PullAsync("main", none, null, true);
                Assert.Equal("Copy " + dimension, pull.Commit!.Summary);
                Assert.Contains($"Create Dimensions('{copy}')", pull.Operations!);
                await tm1.Git.ExecuteAsync(pull);
                Assert.Equal(copy, (await tm1.Dimensions.GetAsync(copy)).Name);
            }

            // Refused while initialized
            await Fails(() => tm1.Git.DeployAsync(remote.Url, "main", "prod", none, true, null), "Cannot re-deploy");
        }
        finally
        {
            await tm1.Git.UninitAsync(true);
            await DeleteDimension(tm1, dimension);
            await DeleteDimension(tm1, copy);
        }

        if (!remote.Local)
        {
            return; // the deploy below needs the remote's history as the local daemon made it
        }
        await tm1.Git.DeployAsync(remote.Url, "main", "dev", none, true, null);
        try
        {
            Assert.Equal("Copy " + dimension, (await tm1.Git.StatusAsync(none)).DeployedCommit!.Summary);
            // A deploy brings back the whole branch, the deleted dimension too
            Assert.Equal(dimension, (await tm1.Dimensions.GetAsync(dimension)).Name);
        }
        finally
        {
            await tm1.Git.UninitAsync(true);
            await DeleteDimension(tm1, dimension);
            await DeleteDimension(tm1, copy);
        }
    }

    [Fact]
    public void KeepsPasswordsOutOfToString() =>
        Assert.Equal("GitCredentials[username=me, password=***, sshKey=none]", GitCredentials.Password("me", "s3cret").ToString());

    private static async Task Fails(Func<Task> call, string message)
    {
        var e = await Assert.ThrowsAnyAsync<Tm1Exception>(call);
        Assert.Contains(message, e.Message, StringComparison.Ordinal);
    }

    private static async Task DeleteDimension(Tm1Client tm1, string name)
    {
        try
        {
            await tm1.Dimensions.DeleteAsync(name);
        }
        catch (Tm1NotFoundException)
        {
            // already gone
        }
    }
}

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>A live test that needs a Git remote TM1 can reach: <c>TM1_GIT_URL</c> (a <c>git://</c> or
/// <c>https://</c> repository whose <c>main</c> has a commit), or else, when the tests start TM1 in
/// Docker themselves, a <c>git daemon</c> container in TM1's network, as the Java test's
/// <c>GitDaemon</c>.</summary>
public sealed class Tm1GitFactAttribute : FactAttribute
{
    public Tm1GitFactAttribute()
    {
        if (!Live.Configured)
        {
            Skip = Live.Reason;
        }
        else if (GitDaemon.Given is null && !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("TM1_URL")))
        {
            Skip = "No Git remote configured (TM1_GIT_URL) for the TM1 at TM1_URL";
        }
    }
}

/// <summary>GitService against a real TM1: a port of the Java client's GitServiceTm1Test.</summary>
public class GitLiveTests
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

            // Through the session, so this doesn't depend on DimensionService
            await tm1.Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Dimensions"), DimensionPatch.Builder()
                .Name(dimension)
                .Hierarchies([HierarchyPatch.Builder().Name(dimension).Elements([ElementPatch.Builder().Name("North").Build()]).Build()])
                .Build()));
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
                Assert.Equal(copy, (await tm1.Session.ExecuteAsync(Tm1Request.Get<Dimension>(ODataPath.Of("Dimensions").Key(copy)))).Name);
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
            Assert.Equal(dimension, (await tm1.Session.ExecuteAsync(Tm1Request.Get<Dimension>(ODataPath.Of("Dimensions").Key(dimension)))).Name);
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
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Dimensions").Key(name)));
        }
        catch (Tm1NotFoundException)
        {
            // already gone
        }
    }
}

/// <summary>The Git remote for the live test: <c>TM1_GIT_URL</c> if it's set, or else a
/// <c>git daemon</c> in an Alpine container sharing the network of the TM1 container the tests
/// started, serving a bare repository whose <c>main</c> has a seed commit, as the Java test's
/// <c>GitDaemon</c>.</summary>
public sealed class GitDaemon : IAsyncDisposable
{
    /// <summary>Where TM1 finds the daemon's repository.</summary>
    public const string DaemonUrl = "git://localhost/model.git";

    private readonly string? _id;

    private GitDaemon(string url, string? id)
    {
        Url = url;
        _id = id;
    }

    public static string? Given => Environment.GetEnvironmentVariable("TM1_GIT_URL") is { } u && !string.IsNullOrWhiteSpace(u) ? u.Trim() : null;

    public string Url { get; }

    /// <summary>Whether it's the daemon here, which the test can change behind TM1's back.</summary>
    public bool Local => _id is not null;

    public static GitDaemon Start()
    {
        if (Given is { } url)
        {
            return new GitDaemon(url, null);
        }
        var port = new Uri(Live.Url).Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
        var tm1 = Docker("ps", "-q", "--filter", "label=net.ajmyers.tm1.tests=dotnet", "--filter", "publish=8010")
            .Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault(id => Docker("port", id, "8010").Contains(":" + port, StringComparison.Ordinal))
            ?? throw new InvalidOperationException("No TM1 container found for " + Live.Url + "; set TM1_GIT_URL");
        var script = string.Join(" && ",
            "apk add -q --no-cache git git-daemon",
            "git init -q --bare -b main /srv/model.git", "git clone -q /srv/model.git /tmp/seed", "cd /tmp/seed",
            "echo 'A TM1 model' > README.md", "git add README.md",
            "git -c user.name=seed -c user.email=seed@example.com commit -qm Seed", "git push -q origin main",
            "exec git daemon --verbose --reuseaddr --base-path=/srv --export-all --enable=receive-pack /srv");
        var id = Docker("run", "-d", "--network", "container:" + tm1, "alpine:3.21", "sh", "-c", script).Trim();
        var daemon = new GitDaemon(DaemonUrl, id);
        var deadline = DateTime.UtcNow + TimeSpan.FromMinutes(2);
        while (!Docker("logs", id).Contains("Ready to rumble", StringComparison.Ordinal))
        {
            if (DateTime.UtcNow > deadline)
            {
                daemon.Stop();
                throw new InvalidOperationException("The git daemon didn't start");
            }
            System.Threading.Thread.Sleep(500);
        }
        return daemon;
    }

    /// <summary>The files on <c>main</c>, as Git sees them, one per line.</summary>
    public string FilesOnMain() => Docker("exec", _id!, "git", "--git-dir=/srv/model.git", "ls-tree", "-r", "--name-only", "main");

    /// <summary>Commits, on the remote's <c>main</c>, a copy of a dimension TM1 pushed, under another
    /// name: a change made elsewhere for TM1 to pull.</summary>
    public void CommitCopyOf(string dimension, string copy) =>
        Docker("exec", _id!, "sh", "-c", string.Join(" && ", "cd /tmp/seed", "git pull -q origin main",
            $"mkdir -p dimensions/{copy}.hierarchies",
            $"sed 's/{dimension}/{copy}/g' dimensions/{dimension}.json > dimensions/{copy}.json",
            $"sed 's/{dimension}/{copy}/g' dimensions/{dimension}.hierarchies/{dimension}.json > dimensions/{copy}.hierarchies/{copy}.json",
            "git add dimensions",
            $"git -c user.name=elsewhere -c user.email=elsewhere@example.com commit -qm 'Copy {dimension}'",
            "git push -q origin main"));

    public ValueTask DisposeAsync()
    {
        Stop();
        return ValueTask.CompletedTask;
    }

    private void Stop()
    {
        if (_id is not null)
        {
            Docker("rm", "-f", _id);
        }
    }

    private static string Docker(params string[] args)
    {
        var info = new ProcessStartInfo("docker") { RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in args)
        {
            info.ArgumentList.Add(a);
        }
        using var process = System.Diagnostics.Process.Start(info)!;
        var output = process.StandardOutput.ReadToEndAsync();
        var error = process.StandardError.ReadToEndAsync();
        process.WaitForExit();
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException($"docker {string.Join(" ", args.Take(2))} failed: {error.Result}");
        }
        return output.Result + error.Result;
    }
}

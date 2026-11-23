using System;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Testcontainers;

namespace Skipcheck.Tm1.Client.Tests.Live;

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
        if (!string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("TM1_URL")))
        {
            throw new InvalidOperationException("The git daemon runs beside the TM1 container, and TM1_URL names a server that isn't one; set TM1_GIT_URL");
        }
        // The container the live tests share, asked for by name: it's already running by now
        var tm1 = Tm1ServerContainer.SharedAsync().GetAwaiter().GetResult().ContainerId;
        var script = string.Join(
            " && ",
            "apk add -q --no-cache git git-daemon",
            "git init -q --bare -b main /srv/model.git",
            "git clone -q /srv/model.git /tmp/seed",
            "cd /tmp/seed",
            "echo 'A TM1 model' > README.md",
            "git add README.md",
            "git -c user.name=seed -c user.email=seed@example.com commit -qm Seed",
            "git push -q origin main",
            "exec git daemon --verbose --reuseaddr --base-path=/srv --export-all --enable=receive-pack /srv"
        );
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
        Docker(
            "exec",
            _id!,
            "sh",
            "-c",
            string.Join(
                " && ",
                "cd /tmp/seed",
                "git pull -q origin main",
                $"mkdir -p dimensions/{copy}.hierarchies",
                $"sed 's/{dimension}/{copy}/g' dimensions/{dimension}.json > dimensions/{copy}.json",
                $"sed 's/{dimension}/{copy}/g' dimensions/{dimension}.hierarchies/{dimension}.json > dimensions/{copy}.hierarchies/{copy}.json",
                "git add dimensions",
                $"git -c user.name=elsewhere -c user.email=elsewhere@example.com commit -qm 'Copy {dimension}'",
                "git push -q origin main"
            )
        );

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

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading;
using AjMyers.Tm1.Client.Auth;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>A test against a real TM1, as the Java client's <c>*Tm1Test</c> classes and the Python
/// client's tests/live. The server is either started in Docker for the run, from the same TM1
/// sources the Java tests use (<c>TM1_PRIVATE_IMAGE</c>, <c>TM1_HOME_DIR</c>,
/// <c>TM1_INSTALL_VOLUME</c>, <c>TM1_BUNDLE_DIR</c>, <c>TM1_BUNDLE_URL</c>: see
/// <see cref="Tm1Container"/>), logging in as <c>admin</c> with no password; or one already running,
/// <c>TM1_URL</c>, with <c>TM1_USER</c> and <c>TM1_PASSWORD</c> (default <c>admin</c> and <c>apple</c>),
/// <c>TM1_NAMESPACE</c> for CAM and <c>TM1_VERIFY=false</c> for a self-signed certificate. Without
/// either (or without Docker), it's skipped; a source that's set but wrong fails. Tests make objects
/// named <c>tm1net_test_…</c> and delete them afterwards.</summary>
public sealed class Tm1FactAttribute : FactAttribute
{
    public Tm1FactAttribute()
    {
        if (!Live.Configured)
        {
            Skip = Live.Reason;
        }
    }
}

public static class Live
{
    private static readonly string? Running = Environment.GetEnvironmentVariable("TM1_URL");
    private static readonly Tm1Container.Source? DiscoveredSource = Tm1Container.Discover(Environment.GetEnvironmentVariable);
    private static readonly Lazy<Tm1Container> Container = new(() => Tm1Container.Start(DiscoveredSource!), LazyThreadSafetyMode.ExecutionAndPublication);

    /// <summary>Whether there's a TM1 to test against.</summary>
    public static bool Configured { get; } = !string.IsNullOrWhiteSpace(Running) || (DiscoveredSource is not null && Tm1Container.DockerAvailable());

    public static string Reason => DiscoveredSource is null ? Tm1Container.NoSourceMessage : "Docker isn't available, so there's no TM1 to test against";

    /// <summary>The REST root and login of the TM1 to test against, starting its container the first time.</summary>
    public static (string Url, string User, string Password) Target()
    {
        if (!string.IsNullOrWhiteSpace(Running))
        {
            return (Running, Environment.GetEnvironmentVariable("TM1_USER") ?? "admin", Environment.GetEnvironmentVariable("TM1_PASSWORD") ?? "apple");
        }
        var c = Container.Value;
        return (c.Url, Tm1Container.Username, Tm1Container.Password);
    }

    /// <summary>The REST root of the TM1 to test against.</summary>
    public static string Url => Target().Url;

    /// <summary>A new client of the TM1 to test against.</summary>
    public static Tm1Client Client()
    {
        var (url, user, password) = Target();
        var ns = string.IsNullOrWhiteSpace(Running) ? null : Environment.GetEnvironmentVariable("TM1_NAMESPACE");
        var builder = Tm1Client.Builder().BaseUrl(url)
            .Authenticator(ns is null ? Tm1Authenticator.Basic(user, password) : Tm1Authenticator.CamNamespace(user, password, ns));
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            builder.HttpHandler(new SocketsHttpHandler
            {
                UseCookies = false,
                SslOptions = { RemoteCertificateValidationCallback = (_, _, _, _) => true },
            });
        }
        return builder.Build();
    }

    /// <summary>A name for a test's objects.</summary>
    public static string Name() => "tm1net_test_" + Guid.NewGuid().ToString("N")[..8];
}

/// <summary>A TM1 server in Docker, as the Java client's tm1-testcontainers starts one: the same
/// sources from the same settings, in the same order, and the same <c>tm1-server</c> image, mounts
/// and cache volume (so a bundle unpacked for the Java or Python tests is reused). The image has no
/// IBM software in it: one of the sources supplies TM1. <c>TM1_SERVER_IMAGE</c> replaces the image
/// (default <c>ghcr.io/ajmyers/tm1-server:edge</c>); <c>TM1_KEEP_CONTAINER=true</c> leaves it running
/// after the tests.</summary>
public sealed class Tm1Container
{
    public const string DefaultImage = "ghcr.io/ajmyers/tm1-server:edge";
    public const int RestPort = 8010;
    public const string CacheVolume = "tm1-testcontainers-cache";
    public const string Username = "admin";
    public const string Password = "";
    private const string InstallPath = "/opt/ibm/cognos/tm1_64";
    private static readonly TimeSpan StartupTimeout = TimeSpan.FromMinutes(5);

    public const string NoSourceMessage = "No TM1 source configured. Set one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR or TM1_BUNDLE_URL to run tests against TM1 (or TM1_URL for a server that's already running).";

    private Tm1Container(string id, string url)
    {
        Id = id;
        Url = url;
    }

    public string Id { get; }

    public string Url { get; }

    /// <summary>Where TM1 comes from: Java's <c>Tm1Source</c> records.</summary>
    public sealed record Source(string Kind, string Value, string? Extra = null)
    {
        /// <summary>For logs: never a URL's query string (a signed URL's secret).</summary>
        public string Describe() => Kind == "bundle-url" ? "bundle " + Value.Split('?')[0] : Kind + " " + Value;
    }

    /// <summary>The first source set, in Java's order (<c>Tm1Source.discover</c>); null if none is.</summary>
    public static Source? Discover(Func<string, string?> lookup)
    {
        string? Get(string name) => lookup(name) is { } v && !string.IsNullOrWhiteSpace(v) ? v.Trim() : null;
        if (Get("TM1_PRIVATE_IMAGE") is { } image)
        {
            return new Source("private-image", image);
        }
        if (Get("TM1_HOME_DIR") is { } home)
        {
            return Directory.Exists(home) ? new Source("install-dir", Path.GetFullPath(home)) : throw new ArgumentException("TM1_HOME_DIR isn't a folder: " + home);
        }
        if (Get("TM1_INSTALL_VOLUME") is { } volume)
        {
            return new Source("install-volume", volume);
        }
        if (Get("TM1_BUNDLE_DIR") is { } bundles)
        {
            return Directory.Exists(bundles) ? new Source("bundle", Path.GetFullPath(bundles), Get("TM1_BUNDLE")) : throw new ArgumentException("TM1_BUNDLE_DIR isn't a folder: " + bundles);
        }
        if (Get("TM1_BUNDLE_URL") is { } url)
        {
            return new Source("bundle-url", url, Get("TM1_BUNDLE_SHA256"));
        }
        return null;
    }

    /// <summary>The <c>docker run</c> arguments for a source, as Java's <c>Tm1ServerContainer.mountSource</c>.</summary>
    public static List<string> RunArguments(Source source, Func<string, string?> lookup)
    {
        var image = source.Kind == "private-image" ? source.Value : lookup("TM1_SERVER_IMAGE") is { } o && !string.IsNullOrWhiteSpace(o) ? o.Trim() : DefaultImage;
        var args = new List<string> { "run", "-d", "--platform", "linux/amd64", "-p", $"127.0.0.1::{RestPort}", "-e", "TM1_SEED=empty" };
        switch (source.Kind)
        {
            case "install-dir":
                args.AddRange(["-v", $"{source.Value}:{InstallPath}:ro"]);
                break;
            case "install-volume":
                args.AddRange(["-v", $"{source.Value}:{InstallPath}"]);
                break;
            case "bundle":
                args.AddRange(["-v", $"{source.Value}:/ibm:ro", "-v", $"{CacheVolume}:/tm1/cache"]);
                if (source.Extra is not null)
                {
                    args.AddRange(["-e", "TM1_BUNDLE=" + source.Extra]);
                }
                break;
            case "bundle-url":
                args.AddRange(["-e", "TM1_BUNDLE_URL=" + source.Value, "-v", $"{CacheVolume}:/tm1/cache"]);
                if (source.Extra is not null)
                {
                    args.AddRange(["-e", "TM1_BUNDLE_SHA256=" + source.Extra]);
                }
                break;
        }
        args.AddRange(["--label", "net.ajmyers.tm1.tests=dotnet", image]);
        return args;
    }

    public static bool DockerAvailable()
    {
        try
        {
            return Docker(["info"], TimeSpan.FromSeconds(30)).Exit == 0;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return false;
        }
    }

    /// <summary>Starts TM1 from <paramref name="source"/> and waits until its REST API answers.</summary>
    public static Tm1Container Start(Source source)
    {
        var run = Docker(RunArguments(source, Environment.GetEnvironmentVariable), TimeSpan.FromMinutes(30));
        if (run.Exit != 0)
        {
            throw new InvalidOperationException($"Couldn't start TM1 ({source.Describe()}): {run.Error}");
        }
        var id = run.Output.Trim();
        var port = Docker(["port", id, RestPort.ToString(System.Globalization.CultureInfo.InvariantCulture)], TimeSpan.FromSeconds(30)).Output.Trim().Split('\n')[0];
        var container = new Tm1Container(id, $"http://127.0.0.1:{port[(port.LastIndexOf(':') + 1)..]}/api/v1/");
        AppDomain.CurrentDomain.ProcessExit += (_, _) => container.Stop();
        container.Wait();
        return container;
    }

    private void Wait()
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        var deadline = DateTime.UtcNow + StartupTimeout;
        while (DateTime.UtcNow < deadline)
        {
            if (!Docker(["inspect", "-f", "{{.State.Running}}", Id], TimeSpan.FromSeconds(30)).Output.Trim().Equals("true", StringComparison.Ordinal))
            {
                throw new InvalidOperationException("TM1's container stopped while starting:\n" + Logs());
            }
            try
            {
                // TM1 serves $metadata without a login, so a 200 means the REST API is really up
                if (http.GetAsync(Url + "$metadata").GetAwaiter().GetResult().IsSuccessStatusCode)
                {
                    return;
                }
            }
            catch (Exception e) when (e is HttpRequestException or System.Threading.Tasks.TaskCanceledException)
            {
                // Not up yet
            }
            Thread.Sleep(2000);
        }
        throw new InvalidOperationException($"TM1 wasn't up within {StartupTimeout}:\n" + Logs());
    }

    public string Logs() => Docker(["logs", "--tail", "60", Id], TimeSpan.FromSeconds(30)) is var l ? l.Output + l.Error : "";

    public void Stop()
    {
        if (!string.Equals(Environment.GetEnvironmentVariable("TM1_KEEP_CONTAINER"), "true", StringComparison.OrdinalIgnoreCase))
        {
            Docker(["rm", "-f", Id], TimeSpan.FromSeconds(60));
        }
    }

    private static (int Exit, string Output, string Error) Docker(IEnumerable<string> args, TimeSpan timeout)
    {
        var info = new ProcessStartInfo("docker") { RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in args)
        {
            info.ArgumentList.Add(a);
        }
        using var process = Process.Start(info)!;
        var output = process.StandardOutput.ReadToEndAsync();
        var error = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(timeout))
        {
            process.Kill();
            return (-1, "", "timed out");
        }
        return (process.ExitCode, output.Result, error.Result);
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Net.Http;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>A test against a real TM1, as the Java client's <c>*Tm1Test</c> classes and the Python
/// client's tests/live. The server is either the one Skipcheck.Tm1.Testcontainers starts in Docker for
/// the run (<see cref="Tm1ServerContainer.SharedAsync"/>, from the same TM1 sources the Java tests
/// use: <c>TM1_PRIVATE_IMAGE</c>, <c>TM1_HOME_DIR</c>, <c>TM1_INSTALL_VOLUME</c>,
/// <c>TM1_BUNDLE_DIR</c>, <c>TM1_BUNDLE_URL</c>), logging in as <c>admin</c> with no password; or
/// one already running, <c>TM1_URL</c>, with <c>TM1_USER</c> and <c>TM1_PASSWORD</c> (default
/// <c>admin</c> and <c>apple</c>), <c>TM1_NAMESPACE</c> for CAM and <c>TM1_VERIFY=false</c> for a
/// self-signed certificate. Without either (or without Docker), it's skipped; a source that's set
/// but wrong fails. Tests make objects named <c>tm1net_test_…</c> and delete them afterwards.</summary>
public sealed class Tm1FactAttribute : FactAttribute
{
    private bool _v11Only;

    public Tm1FactAttribute()
    {
        if (!Live.Configured)
        {
            Skip = Live.Reason;
        }
    }

    /// <summary>When true, skipped against a TM1 v12 engine container.</summary>
    public bool V11Only
    {
        get => _v11Only;
        set
        {
            _v11Only = value;
            if (value && Live.IsEngine && Skip is null)
            {
                Skip = "TM1 11 only";
            }
        }
    }
}

/// <summary>A <see cref="Tm1FactAttribute"/> with a case per row of data.</summary>
public sealed class Tm1TheoryAttribute : TheoryAttribute
{
    public Tm1TheoryAttribute()
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
    private static readonly string? ServerSkip = string.IsNullOrWhiteSpace(Running) ? RequiresTm1FactAttribute.Tm1SkipReason() : null;
    private static readonly string? EngineSkip =
        string.IsNullOrWhiteSpace(Running) && ServerSkip is not null ? RequiresTm1EngineFactAttribute.EngineSkipReason() : null;

    /// <summary>Whether the live tests are running against a TM1 v12 engine container.</summary>
    public static bool IsEngine { get; } = string.IsNullOrWhiteSpace(Running) && ServerSkip is not null && EngineSkip is null;

    /// <summary>Whether the TM1 the tests run against can write to its own install, as Java's
    /// ServerAssumptions.Install names it: <c>readOnly</c> for an installed folder (TM1_HOME_DIR or
    /// TM1_ENGINE_HOME_DIR, which Skipcheck.Tm1.Testcontainers mounts read-only), <c>writable</c> for
    /// any other source, and null for a server the suite didn't start (TM1_URL).</summary>
    public static string? Install { get; } = InstallOf();

    private static string? InstallOf()
    {
        if (!string.IsNullOrWhiteSpace(Running))
        {
            return null;
        }
        try
        {
            if (Tm1Source.Discover() is { } server)
            {
                return server is Tm1Source.InstallDir ? "readOnly" : "writable";
            }
            return Tm1EngineSource.Discover() switch
            {
                null => null,
                Tm1EngineSource.InstallDir => "readOnly",
                _ => "writable",
            };
        }
        catch (Exception e) when (e is ArgumentException or FormatException)
        {
            return null; // a source set but wrong (a folder that isn't there, a URL that isn't one), which starting the container reports
        }
    }

    /// <summary>Whether there's a TM1 to test against.</summary>
    public static bool Configured { get; } = !string.IsNullOrWhiteSpace(Running) || ServerSkip is null || EngineSkip is null;

    /// <summary>Why the live tests are skipped: tm1-testcontainers' reason, or TM1_URL unset.</summary>
    public static string Reason =>
        (
            ServerSkip == RequiresTm1FactAttribute.NoDockerMessage
                ? ServerSkip
                : RequiresTm1FactAttribute.NoSourceMessage + " " + RequiresTm1EngineFactAttribute.NoSourceMessage
        ) + " Or set TM1_URL for a server that's already running.";

    /// <summary>The REST root and login of the TM1 to test against, starting the shared container the first time.</summary>
    public static (string Url, string User, string Password) Target()
    {
        if (!string.IsNullOrWhiteSpace(Running))
        {
            return (Running, Environment.GetEnvironmentVariable("TM1_USER") ?? "admin", Environment.GetEnvironmentVariable("TM1_PASSWORD") ?? "apple");
        }
        if (IsEngine)
        {
            var db = Tm1EngineContainer.SharedAsync().GetAwaiter().GetResult().Database(Tm1EngineContainer.DefaultDatabase);
            return (db.RestUrl, db.Login().User, "");
        }
        var tm1 = Tm1ServerContainer.SharedAsync().GetAwaiter().GetResult();
        return (tm1.RestUrl, tm1.Username, tm1.Password);
    }

    /// <summary>The REST root of the TM1 to test against.</summary>
    public static string Url => Target().Url;

    /// <summary>A new client of the TM1 to test against.</summary>
    public static Tm1Client Client() => Builder().Build();

    /// <summary>Settings for a client of the TM1 to test against, logging in as <see cref="Target"/>
    /// (or as <paramref name="user"/>), with the TM1_NAMESPACE and TM1_VERIFY of a running server.</summary>
    public static Tm1ClientBuilder Builder(string? user = null, string? password = null)
    {
        if (string.IsNullOrWhiteSpace(Running))
        {
            if (IsEngine)
            {
                var db = Tm1EngineContainer.SharedAsync().GetAwaiter().GetResult().Database(Tm1EngineContainer.DefaultDatabase);
                var login = user is null ? db.Login() : db.Login(user);
                return Tm1Client
                    .Builder()
                    .BaseUrl(db.RestUrl)
                    .Dialect(Tm1Dialect.V12(db.Instance, db.Name))
                    .Authenticator(Tm1Authenticator.Application(login.ClientId, login.ClientSecret, login.User));
            }
            var tm1 = Tm1ServerContainer.SharedAsync().GetAwaiter().GetResult();
            var own =
                tm1.CamNamespace is { } cam && user is null
                    ? Tm1Authenticator.CamNamespace(tm1.Username, tm1.Password, cam.Id())
                    : Tm1Authenticator.Basic(user ?? tm1.Username, user is null ? tm1.Password : password);
            return Tm1Client.Builder().BaseUrl(tm1.RestUrl).Authenticator(own);
        }
        var (url, liveUser, livePassword) = Target();
        var ns = Environment.GetEnvironmentVariable("TM1_NAMESPACE");
        var builder = Tm1Client
            .Builder()
            .BaseUrl(url)
            .Authenticator(
                user is not null ? Tm1Authenticator.Basic(user, password)
                : string.IsNullOrWhiteSpace(ns) ? Tm1Authenticator.Basic(liveUser, livePassword)
                : Tm1Authenticator.CamNamespace(liveUser, livePassword, ns)
            );
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            builder.HttpHandler(new SocketsHttpHandler { UseCookies = false, SslOptions = { RemoteCertificateValidationCallback = (_, _, _, _) => true } });
        }
        return builder;
    }

    /// <summary>An authenticator for <paramref name="user"/> against the TM1 under test:
    /// application credentials on a v12 engine, or Basic credentials on TM1 11.</summary>
    public static Tm1Authenticator Authenticator(string user, string password)
    {
        if (IsEngine)
        {
            var db = Tm1EngineContainer.SharedAsync().GetAwaiter().GetResult().Database(Tm1EngineContainer.DefaultDatabase);
            var login = db.Login(user);
            return Tm1Authenticator.Application(login.ClientId, login.ClientSecret, login.User);
        }
        return Tm1Authenticator.Basic(user, password);
    }

    /// <summary>A name for a test's objects.</summary>
    public static string Name() => "tm1net_test_" + Guid.NewGuid().ToString("N")[..8];
}

using System;
using System.Net.Http;
using AjMyers.Tm1.Client.Auth;
using AjMyers.Tm1.Testcontainers;
using AjMyers.Tm1.Testcontainers.Xunit;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>A test against a real TM1, as the Java client's <c>*Tm1Test</c> classes and the Python
/// client's tests/live. The server is either the one AjMyers.Tm1.Testcontainers starts in Docker for
/// the run (<see cref="Tm1ServerContainer.SharedAsync"/>, from the same TM1 sources the Java tests
/// use: <c>TM1_PRIVATE_IMAGE</c>, <c>TM1_HOME_DIR</c>, <c>TM1_INSTALL_VOLUME</c>,
/// <c>TM1_BUNDLE_DIR</c>, <c>TM1_BUNDLE_URL</c>), logging in as <c>admin</c> with no password; or
/// one already running, <c>TM1_URL</c>, with <c>TM1_USER</c> and <c>TM1_PASSWORD</c> (default
/// <c>admin</c> and <c>apple</c>), <c>TM1_NAMESPACE</c> for CAM and <c>TM1_VERIFY=false</c> for a
/// self-signed certificate. Without either (or without Docker), it's skipped; a source that's set
/// but wrong fails. Tests make objects named <c>tm1net_test_…</c> and delete them afterwards.</summary>
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
    private static readonly string? ContainerSkip = string.IsNullOrWhiteSpace(Running) ? RequiresTm1FactAttribute.Tm1SkipReason() : null;

    /// <summary>Whether there's a TM1 to test against.</summary>
    public static bool Configured { get; } = !string.IsNullOrWhiteSpace(Running) || ContainerSkip is null;

    /// <summary>Why the live tests are skipped: tm1-testcontainers' reason, or TM1_URL unset.</summary>
    public static string Reason => (ContainerSkip ?? RequiresTm1FactAttribute.NoSourceMessage) + " Or set TM1_URL for a server that's already running.";

    /// <summary>The REST root and login of the TM1 to test against, starting the shared container the first time.</summary>
    public static (string Url, string User, string Password) Target()
    {
        if (!string.IsNullOrWhiteSpace(Running))
        {
            return (Running, Environment.GetEnvironmentVariable("TM1_USER") ?? "admin", Environment.GetEnvironmentVariable("TM1_PASSWORD") ?? "apple");
        }
        var tm1 = Tm1ServerContainer.SharedAsync().GetAwaiter().GetResult();
        return (tm1.RestUrl, tm1.Username, tm1.Password);
    }

    /// <summary>The REST root of the TM1 to test against.</summary>
    public static string Url => Target().Url;

    /// <summary>A new client of the TM1 to test against.</summary>
    public static Tm1Client Client()
    {
        if (string.IsNullOrWhiteSpace(Running))
        {
            return Tm1ServerFixture.ClientOf(Tm1ServerContainer.SharedAsync().GetAwaiter().GetResult());
        }
        var (url, user, password) = Target();
        var ns = Environment.GetEnvironmentVariable("TM1_NAMESPACE");
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

using System;
using AjMyers.Tm1.Client.Auth;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>A test against a real TM1, skipped unless one is configured, as the Java client's
/// <c>*Tm1Test</c> classes and the Python client's tests/live:
/// <list type="bullet">
/// <item><c>TM1_URL</c>: the REST API's root, such as <c>https://localhost:8010/api/v1</c></item>
/// <item><c>TM1_USER</c>, <c>TM1_PASSWORD</c> (default <c>admin</c> and <c>apple</c>), <c>TM1_NAMESPACE</c> for CAM</item>
/// <item><c>TM1_VERIFY=false</c> to accept a self-signed certificate (the docker/tm1-server image's)</item>
/// </list>
/// Tests make objects named <c>tm1net_test_…</c> and delete them afterwards.</summary>
public sealed class Tm1FactAttribute : FactAttribute
{
    public Tm1FactAttribute()
    {
        if (Live.Url is null)
        {
            Skip = "no TM1 configured (TM1_URL)";
        }
    }
}

public static class Live
{
    public static string? Url { get; } = Environment.GetEnvironmentVariable("TM1_URL");

    /// <summary>A new client of the configured TM1.</summary>
    public static Tm1Client Client()
    {
        var user = Environment.GetEnvironmentVariable("TM1_USER") ?? "admin";
        var password = Environment.GetEnvironmentVariable("TM1_PASSWORD") ?? "apple";
        var ns = Environment.GetEnvironmentVariable("TM1_NAMESPACE");
        var builder = Tm1Client.Builder().BaseUrl(Url ?? throw new InvalidOperationException("TM1_URL"))
            .Authenticator(ns is null ? Tm1Authenticator.Basic(user, password) : Tm1Authenticator.CamNamespace(user, password, ns));
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            builder.HttpHandler(new System.Net.Http.SocketsHttpHandler
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

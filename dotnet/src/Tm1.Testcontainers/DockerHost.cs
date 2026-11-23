// SPDX-License-Identifier: Apache-2.0

using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Docker.DotNet;
using DotNet.Testcontainers.Configurations;
using DotNet.Testcontainers.Containers;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>The Docker daemon Testcontainers uses, for what its builders don't cover (inspecting a
/// container's networks, volumes, PAW's compose containers), as Java's <c>DockerClientFactory</c>.</summary>
internal static class DockerHost
{
    private static readonly Lazy<bool> IsAvailable = new(Probe, LazyThreadSafetyMode.ExecutionAndPublication);

    /// <summary>Whether a Docker daemon answers.</summary>
    public static bool Available() => IsAvailable.Value;

    public static IDockerClient Client() =>
        (TestcontainersSettings.OS.DockerEndpointAuthConfig ?? throw new InvalidOperationException("Docker isn't available"))
            .GetDockerClientBuilder(ResourceReaper.DefaultSessionId)
            .Build();

    /// <summary>The daemon's socket, as a container that drives Docker mounts it.</summary>
    public static string SocketPath()
    {
        var endpoint = TestcontainersSettings.OS.DockerEndpointAuthConfig?.Endpoint;
        return endpoint is { Scheme: "unix" } ? endpoint.AbsolutePath : "/var/run/docker.sock";
    }

    /// <summary>Whether reuse is switched on, as Testcontainers for Java reads it: <c>testcontainers.reuse.enable=true</c>
    /// in <c>~/.testcontainers.properties</c>, or <c>TESTCONTAINERS_REUSE_ENABLE=true</c>.
    /// Otherwise a "reused" container is an ordinary one, removed after the run.</summary>
    public static bool ReuseEnabled()
    {
        if (string.Equals(Environment.GetEnvironmentVariable("TESTCONTAINERS_REUSE_ENABLE"), "true", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        var file = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".testcontainers.properties");
        if (!File.Exists(file))
        {
            return false;
        }
        foreach (var line in File.ReadLines(file))
        {
            var parts = line.Split('=', 2);
            if (parts.Length == 2 && parts[0].Trim() == "testcontainers.reuse.enable")
            {
                return string.Equals(parts[1].Trim(), "true", StringComparison.OrdinalIgnoreCase);
            }
        }
        return false;
    }

    private static bool Probe()
    {
        try
        {
            if (TestcontainersSettings.OS.DockerEndpointAuthConfig is null)
            {
                return false;
            }
            using var client = Client();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            Task.Run(() => client.System.PingAsync(timeout.Token)).GetAwaiter().GetResult();
            return true;
        }
#pragma warning disable CA1031 // any failure means there's no Docker to use
        catch (Exception)
#pragma warning restore CA1031
        {
            return false;
        }
    }
}

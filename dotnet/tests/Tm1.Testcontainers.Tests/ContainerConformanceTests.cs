// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>Java's ContainerConformanceTest: starts each scenario of
/// <c>testcontainers-conformance/scenarios</c>, a <c>pa-environment.yaml</c>, and writes what Docker says
/// it got (<c>docker inspect</c> of each container and of the network) to
/// <c>build/container-conformance/dotnet/&lt;scenario&gt;.json</c>. <c>scripts/compare-containers.py</c> then
/// requires every language to have got the same containers (<c>./gradlew containerConformance</c>).</summary>
[Collection(OwnContainers.Name)]
public class ContainerConformanceTests
{
    [RequiresTm1Fact]
    public Task OneServer() => Record("one-server");

    [RequiresTm1WebFact]
    public Task ServersAndTm1Web() => Record("servers-and-web");

    [RequiresCognosFact]
    public Task ServersInCamSecurity() => Record("cam-security");

    private static async Task Record(string scenario)
    {
        var home = Path.Combine(Repo.Root, "testcontainers-conformance", "scenarios");
        await using var env = PlanningAnalyticsEnvironment.FromConfig(Path.Combine(home, scenario + ".yaml")).Build();
        await env.StartAsync();

        var containers = new List<(string Role, string Id)>();
        foreach (var (name, server) in env.Tm1Servers)
        {
            containers.Add(("tm1/" + name, server.ContainerId));
        }
        if (env.AdminServer is { } admin)
        {
            containers.Add(("adminServer", admin.ContainerId));
        }
        if (scenario == "servers-and-web")
        {
            containers.Add(("tm1Web", env.Tm1Web().ContainerId));
        }
        if (scenario == "cam-security")
        {
            containers.Add(("cognos", env.Cognos().ContainerId));
            containers.Add(("keycloak", env.Keycloak().ContainerId));
        }
        // Each inspection as Docker's CLI prints it, so every language writes the same text and none
        // reads it through its own Docker client's types
        var parts = new List<string>();
        foreach (var (role, id) in containers)
        {
            parts.Add("\"" + role + "\": " + await Docker("inspect", id));
        }
        var network = await Docker("network", "inspect", env.Network.Name);
        var log =
            "{\"scenario\": \""
            + scenario
            + "\", \"language\": \"dotnet\",\n\"network\": "
            + network
            + ",\n\"containers\": {\n"
            + string.Join(",\n", parts)
            + "}}\n";
        var file = Path.Combine(Repo.Root, "build", "container-conformance", "dotnet", scenario + ".json");
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        await File.WriteAllTextAsync(file, log);
        Assert.NotEmpty(containers);
    }

    private static async Task<string> Docker(params string[] arguments)
    {
        var start = new ProcessStartInfo("docker")
        {
            RedirectStandardOutput = true,
            StandardOutputEncoding = Encoding.UTF8,
            UseShellExecute = false,
        };
        foreach (var argument in arguments)
        {
            start.ArgumentList.Add(argument);
        }
        using var process = Process.Start(start) ?? throw new InvalidOperationException("docker didn't start");
        var output = await process.StandardOutput.ReadToEndAsync();
        await process.WaitForExitAsync();
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException("docker " + string.Join(' ', arguments) + " failed");
        }
        return output.Trim();
    }
}

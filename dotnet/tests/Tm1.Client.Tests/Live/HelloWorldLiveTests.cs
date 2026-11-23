// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Examples;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Runs the quick start's example (HelloWorld) against a real TM1, and records what it prints for the landing
/// page, which shows it under the code (<see cref="ExampleOutput"/>). Run it after changing the example, and commit the
/// recording in api-site/example-output with it. The example logs in as a new tm1-server does (admin, no password),
/// so this starts a server of its own with the empty database rather than use the shared one, which other tests fill,
/// and is skipped without Docker or a TM1 source (a TM1_URL alone won't do). The server has no cubes, so the example
/// prints the version alone. A port of the Java client's HelloWorldTm1Test.</summary>
public sealed class HelloWorldLiveTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(30);

    [RequiresTm1Fact]
    public async Task PrintsTheVersionAndTheCubes()
    {
        await using var server = new Tm1ServerContainer(Tm1Source.Discover()!);
        await server.StartAsync();
        using var output = new ExampleOutput("tm1-client", "HelloWorld");
        await using (var tm1 = Tm1Client.Builder().BaseUrl(server.RestUrl).BasicAuth(server.Username, server.Password).Build())
        {
            await output.RecordAsync("hello-world", () => HelloWorld.Hello(tm1).WaitAsync(Wait));
        }
        Assert.Equal(["Hello from TM1 " + server.Tm1Version], output.Output("hello-world"));
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Docker.DotNet.Models;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>Java's Tm1ServerContainerTest: against the shared server, skipped without Docker or a TM1 source.</summary>
public class Tm1ServerContainerTests(Tm1ServerFixture fixture) : IClassFixture<Tm1ServerFixture>
{
    [RequiresTm1Fact]
    public async Task StartsATm1ServerAndReportsItsVersion()
    {
        var tm1 = fixture.Tm1Server;
        Assert.StartsWith("http://", tm1.RestUrl, StringComparison.Ordinal);
        Assert.EndsWith("/api/v1/", tm1.RestUrl, StringComparison.Ordinal);
        Assert.True(tm1.Tm1Version.CompareTo(Tm1Version.Parse("11")) > 0);
        var (status, body) = await GetAsync(tm1, "Configuration/ProductVersion/$value");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(tm1.Tm1Version, Tm1Version.Parse(body));
    }

    [RequiresTm1Fact]
    public async Task StartsWithAnEmptyDatabase()
    {
        var (status, body) = await GetAsync(fixture.Tm1Server, "Cubes?$select=Name&$filter=not%20startswith(Name,'%7D')");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"value\":[]", body.Replace(" ", "", StringComparison.Ordinal), StringComparison.Ordinal);
    }

    [RequiresTm1Fact]
    public async Task SharesOneContainerAcrossTests()
    {
        Assert.Same(await Tm1ServerContainer.SharedAsync(), fixture.Tm1Server);
        Assert.True(fixture.Tm1Server.IsRunning);
    }

    [RequiresTm1Fact]
    public async Task RefusesToStartOnTheWrongVersion()
    {
        await using var tm1 = new Tm1ServerContainer(fixture.Tm1Server.Source).WithExpectedVersion(Tm1Version.Parse("99.0"));
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => tm1.StartAsync());
        Assert.Contains("Expected TM1 99.0 but the server from", e.Message, StringComparison.Ordinal);
    }

    [RequiresTm1Fact]
    public async Task FailsAtOnceWhenTheContainerExits()
    {
        // An install whose programs can't run, as when TM1 lacks a library: the container exits at
        // once, and StartAsync says so with its log, rather than waiting out the startup timeout
        var install = System.IO.Directory.CreateTempSubdirectory("tm1-install-").FullName;
        try
        {
            // executable, with an interpreter that doesn't exist: exec fails the same way whatever
            // the host's file permissions
            System.IO.Directory.CreateDirectory(System.IO.Path.Combine(install, "bin64"));
            foreach (var program in new[] { "tm1s.exe", "tm1admsrv.exe" })
            {
                var path = System.IO.Path.Combine(install, "bin64", program);
                await System.IO.File.WriteAllTextAsync(path, "#!/not/a/program\n");
                if (!OperatingSystem.IsWindows())
                {
                    System.IO.File.SetUnixFileMode(path, (System.IO.UnixFileMode)0b111_101_101);
                }
            }
            var started = System.Diagnostics.Stopwatch.StartNew();
            await using (var tm1 = new Tm1ServerContainer(new Tm1Source.InstallDir(install)))
            {
                var e = await Assert.ThrowsAnyAsync<Exception>(() => tm1.StartAsync());
                Assert.Contains("The container exited while starting, with code", e.ToString(), StringComparison.Ordinal);
                Assert.Contains("[tm1-entrypoint] Starting TM1 Admin Server", e.ToString(), StringComparison.Ordinal);
            }
            // Well inside the five-minute startup timeout, with room for pulling the image
            Assert.True(started.Elapsed < TimeSpan.FromMinutes(4), $"took {started.Elapsed}");
        }
        finally
        {
            System.IO.Directory.Delete(install, recursive: true);
        }
    }

    [RequiresTm1Fact]
    public async Task GivesAClientOfTheSharedServer()
    {
        await using var client = fixture.Client();
        Assert.NotNull(client);
    }

    internal static async Task<(HttpStatusCode Status, string Body)> GetAsync(Tm1ServerContainer tm1, string path)
    {
        using var http = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, tm1.RestUrl + path);
        request.Headers.TryAddWithoutValidation("Authorization", "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(tm1.Username + ":" + tm1.Password)));
        using var response = await http.SendAsync(request);
        return (response.StatusCode, await response.Content.ReadAsStringAsync());
    }
}

/// <summary>Java's Tm1WebContainerTest: skipped without a TM1 Web source.</summary>
public class Tm1WebContainerTests(Tm1WebFixture fixture) : IClassFixture<Tm1WebFixture>
{
    [RequiresTm1WebFact]
    public async Task ListsAndLogsOnToTheSharedTm1Server()
    {
        var (web, tm1) = (fixture.Tm1Web, fixture.Tm1Server);
        var server = await ServerNameAsync(tm1);
        using var session = await Tm1WebSession.OpenAsync(web);
        Assert.Contains(server, await session.ServersAsync(web.AdminHost));
        Assert.False(string.IsNullOrWhiteSpace(await session.LoginAsync(web.AdminHost, server, tm1.Username, tm1.Password)));
    }

    [RequiresTm1WebFact]
    public async Task EvaluatesMdxThroughTheEvalservice()
    {
        var (web, tm1) = (fixture.Tm1Web, fixture.Tm1Server);
        var server = await ServerNameAsync(tm1);
        using var session = await Tm1WebSession.OpenAsync(web);
        var id = await session.LoginAsync(web.AdminHost, server, tm1.Username, tm1.Password);
        var info = await tm1.InspectAsync();
        var result = await session.EvaluateMdxAsync(
            $"http://{info.Config!.Hostname}:{Tm1ServerContainer.RestPort}",
            server,
            id,
            "SELECT {[}Groups].Members} ON 0, {[}Clients].Members} ON 1 FROM [}ClientGroups]"
        );
        Assert.Contains("\"success\":true", result, StringComparison.Ordinal);
    }

    [RequiresTm1WebFact]
    public async Task SharesOneContainerWiredToTheSharedServer()
    {
        Assert.Same(await Tm1WebContainer.SharedAsync(), fixture.Tm1Web);
        Assert.Same(fixture.Tm1Server, fixture.Tm1Web.Tm1Server);
        Assert.StartsWith("http://", fixture.Tm1Web.Url, StringComparison.Ordinal);
        Assert.EndsWith("/tm1web/", fixture.Tm1Web.Url, StringComparison.Ordinal);
    }

    internal static async Task<string> ServerNameAsync(Tm1ServerContainer tm1)
    {
        var (status, body) = await Tm1ServerContainerTests.GetAsync(tm1, "Configuration/ServerName/$value");
        Assert.Equal(HttpStatusCode.OK, status);
        return body;
    }
}

/// <summary>Java's PawContainerTest: skipped without a PAW zip or the license accepted.</summary>
public class PawContainerTests(PawFixture fixture) : IClassFixture<PawFixture>
{
    [RequiresPawFact]
    public async Task LogsOnAndCallsTm1ThroughPaw()
    {
        var tm1 = fixture.Tm1Server;
        using var session = new PawSession(fixture.Paw.Url);
        await session.LoginAsync(tm1.Username, tm1.Password);
        var server = await Tm1WebContainerTests.ServerNameAsync(tm1);
        Assert.Contains(server, await session.ServersAsync());
        var (status, body) = await session.Tm1GetAsync(server, "ActiveUser?$select=Name");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Name\":\"" + tm1.Username + "\"", body, StringComparison.OrdinalIgnoreCase);
    }

    [RequiresPawFact]
    public async Task ServesTheWorkspaceAppOnceLoggedOn()
    {
        using var session = new PawSession(fixture.Paw.Url);
        await session.LoginAsync(fixture.Tm1Server.Username, fixture.Tm1Server.Password);
        var (status, body) = await session.GetAsync("");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("Planning Analytics", body, StringComparison.Ordinal);
    }

    [RequiresPawFact]
    public async Task RefusesAWrongPassword()
    {
        using var session = new PawSession(fixture.Paw.Url);
        await Assert.ThrowsAsync<System.IO.IOException>(() => session.LoginAsync(fixture.Tm1Server.Username, "not-the-password"));
    }

    [RequiresPawFact]
    public async Task SharesOnePawWiredToTheSharedServer()
    {
        Assert.Same(await PawContainer.SharedAsync(), fixture.Paw);
        Assert.Same(fixture.Tm1Server, fixture.Paw.Tm1Server);
        Assert.True(fixture.Paw.IsRunning);
        Assert.StartsWith("http://", fixture.Paw.Url, StringComparison.Ordinal);
        Assert.EndsWith("/", fixture.Paw.Url, StringComparison.Ordinal);
    }
}

/// <summary>Java's PawCamSecurityTest: PAW with Cognos (CAM) security, in a PlanningAnalyticsEnvironment. PAW and Cognos together need
/// most of a developer machine's Docker memory, so nothing else should run beside it. Skipped without TM1, Cognos and PAW sources.</summary>
public class PawCamSecurityTests
{
    [RequiresCognosAndPawFact]
    public async Task LogsOnThroughCognosAndCallsTm1()
    {
        await using var env = PlanningAnalyticsEnvironment
            .Builder()
            .Tm1("planning")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw", "Admins").WithUser("viewer", "viewerpw", "Readers"))
            .Paw(paw => paw.AcceptLicense())
            .Build();
        await env.StartAsync();
        var url = env.Paw().Url;

        // Browsers are sent to Cognos, as the host reaches it, to log on
        using var anonymous = new PawSession(url);
        var (_, page) = await anonymous.GetAsync("login");
        Assert.Contains("extLoginUrl = \"" + env.Cognos().Url + "v1/disp?b_action=xts.run", page, StringComparison.Ordinal);

        // A test user logs on with their Cognos password, and calls TM1 as themselves
        using var viewer = new PawSession(url);
        await viewer.LoginAsync("viewer", "viewerpw");
        Assert.Contains("planning", await viewer.ServersAsync());
        var (status, body) = await viewer.Tm1GetAsync("planning", "ActiveUser?$select=Name");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("CAMID", body, StringComparison.Ordinal);
        Assert.Contains("viewer", body, StringComparison.OrdinalIgnoreCase);

        // Cognos checks the password
        using var wrong = new PawSession(url);
        await Assert.ThrowsAsync<System.IO.IOException>(() => wrong.LoginAsync("viewer", "not-the-password"));
    }

    /// <summary>Needs TM1, Cognos and PAW, as Java's @RequiresCognos with @RequiresPaw.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class RequiresCognosAndPawFactAttribute : RequiresTm1FactAttribute
    {
        public RequiresCognosAndPawFactAttribute()
        {
            Skip ??= new RequiresCognosFactAttribute().Skip ?? new RequiresPawFactAttribute().Skip;
        }
    }
}

/// <summary>Java's EnvironmentExampleTest#aReusedEnvironmentKeepsItsContainersForTheNextRun: skipped without a TM1 source, or
/// with Testcontainers reuse off.</summary>
public class EnvironmentExampleTests
{
    [RequiresTm1AndReuseFact]
    public async Task AReusedEnvironmentKeepsItsContainersForTheNextRun()
    {
        // A configuration of its own, so it can't pick up a developer's reused environment
        var builder = PlanningAnalyticsEnvironment.Builder().Tm1("reuse-test", tm1 => tm1.WithEnv("TM1_CFG_MaxThreads", "3")).Reuse(true);
        var first = builder.Build();
        await first.StartAsync();
        var container = first.Tm1("reuse-test").ContainerId;
        await first.StopAsync();
        var second = builder.Build();
        try
        {
            await second.StartAsync();
            Assert.Equal(container, second.Tm1("reuse-test").ContainerId);
        }
        finally
        {
            using var docker = DockerHost.Client();
            await docker.Containers.RemoveContainerAsync(container, new ContainerRemoveParameters { Force = true, RemoveVolumes = true });
            await docker.Networks.DeleteNetworkAsync(second.Network.Name);
        }
    }

    /// <summary>A TM1 test that also needs the developer's reuse switch, as Java's assumeTrue on it.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class RequiresTm1AndReuseFactAttribute : RequiresTm1FactAttribute
    {
        public RequiresTm1AndReuseFactAttribute()
        {
            Skip ??= DockerHost.ReuseEnabled() ? null : "Testcontainers reuse is off: set testcontainers.reuse.enable=true in ~/.testcontainers.properties";
        }
    }
}

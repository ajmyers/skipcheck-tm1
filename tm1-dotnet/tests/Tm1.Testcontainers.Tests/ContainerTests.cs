using System;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Version;
using AjMyers.Tm1.Testcontainers.Xunit;

namespace AjMyers.Tm1.Testcontainers.Tests;

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
        var result = await session.EvaluateMdxAsync($"http://{info.Config!.Hostname}:{Tm1ServerContainer.RestPort}", server, id,
            "SELECT {[}Groups].Members} ON 0, {[}Clients].Members} ON 1 FROM [}ClientGroups]");
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

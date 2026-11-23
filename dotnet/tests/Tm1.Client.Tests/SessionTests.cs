// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The session against a fake TM1: login and cookie, re-login, views, batches, async
/// operations and capability checks, as the Java and Python clients' session tests.</summary>
public class SessionTests
{
    private readonly FakeTm1 _tm1 = new();

    /// <summary>The headers the Java client's <c>Connection.odataHeaders</c> sends, exactly.</summary>
    [Fact]
    public async Task SendsTheJavaClientsHeaders()
    {
        var session = _tm1.Session();
        var cubes = ODataPath.Of("Cubes");
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "c" };
        await session.ExecuteAsync(Tm1Request.Get<Cube>(cubes.Key("c")));
        Assert.Equal(
            ("application/json", "4.0", "4.0"),
            (_tm1.Last.Headers["accept"], _tm1.Last.Headers["odata-version"], _tm1.Last.Headers["odata-maxversion"])
        );
        await session.ExecuteAsync(Tm1Request.Post<Cube>(cubes, new Dictionary<string, object?> { ["Name"] = "c" }));
        Assert.Equal(("application/json", "application/json"), (_tm1.Last.Headers["accept"], _tm1.Last.Headers["content-type"]));
        _tm1.Respond = _ => "SKIPCHECK;";
        await session.ExecuteAsync(Tm1Request.Text(cubes.Key("c").Nav("Rules")));
        Assert.Equal("text/plain, application/json", _tm1.Last.Headers["accept"]);
    }

    [Fact]
    public async Task LogsInOnceThenSendsOnlyTheCookie()
    {
        _tm1.Respond = _ => FakeTm1.Collection();
        var tm1 = _tm1.Client();
        await tm1.Cubes.ListAsync().ToListAsync();
        await tm1.Cubes.ListAsync().ToListAsync();
        Assert.Equal(1, _tm1.Logins);
        Assert.All(_tm1.Requests, r =>
        {
            Assert.False(r.Headers.ContainsKey("authorization"));
            Assert.Equal("TM1SessionId=s1", r.Headers["cookie"]);
            Assert.Equal("4.0", r.Headers["odata-version"]);
        });
    }

    [Fact]
    public async Task LogsInAgainWhenTheSessionExpired()
    {
        var answers = new Queue<object?>([new HttpResponseMessage(HttpStatusCode.Unauthorized), FakeTm1.Collection(new { Name = "c" })]);
        _tm1.Respond = _ => answers.Dequeue();
        var cubes = await _tm1.Client().Cubes.ListAsync().ToListAsync();
        Assert.Equal("c", Assert.Single(cubes).Name);
        Assert.Equal(2, _tm1.Logins);
        Assert.Equal("TM1SessionId=s2", _tm1.Last.Headers["cookie"]);
    }

    [Fact]
    public async Task FollowsNextLinksAcrossPages()
    {
        _tm1.Respond = r =>
            r.Target == "Cubes?$select=Name"
                ? new Dictionary<string, object?> { ["value"] = new[] { new { Name = "a" } }, ["@odata.nextLink"] = "Cubes?$select=Name&$skiptoken=2" }
                : FakeTm1.Collection(new { Name = "b" });
        var names = await _tm1.Client().Cubes.ListAsync(ODataQuery.None().Select("Name")).Select(c => c.Name).ToListAsync();
        Assert.Equal(["a", "b"], names);
    }

    [Fact]
    public async Task SandboxAndChangeSetViewsAddTm1sOptions()
    {
        _tm1.Respond = _ => new Dictionary<string, object?>();
        var tm1 = _tm1.Client();
        await tm1.WithSandbox("What if").WithChangeSet("3").Cubes.GetAsync("c");
        Assert.Equal("Cubes('c')?!sandbox=What%20if&!ChangeSet=3", _tm1.Last.Target);
        await tm1.Cubes.GetAsync("c");
        Assert.Equal("Cubes('c')", _tm1.Last.Target);
        Assert.Equal(1, _tm1.Logins);
    }

    [Fact]
    public async Task ReadsBytesThatArentUtf8AsTheReplacementCharacter()
    {
        // As TM1's transaction log gives a password's hash, with "é" (two bytes) beside it
        byte[] hash = [0xe0, 0xec, (byte)'r', 0xff, 0xff];
        byte[] Body(string start, string end) => [.. Encoding.UTF8.GetBytes(start), .. hash, .. Encoding.UTF8.GetBytes(end)];
        _tm1.Respond = r => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(
                r.Target.StartsWith("Cubes(", StringComparison.Ordinal) ? Body("{\"Name\":\"é", "\"}") : Body("{\"value\":[{\"Name\":\"é", "\"}]}")
            ),
        };
        var replaced = "é" + new string((char)0xFFFD, 2) + "r" + new string((char)0xFFFD, 2);
        var tm1 = _tm1.Client();
        Assert.Equal(replaced, (await tm1.Cubes.GetAsync("c")).Name);
        Assert.Equal([replaced], (await tm1.Cubes.ListAsync(ODataQuery.None()).ToListAsync()).Select(c => c.Name));
    }

    [Fact]
    public async Task ABatchSendsItsCallsTogetherEachWithItsOwnResult()
    {
        _tm1.Respond = r =>
        {
            Assert.Equal("$batch", r.Target);
            var requests = r.Json.GetProperty("requests").EnumerateArray().ToList();
            Assert.Equal(["Cubes('a')?!sandbox=s", "Cubes('b')?!sandbox=s"], requests.Select(q => q.GetProperty("url").GetString()));
            // Each asks for its own media type, in lower case
            Assert.All(requests, q => Assert.Equal("application/json", q.GetProperty("headers").GetProperty("accept").GetString()));
            return new Dictionary<string, object?>
            {
                ["responses"] = new object[]
                {
                    new Dictionary<string, object?>
                    {
                        ["id"] = "1",
                        ["status"] = 200,
                        ["body"] = new { Name = "a" },
                    },
                    new Dictionary<string, object?>
                    {
                        ["id"] = "2",
                        ["status"] = 404,
                        ["body"] = new { error = new { code = "1", message = "'b' can not be found" } },
                    },
                },
            };
        };
        var batch = _tm1.Client().WithSandbox("s").Batch();
        var a = batch.Client.Cubes.GetAsync("a");
        var b = batch.Client.Cubes.GetAsync("b");
        Assert.Equal(2, batch.Size());
        Assert.False(a.IsCompleted);
        await batch.RunAsync();
        Assert.Equal("a", (await a).Name);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => b);
        Assert.Single(_tm1.Requests);
    }

    [Fact]
    public async Task AnAsyncViewPollsTheMonitorAndReadsTheResult()
    {
        var polls = 0;
        _tm1.Respond = r =>
        {
            if (r.Method == "POST")
            {
                Assert.Equal("respond-async", r.Headers["prefer"]);
                var accepted = new HttpResponseMessage(HttpStatusCode.Accepted);
                accepted.Headers.Location = new Uri("../_async('7')", UriKind.Relative);
                return accepted;
            }
            Assert.Equal("_async('7')", r.Target);
            return ++polls == 1
                ? new HttpResponseMessage(HttpStatusCode.Accepted)
                : new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Message(200, """{"Name":"c"}""")) };
        };
        // The Location is relative to the request's URL
        var path = ODataPath.Of("Processes").Key("p").Action("tm1.ExecuteWithReturn");
        var result = await _tm1.Client().WithAsync(TimeSpan.FromMilliseconds(1)).Session.ExecuteAsync(Tm1Request.Post<Cube>(path, null));
        Assert.Equal("c", result.Name);
        Assert.Equal(2, polls);
    }

    [Fact]
    public async Task CancellingAnAsyncOperationDeletesItsMonitor()
    {
        using var cancel = new CancellationTokenSource();
        _tm1.Respond = r =>
        {
            if (r.Method == "POST")
            {
                var accepted = new HttpResponseMessage(HttpStatusCode.Accepted);
                accepted.Headers.Location = new Uri("../_async('9')", UriKind.Relative);
                return accepted;
            }
            if (r.Method == "GET")
            {
                cancel.Cancel();
                return new HttpResponseMessage(HttpStatusCode.Accepted);
            }
            return null;
        };
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => _tm1.Client().WithAsync(TimeSpan.FromMilliseconds(1)).Cubes.LoadAsync("c", cancel.Token));
        Assert.Equal(("DELETE", "_async('9')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task RefusesAnOperationTheServerDoesntHave()
    {
        // Capabilities are cached per server and version for the process: this is another version
        _tm1.ProductVersion = "11.8.02000.1";
        _tm1.Metadata = Encoding.UTF8.GetBytes(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices>
              <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                <Annotation Term="Core.SchemaVersion"><String>11.8.20</String></Annotation>
                <EntityType Name="Cube"><Key><PropertyRef Name="Name"/></Key><Property Name="Name" Type="Edm.String"/></EntityType>
                <EntityContainer Name="API"><EntitySet Name="Cubes" EntityType="tm1.Cube"/></EntityContainer>
              </Schema></edmx:DataServices></edmx:Edmx>
            """
        );
        var e = await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(() => _tm1.Client().Cubes.ReserveAsync("c"));
        Assert.Equal(
            "Cube.Reserve isn't defined by the TM1 server at http://tm1:8010/api/v1/: it was added in schema 11.8.34, and the server reports schema 11.8.20, product version 11.8.02000.1",
            e.Message
        );
        Assert.Empty(_tm1.Requests);
    }

    /// <summary>The Java client's <c>ApiSpecInventoryTest</c>: the spec the coverage gate counts lists
    /// the endpoints of the snapshot it was made from, so every client is held to one inventory.</summary>
    [Fact]
    public void SpecListsTheEndpointsTheCoverageGateCounts()
    {
        var caps = Version.Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot));
        Assert.Equal("11.8.35", caps.SchemaVersion);
        Assert.True(caps.Endpoints.SetEquals(CoverageGateTests.Endpoints()));
        using var spec = System.Text.Json.JsonDocument.Parse(File.ReadAllText(Repo.Spec));
        Assert.Equal(caps.SchemaVersion, spec.RootElement.GetProperty("source").GetProperty("schemaVersion").GetString());
    }

    [Fact]
    public async Task RemembersARefusedLogin()
    {
        var tm1 = new FakeTm1Refusing();
        var client = tm1.Client();
        await Assert.ThrowsAsync<Tm1AuthenticationException>(() => client.Cubes.GetAsync("c"));
        var again = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => client.Cubes.GetAsync("c"));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, again.Kind);
        Assert.Equal(1, tm1.Attempts);
    }

    private static byte[] Message(int status, string json)
    {
        using var compressed = new MemoryStream();
        using (var gz = new GZipStream(compressed, CompressionMode.Compress, leaveOpen: true))
        {
            gz.Write(Encoding.UTF8.GetBytes(json));
        }
        var head = Encoding.ASCII.GetBytes($"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\n\r\n");
        return [.. head, .. compressed.ToArray()];
    }

    private sealed class FakeTm1Refusing : HttpMessageHandler
    {
        public int Attempts { get; private set; }

        public Tm1Client Client() => Tm1Client.Of(Tm1Session.Builder().BaseUrl(FakeTm1.Base).BasicAuth("admin", "wrong").HttpHandler(this).Build());

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Attempts++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.Unauthorized));
        }
    }
}

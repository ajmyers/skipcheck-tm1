using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Xml.Linq;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Live;

// The Java client's session, pool, capability, async, batch and client tests against a real TM1
// (Tm1SessionTm1Test, Tm1SessionImpersonationTm1Test, Tm1SessionPoolTm1Test, Tm1CapabilitiesTm1Test,
// Tm1SessionAsyncTm1Test, Tm1BatchTm1Test, Tm1ClientTm1Test), and its checks of TM1's own
// behaviour that the session relies on (Tm1SessionBehaviorTest, Tm1ImpersonationBehaviorTest).

/// <summary>TM1 over plain HTTP, as the Java tests' <c>RawTm1</c>: for setting up, checking what
/// the client left on TM1, and checking TM1's own behaviour, without going through the client.</summary>
public sealed class RawTm1 : IDisposable
{
    private const string Cookie = "TM1SessionId";

    private readonly HttpClient _http;
    private readonly string _root;
    private readonly string _basic;

    public RawTm1()
    {
        var (url, user, password) = Live.Target();
        _root = url;
        User = user;
        Password = password;
        _basic = Basic(user, password);
        _http = new HttpClient(Handler()) { Timeout = TimeSpan.FromSeconds(60) };
    }

    public string User { get; }

    public string Password { get; }

    public string Root => _root;

    /// <summary>The handler live tests send through: no cookie jar, and no certificate check with <c>TM1_VERIFY=false</c>.</summary>
    public static SocketsHttpHandler Handler()
    {
        // A header character that isn't ASCII goes out as "?", as Java's HttpClient writes it, so the
        // impersonation test sends what Java's does. .NET refuses to send one otherwise. Sent as
        // UTF-8, TM1 would accept the name: issue 184.
        var handler = new SocketsHttpHandler
        {
            UseCookies = false,
            PooledConnectionIdleTimeout = Connection.MaxIdle,
            RequestHeaderEncodingSelector = (_, _) => Encoding.ASCII,
        };
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            handler.SslOptions.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        }
        return handler;
    }

    /// <summary>Settings for a session of the TM1 under test, logging in as <see cref="Live.Client"/> does.</summary>
    public static Tm1SessionBuilder Sessions()
    {
        var (url, user, password) = Live.Target();
        var ns = Environment.GetEnvironmentVariable("TM1_URL") is { Length: > 0 } ? Environment.GetEnvironmentVariable("TM1_NAMESPACE") : null;
        var builder = Tm1Session
            .Builder()
            .BaseUrl(url)
            .Authenticator(ns is null ? Tm1Authenticator.Basic(user, password) : Tm1Authenticator.CamNamespace(user, password, ns));
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            builder.HttpHandler(Handler());
        }
        return builder;
    }

    public static string Basic(string user, string password) => "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes($"{user}:{password}"));

    /// <summary>A label unique to one test, for <see cref="SessionCount"/>.</summary>
    public static string UniqueContext() => "test-" + Guid.NewGuid();

    /// <summary>Logs in with Basic credentials and no cookie; the new session's cookie, as <c>name=value</c>.</summary>
    public string Login() =>
        CookieOf(Send(Request("GET", "ActiveSession?$select=ID").WithBasic())) ?? throw new InvalidOperationException("TM1 set no session cookie");

    public long SessionId(string cookie) => Json(Send(Request("GET", "ActiveSession?$select=ID").WithCookie(cookie))).GetProperty("ID").GetInt64();

    public void Close(string cookie) => Send(Request("POST", "ActiveSession/tm1.Close").WithCookie(cookie).WithBody("{}"));

    /// <summary>Kills another session by ID from <paramref name="admin"/>'s session.</summary>
    public int Kill(string admin, long id) => (int)Send(Request("POST", $"Sessions({id})/tm1.Close").WithCookie(admin).WithBody("{}")).Status;

    /// <summary>Waits until session <paramref name="id"/> is no longer listed (TM1 takes a few seconds).</summary>
    public bool AwaitGone(string admin, long id, TimeSpan timeout)
    {
        var deadline = Stopwatch.StartNew();
        while (Send(Request("GET", $"Sessions({id})?$select=ID").WithCookie(admin)).Status == HttpStatusCode.OK)
        {
            if (deadline.Elapsed > timeout)
            {
                return false;
            }
            Thread.Sleep(250);
        }
        return true;
    }

    /// <summary>Waits until TM1 lists <paramref name="expected"/> sessions labelled <paramref name="context"/>; the last count seen.</summary>
    public int AwaitSessionCount(string admin, string context, int expected, TimeSpan timeout)
    {
        var deadline = Stopwatch.StartNew();
        var count = SessionCount(admin, context);
        while (count != expected && deadline.Elapsed < timeout)
        {
            Thread.Sleep(250);
            count = SessionCount(admin, context);
        }
        return count;
    }

    /// <summary>How many sessions labelled <paramref name="context"/> TM1 has open: leak checks count
    /// a label unique to the test, since the server is shared.</summary>
    public int SessionCount(string admin, string context) =>
        Json(Send(Request("GET", "Sessions?$select=ID,Context").WithCookie(admin)))
            .GetProperty("value")
            .EnumerateArray()
            .Count(s => s.TryGetProperty("Context", out var c) && c.ValueKind == JsonValueKind.String && c.GetString() == context);

    /// <summary>How many sessions TM1 has open, those with no user included.</summary>
    public int AllSessionCount(string admin) => Json(Send(Request("GET", "Sessions?$select=ID").WithCookie(admin))).GetProperty("value").GetArrayLength();

    /// <summary>Creates a TM1 user with no groups (so not an administrator).</summary>
    public void CreateUser(string admin, string name, string password)
    {
        var created = Send(Request("POST", "Users").WithCookie(admin).WithBody(JsonSerializer.Serialize(new { Name = name, Password = password })));
        if (created.Status != HttpStatusCode.Created)
        {
            throw new InvalidOperationException($"Couldn't create user {name}: {(int)created.Status} {created.Body}");
        }
    }

    public void DeleteUser(string admin, string name) =>
        Send(Request("DELETE", $"Users('{Uri.EscapeDataString(name.Replace("'", "''", StringComparison.Ordinal))}')").WithCookie(admin));

    public RawRequest Request(string method, string path) => new(this, method, path);

    /// <summary>A response: status, headers (all values of each) and body.</summary>
    public sealed record RawResponse(HttpStatusCode Status, IReadOnlyDictionary<string, string[]> Headers, string Body)
    {
        public string? Header(string name) => Headers.TryGetValue(name, out var v) ? v.FirstOrDefault() : null;

        public IEnumerable<string> All(string name) => Headers.TryGetValue(name, out var v) ? v : [];
    }

    /// <summary>One request: method, path relative to the service root, and optional credentials, cookie, headers and JSON body.</summary>
    public sealed class RawRequest(RawTm1 tm1, string method, string path)
    {
        private readonly List<(string Name, string Value)> _headers = new();

        public string Method => method;

        public string? Body { get; private set; }

        public RawRequest WithBasic() => WithHeader("Authorization", tm1._basic);

        public RawRequest WithCookie(string cookie) => WithHeader("Cookie", cookie);

        public RawRequest WithHeader(string name, string value)
        {
            _headers.Add((name, value));
            return this;
        }

        public RawRequest WithBody(string json)
        {
            Body = json;
            return this;
        }

        internal HttpRequestMessage Build()
        {
            var message = new HttpRequestMessage(new HttpMethod(method), tm1._root + path);
            foreach (var (name, value) in _headers)
            {
                message.Headers.TryAddWithoutValidation(name, value);
            }
            if (Body is not null)
            {
                message.Content = new StringContent(Body, Encoding.UTF8, "application/json");
            }
            return message;
        }

        public override string ToString() => $"{method} {path}";
    }

    /// <summary>Sends a request; a GET or DELETE on a kept-alive connection TM1 closed gets one more try.</summary>
    public RawResponse Send(RawRequest request) => SendAsync(request).GetAwaiter().GetResult();

    public async Task<RawResponse> SendAsync(RawRequest request)
    {
        try
        {
            return await SendOnceAsync(request);
        }
        catch (HttpRequestException) when (request.Method is "GET" or "DELETE")
        {
            return await SendOnceAsync(request);
        }
    }

    private async Task<RawResponse> SendOnceAsync(RawRequest request)
    {
        using var message = request.Build();
        using var response = await _http.SendAsync(message);
        var headers = response
            .Headers.Concat(response.Content.Headers)
            .GroupBy(h => h.Key, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.SelectMany(h => h.Value).ToArray(), StringComparer.OrdinalIgnoreCase);
        return new RawResponse(response.StatusCode, headers, await response.Content.ReadAsStringAsync());
    }

    /// <summary>The <c>TM1SessionId</c> cookie a response sets, as <c>name=value</c>.</summary>
    public static string? CookieOf(RawResponse response) =>
        response.All("Set-Cookie").Where(c => c.StartsWith(Cookie + "=", StringComparison.Ordinal)).Select(c => c.Split(';')[0]).FirstOrDefault();

    public static JsonElement Json(RawResponse response) => JsonDocument.Parse(response.Body).RootElement.Clone();

    public static string Scratch(string prefix = "zz_session_") => prefix + Guid.NewGuid().ToString("N")[..8];

    public void Dispose() => _http.Dispose();
}

/// <summary>The Java client's <c>Tm1SessionTm1Test</c>: the session against a real TM1, leaving no session behind.</summary>
public sealed class Tm1SessionTm1Test : IDisposable
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(30);
    private static readonly Tm1Request<JsonElement> Server = Tm1Request.Get<JsonElement>(
        ODataPath.Of("Server"),
        ODataQuery.None().Select("Name", "ProductVersion")
    );

    private readonly Lazy<(RawTm1 Raw, string Admin)> _setUp = new(() =>
    {
        var raw = new RawTm1();
        return (raw, raw.Login());
    });

    private readonly string _context = RawTm1.UniqueContext();

    private RawTm1 Raw => _setUp.Value.Raw;

    private string Admin => _setUp.Value.Admin;

    public void Dispose()
    {
        if (_setUp.IsValueCreated)
        {
            Raw.Close(Admin);
            Raw.Dispose();
        }
    }

    [Tm1Fact]
    public async Task ReadsTheServerAndClosesItsSessionLeavingNoneBehind()
    {
        var session = Session();
        var tm1 = await session.ExecuteAsync(Server);
        Assert.Equal(
            RawTm1.Json(Raw.Send(Raw.Request("GET", "Server?$select=ProductVersion").WithCookie(Admin))).GetProperty("ProductVersion").GetString(),
            tm1.GetProperty("ProductVersion").GetString()
        );
        var id = await session.SessionIdAsync();
        Assert.Equal(1, Raw.SessionCount(Admin, _context));
        await session.CloseAsync();
        Assert.True(Raw.AwaitGone(Admin, id, Wait));
        Assert.Equal(0, Raw.AwaitSessionCount(Admin, _context, 0, Wait));
    }

    [Tm1Fact]
    public async Task SharesOneLoginBetweenConcurrentFirstRequests()
    {
        var session = Session();
        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => session.ExecuteAsync(Server)));
        Assert.Equal(8, results.Length);
        Assert.Equal(1, Raw.SessionCount(Admin, _context));
        await session.CloseAsync();
        Assert.Equal(0, Raw.AwaitSessionCount(Admin, _context, 0, Wait));
    }

    [Tm1Fact]
    public async Task LeavesNoSessionWhenCloseRacesTheFirstRequest()
    {
        var session = Session();
        var first = session.ExecuteAsync(Server);
        await session.CloseAsync();
        try
        {
            await first;
        }
        catch (Exception)
        {
            // Either outcome is fine; what matters is what's left on TM1
        }
        Assert.Equal(0, Raw.AwaitSessionCount(Admin, _context, 0, Wait));
    }

    /// <summary>The replay the client relies on, end to end: the session is killed from another
    /// session, then a POST through the client logs in again and creates the dimension exactly once,
    /// and the session TM1 opened for the 401 is the one logged in, so nothing is left behind.</summary>
    [Tm1Fact]
    public async Task LogsInAgainAfterItsSessionIsKilledAndRunsThePostOnce()
    {
        var session = Session();
        var name = RawTm1.Scratch();
        try
        {
            var first = await session.SessionIdAsync();
            Assert.Equal(204, Raw.Kill(Admin, first));
            var dimension = new Dictionary<string, object?>
            {
                ["Name"] = name,
                ["Hierarchies"] = new[] { new Dictionary<string, object?> { ["Name"] = name } },
            };
            await session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Dimensions"), dimension));
            Assert.Equal("1", Raw.Send(Raw.Request("GET", $"Dimensions/$count?$filter=Name%20eq%20'{name}'").WithCookie(Admin)).Body);
            Assert.NotEqual(first, await session.SessionIdAsync());
        }
        finally
        {
            Raw.Send(Raw.Request("DELETE", $"Dimensions('{name}')").WithCookie(Admin));
            await session.CloseAsync();
        }
        Assert.Equal(0, Raw.AwaitSessionCount(Admin, _context, 0, Wait));
    }

    [Tm1Fact]
    public async Task LabelsTheSessionWithItsContext()
    {
        var session = RawTm1.Sessions().SessionContext("tm1-client test").Build();
        try
        {
            var active = await session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("ActiveSession"), ODataQuery.None().Select("Context")));
            Assert.Equal("tm1-client test", active.GetProperty("Context").GetString());
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    /// <summary>TM1 reads <c>!sandbox</c> on any request: a sandbox that exists works, one that doesn't fails.</summary>
    [Tm1Fact]
    public async Task RunsRequestsInTheViewsSandbox()
    {
        var session = Session();
        var sandbox = RawTm1.Scratch();
        try
        {
            await session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Sandboxes"), new Dictionary<string, object?> { ["Name"] = sandbox }));
            await session.WithSandbox(sandbox).ExecuteAsync(Server);
            await Assert.ThrowsAnyAsync<Tm1ApiException>(() => session.WithSandbox(sandbox + "_missing").ExecuteAsync(Server));
        }
        finally
        {
            try
            {
                await session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Sandboxes").Key(sandbox)));
            }
            catch (Tm1Exception)
            {
                // Not created
            }
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task StreamsACollection()
    {
        var session = Session();
        try
        {
            var names = await session
                .StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Dimensions"), ODataQuery.None().Select("Name")))
                .Select(d => d.GetProperty("Name").GetString())
                .ToListAsync();
            Assert.NotEmpty(names);
            Assert.All(names, n => Assert.False(string.IsNullOrWhiteSpace(n)));
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task ReportsAMissingObjectAsNotFound()
    {
        var session = Session();
        try
        {
            await Assert.ThrowsAsync<Tm1NotFoundException>(() =>
                session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("zz_no_such_cube")))
            );
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    /// <summary>A session labelled with this test's context, so the leak checks count only its sessions.</summary>
    private Tm1Session Session() => RawTm1.Sessions().SessionContext(_context).Build();
}

/// <summary>The Java client's <c>Tm1SessionImpersonationTm1Test</c>.</summary>
public sealed class Tm1SessionImpersonationTm1Test : IDisposable
{
    private static readonly Tm1Request<JsonElement> ActiveUser = Tm1Request.Get<JsonElement>(ODataPath.Of("ActiveUser"), ODataQuery.None().Select("Name"));

    private readonly Lazy<(RawTm1 Raw, string Admin, string Alice)> _setUp = new(() =>
    {
        var raw = new RawTm1();
        var admin = raw.Login();
        var alice = "zz_alice_" + Guid.NewGuid().ToString("N")[..6];
        raw.CreateUser(admin, alice, "a");
        return (raw, admin, alice);
    });

    private RawTm1 Raw => _setUp.Value.Raw;

    private string Admin => _setUp.Value.Admin;

    private string Alice => _setUp.Value.Alice;

    public void Dispose()
    {
        if (_setUp.IsValueCreated)
        {
            Raw.DeleteUser(Admin, Alice);
            Raw.Close(Admin);
            Raw.Dispose();
        }
    }

    [Tm1Fact]
    public async Task RunsAsTheImpersonatedUser()
    {
        var session = Session(Alice.ToUpperInvariant());
        try
        {
            Assert.Equal(Alice, (await session.ExecuteAsync(ActiveUser)).GetProperty("Name").GetString());
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task ReportsAnUnknownUser() => Assert.Equal(Tm1ErrorKind.UnknownUser, await KindOf(Session("zz_nobody")));

    [Tm1Fact]
    public async Task ReportsThatAdministratorsCantBeImpersonated() => Assert.Equal(Tm1ErrorKind.ImpersonationRefused, await KindOf(Session("Admin")));

    [Tm1Fact]
    public async Task ReportsADisabledUserAsRefusedCredentials()
    {
        Raw.Send(Raw.Request("PATCH", $"Users('{Alice}')").WithCookie(Admin).WithBody("{\"Enabled\":false}"));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, await KindOf(Session(Alice)));
    }

    /// <summary>Each refused login leaves a session on TM1; a remembered refusal doesn't add more.</summary>
    [Tm1Fact]
    public async Task DoesntLogInAgainWhileARefusalIsRemembered()
    {
        var session = Session("zz_nobody");
        Assert.Equal(Tm1ErrorKind.UnknownUser, await KindOf(session));
        var after = Raw.AllSessionCount(Admin);
        for (int i = 0; i < 5; i++)
        {
            Assert.Equal(Tm1ErrorKind.UnknownUser, await KindOf(session));
        }
        Assert.Equal(after, Raw.AllSessionCount(Admin));
    }

    private static Tm1Session Session(string user) => RawTm1.Sessions().Impersonate(user).Build();

    private static async Task<Tm1ErrorKind> KindOf(Tm1Session session) =>
        (await Assert.ThrowsAnyAsync<Tm1ApiException>(() => session.ExecuteAsync(ActiveUser))).Kind;
}

/// <summary>The Java client's <c>Tm1SessionPoolTm1Test</c>.</summary>
public sealed class Tm1SessionPoolTm1Test : IAsyncLifetime
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(30);
    private static readonly Tm1Request<JsonElement> ActiveUser = Tm1Request.Get<JsonElement>(ODataPath.Of("ActiveUser"), ODataQuery.None().Select("Name"));

    private RawTm1? _raw;
    private string _admin = "";
    private string _alice = "";
    private string _bob = "";
    private readonly string _context = RawTm1.UniqueContext();
    private Tm1SessionPool? _pool;

    private RawTm1 Raw => _raw!;

    private Tm1SessionPool Pool => _pool!;

    public Task InitializeAsync()
    {
        if (!Live.Configured)
        {
            return Task.CompletedTask;
        }
        _raw = new RawTm1();
        _admin = Raw.Login();
        var run = Guid.NewGuid().ToString("N")[..6];
        _alice = "zz_alice_" + run;
        _bob = "zz_bob_" + run;
        Raw.CreateUser(_admin, _alice, "a");
        Raw.CreateUser(_admin, _bob, "b");
        _pool = Tm1SessionPool
            .Builder(RawTm1.Sessions().SessionContext(_context))
            .IdleTimeout(TimeSpan.FromSeconds(1))
            .CloseGrace(TimeSpan.Zero)
            .SweepInterval(TimeSpan.FromHours(1))
            .Build();
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        if (_raw is null)
        {
            return;
        }
        await Pool.CloseAsync().WaitAsync(Wait);
        Raw.DeleteUser(_admin, _alice);
        Raw.DeleteUser(_admin, _bob);
        Raw.Close(_admin);
        Raw.Dispose();
    }

    [Tm1Fact]
    public async Task RunsEachIdentityAsItself()
    {
        Assert.Equal(_alice, await Name(Pool.Session(Tm1Identity.Impersonating(_alice))));
        Assert.Equal(_bob, await Name(Pool.Session(Tm1Identity.Impersonating(_bob))));
        Assert.Equal(_bob, await Name(Pool.Session(Tm1Identity.WithCredentials(_bob, Tm1Authenticator.Basic(_bob, "b")))));
        Assert.Equal("Admin", await Name(Pool.Session()), ignoreCase: true);
        Assert.Equal(4, Pool.Stats().Held);
    }

    [Tm1Fact]
    public async Task ClosesEvictedSessionsOnTm1()
    {
        var session = Pool.Session(Tm1Identity.Impersonating(_alice));
        Assert.Equal(_alice, await Name(session));
        var id = await session.SessionIdAsync();
        await Task.Delay(1100);
        Pool.EvictExpired();
        Assert.True(Raw.AwaitGone(_admin, id, Wait));
        await Runtime.ScriptedTm1.Eventually(() => Pool.Stats().Closed == 1, "for the pool to count the close");
    }

    [Tm1Fact]
    public async Task LeavesNoSessionBehindWhenClosed()
    {
        await Name(Pool.Session(Tm1Identity.Impersonating(_alice)));
        await Name(Pool.Session(Tm1Identity.Impersonating(_bob)));
        await Pool.CloseAsync().WaitAsync(Wait);
        Assert.Equal(0, Raw.AwaitSessionCount(_admin, _context, 0, Wait));
    }

    /// <summary>Asking for an administrator fails; nothing runs as the pool's own user instead.</summary>
    [Tm1Fact]
    public async Task NeverFallsBackToAnotherIdentity()
    {
        var e = await Assert.ThrowsAnyAsync<Tm1ApiException>(() => Pool.Session(Tm1Identity.Impersonating("Admin")).ExecuteAsync(ActiveUser));
        Assert.Equal(Tm1ErrorKind.ImpersonationRefused, e.Kind);
    }

    private static async Task<string?> Name(Tm1Session session) => (await session.ExecuteAsync(ActiveUser)).GetProperty("Name").GetString();
}

/// <summary>The Java client's <c>Tm1CapabilitiesTm1Test</c>.</summary>
public class Tm1CapabilitiesTm1Test
{
    [Tm1Fact]
    public async Task ReadsTheVersionAndTheCapabilitiesTheSnapshotDescribes()
    {
        var session = RawTm1.Sessions().Build();
        try
        {
            var reported = (await session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Server"), ODataQuery.None().Select("ProductVersion"))))
                .GetProperty("ProductVersion")
                .GetString()!;
            Assert.Equal(Tm1Version.Parse(reported), await session.VersionAsync());
            var capabilities = await session.CapabilitiesAsync();
            var snapshot = XDocument.Load(Repo.Snapshot);
            var snapshotVersion = Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot)).SchemaVersion;
            Assert.Equal(snapshotVersion, capabilities.SchemaVersion);
            var container = snapshot.Descendants().Single(e => e.Name.LocalName == "EntityContainer");
            foreach (var set in container.Elements().Where(e => e.Name.LocalName == "EntitySet"))
            {
                Assert.True(capabilities.Supports(Tm1Capability.EntitySet((string)set.Attribute("Name")!)), (string?)set.Attribute("Name"));
            }
            foreach (var singleton in container.Elements().Where(e => e.Name.LocalName == "Singleton"))
            {
                Assert.True(capabilities.Supports(Tm1Capability.Singleton((string)singleton.Attribute("Name")!)), (string?)singleton.Attribute("Name"));
            }
            foreach (var import in container.Elements().Where(e => e.Name.LocalName == "ActionImport"))
            {
                Assert.True(capabilities.Supports(Tm1Capability.UnboundOperation((string)import.Attribute("Name")!)), (string?)import.Attribute("Name"));
            }
            Assert.True(capabilities.Supports(Tm1Capability.BoundOperation("tm1.Cube", "tm1.Reserve")));
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task RefusesARequestTheServerDoesntDefine()
    {
        var session = RawTm1.Sessions().Build();
        try
        {
            var version = await session.VersionAsync();
            var missing = Tm1Request.Get<JsonElement>(ODataPath.Of("Bananas")).Requires(Tm1Capability.EntitySet("Bananas").Since("99.0"));
            var e = await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(() => session.ExecuteAsync(missing));
            Assert.Contains("added in schema 99.0", e.Message);
            Assert.Contains("reports schema " + Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot)).SchemaVersion, e.Message);
            Assert.Contains("product version " + version, e.Message);
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task SharesOneFetchBetweenAPoolsSessions()
    {
        var (_, user, password) = Live.Target();
        await using var pool = Tm1SessionPool.Builder(RawTm1.Sessions()).Build();
        var first = await pool.Session().CapabilitiesAsync();
        var second = await pool.Session(Tm1Identity.WithCredentials("again", Tm1Authenticator.Basic(user, password))).CapabilitiesAsync();
        Assert.Same(first, second);
    }
}

/// <summary>The Java client's <c>Tm1SessionAsyncTm1Test</c>: a process run asynchronously, and stopped by cancelling.</summary>
public class Tm1SessionAsyncTm1Test
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(60);

    [Tm1Fact]
    public async Task RunsAProcessAndReadsACollectionThroughTheMonitor()
    {
        await using var tm1 = Live.Client();
        var async = tm1.WithAsync(TimeSpan.FromMilliseconds(200));
        var process = "tm1-client-" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(CreateProcess(process, "x = 1;\n"));
        try
        {
            var result = await async.Session.ExecuteAsync(Execute(process)).WaitAsync(Wait);
            Assert.Equal("CompletedSuccessfully", result.GetProperty("ProcessExecuteStatusCode").GetString());
            Assert.NotEmpty(
                await async.Session.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"), ODataQuery.None().Select("Name"))).ToListAsync()
            );
        }
        finally
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
        }
    }

    [Tm1Fact]
    public async Task StopsTheWorkOnTm1WhenCancelled()
    {
        await using var tm1 = Live.Client();
        var async = tm1.WithAsync(TimeSpan.FromMilliseconds(200));
        var process = "tm1-client-" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(CreateProcess(process, "i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n"));
        try
        {
            using var cancel = new CancellationTokenSource();
            var run = async.Session.ExecuteAsync(Execute(process), cancel.Token);
            Assert.True(await WaitFor(tm1, process, true), "the process runs");
            cancel.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => run);
            Assert.True(await WaitFor(tm1, process, false), "cancelling deleted the monitor, which stopped it");
        }
        finally
        {
            await WaitFor(tm1, process, false);
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
        }
    }

    internal static Tm1Request<NoContent> CreateProcess(string name, string prolog) =>
        Tm1Request.Post(ODataPath.Of("Processes"), new Dictionary<string, object?> { ["Name"] = name, ["PrologProcedure"] = prolog });

    internal static Tm1Request<JsonElement> Execute(string process) =>
        Tm1Request.Post<JsonElement>(ODataPath.Of("Processes").Key(process).Action("tm1.ExecuteWithReturn"), null);

    /// <summary>Waits up to 30 s for a thread running <paramref name="process"/> to be there, or gone.</summary>
    private static async Task<bool> WaitFor(Tm1Client tm1, string process, bool running)
    {
        for (int i = 0; i < 150; i++)
        {
            var found = await tm1
                .Session.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Threads")))
                .AnyAsync(t =>
                    t.TryGetProperty("Function", out var f)
                    && f.ValueKind == JsonValueKind.String
                    && f.GetString()!.Contains($"Processes('{process}')", StringComparison.Ordinal)
                );
            if (found == running)
            {
                return true;
            }
            await Task.Delay(200);
        }
        return false;
    }
}

/// <summary>The Java client's <c>Tm1BatchTm1Test</c>.</summary>
public class Tm1BatchTm1Test
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(60);

    [Tm1Fact]
    public async Task RunsReadsWritesAndFailuresTogether()
    {
        await using var tm1 = Live.Client();
        var dimension = "batch" + Guid.NewGuid().ToString("N")[..8];
        var process = "tm1-client-" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                ODataPath.Of("Dimensions"),
                new Dictionary<string, object?> { ["Name"] = dimension, ["Hierarchies"] = new[] { new Dictionary<string, object?> { ["Name"] = dimension } } }
            )
        );
        await tm1.Session.ExecuteAsync(Tm1SessionAsyncTm1Test.CreateProcess(process, "x = 1;\n"));
        try
        {
            var batch = tm1.Batch();
            var queued = batch.Client.Session;
            var elements = ODataPath.Of("Dimensions").Key(dimension).Nav("Hierarchies").Key(dimension).Nav("Elements");
            var cube = queued.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("}ClientGroups"), ODataQuery.None().Select("Name")));
            var missing = queued.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("not-a-cube")));
            var north = queued.ExecuteAsync(Tm1Request.Post<JsonElement>(elements, new Dictionary<string, object?> { ["Name"] = "North" }));
            var twice = queued.ExecuteAsync(Tm1Request.Post<JsonElement>(elements, new Dictionary<string, object?> { ["Name"] = "North" }));
            var listed = queued.StreamAsync(Tm1Request.List<JsonElement>(elements)).Select(e => e.GetProperty("Name").GetString()).ToListAsync().AsTask();
            await Runtime.ScriptedTm1.Eventually(() => batch.Size() == 5, "for the list to queue");
            var run = queued.ExecuteAsync(Tm1SessionAsyncTm1Test.Execute(process));
            Assert.Equal(6, batch.Size());
            await batch.RunAsync().WaitAsync(Wait);
            Assert.Equal("}ClientGroups", (await cube).GetProperty("Name").GetString());
            await Assert.ThrowsAsync<Tm1NotFoundException>(() => missing);
            Assert.Equal("North", (await north).GetProperty("Name").GetString());
            await Assert.ThrowsAnyAsync<Tm1Exception>(() => twice); // TM1 carries on past it, in order
            Assert.Equal(["North"], await listed); // sees the element made before it
            Assert.Equal("CompletedSuccessfully", (await run).GetProperty("ProcessExecuteStatusCode").GetString());
        }
        finally
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Dimensions").Key(dimension)));
        }
    }

    [Tm1Fact]
    public async Task TakesAThousandRequests()
    {
        await using var tm1 = Live.Client();
        var batch = tm1.Batch();
        var reads = Enumerable
            .Range(0, 1000)
            .Select(_ =>
                batch.Client.Session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("}ClientGroups"), ODataQuery.None().Select("Name")))
            )
            .ToList();
        await batch.RunAsync(1000).WaitAsync(Wait);
        Assert.Equal(1000, (await Task.WhenAll(reads)).Length);
    }
}

/// <summary>The Java client's <c>Tm1ClientTm1Test</c>.</summary>
public class Tm1ClientTm1Test
{
    [Tm1Fact]
    public async Task ConnectsReadsTheServerAndCloses()
    {
        var client = Live.Client();
        var server = await (await client.ConnectAsync()).Session.ExecuteAsync(
            Tm1Request.Get<JsonElement>(ODataPath.Of("Server"), ODataQuery.None().Select("Name", "ProductVersion"))
        );
        var version = await client.Session.VersionAsync();
        await client.CloseAsync();
        Assert.Equal(version.ToString(), server.GetProperty("ProductVersion").GetString());
        Assert.True(client.Session.IsClosed());
        Assert.Equal("x", client.WithSandbox("x").Session.Sandbox());
    }
}

/// <summary>The Java client's <c>Tm1SessionBehaviorTest</c>: what TM1 itself does that the session
/// relies on, checked over plain HTTP.</summary>
public sealed class Tm1SessionBehaviorTest : IDisposable
{
    private readonly Lazy<RawTm1> _raw = new(() => new RawTm1());

    private RawTm1 Tm1 => _raw.Value;

    public void Dispose()
    {
        if (_raw.IsValueCreated)
        {
            Tm1.Dispose();
        }
    }

    /// <summary>The client replays a request once after a 401 on an established session, POSTs
    /// included. That's only safe if TM1 refuses the request before running it.</summary>
    [Tm1Fact]
    public void RefusesARequestOnAKilledSessionWithoutRunningIt()
    {
        var admin = Tm1.Login();
        var victim = Tm1.Login();
        var dimension = RawTm1.Scratch();
        var create = Dimension(dimension);
        try
        {
            Assert.Equal(204, Tm1.Kill(admin, Tm1.SessionId(victim)));
            var refused = Tm1.Send(Tm1.Request("POST", "Dimensions").WithCookie(victim).WithBody(create));
            Assert.Equal(HttpStatusCode.Unauthorized, refused.Status);
            Assert.Equal(HttpStatusCode.NotFound, Tm1.Send(Tm1.Request("GET", $"Dimensions('{dimension}')").WithCookie(admin)).Status); // the refused POST must not have created it
            var relogged = RawTm1.CookieOf(refused)!;
            Assert.Equal(HttpStatusCode.OK, Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=ID").WithCookie(relogged).WithBasic()).Status);
            Assert.Equal(HttpStatusCode.Created, Tm1.Send(Tm1.Request("POST", "Dimensions").WithCookie(relogged).WithBody(create)).Status);
            Assert.Equal("1", Tm1.Send(Tm1.Request("GET", $"Dimensions/$count?$filter=Name%20eq%20'{dimension}'").WithCookie(admin)).Body);
            Tm1.Close(relogged);
        }
        finally
        {
            Tm1.Send(Tm1.Request("DELETE", $"Dimensions('{dimension}')").WithCookie(admin));
            Tm1.Close(admin);
        }
    }

    /// <summary>Every 401 opens a TM1 session with no user, and sets its cookie. Sending that cookie
    /// with the credentials logs that same session in, instead of opening another.</summary>
    [Tm1Fact]
    public void OpensASessionOnEvery401AndLogsThatSessionInWhenItsCookieIsSentWithCredentials()
    {
        var admin = Tm1.Login();
        try
        {
            var before = Tm1.AllSessionCount(admin);
            var anonymous = Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=ID"));
            Assert.Equal(HttpStatusCode.Unauthorized, anonymous.Status);
            Assert.Equal("Basic realm=\"TM1\"", anonymous.Header("WWW-Authenticate"));
            var cookie = RawTm1.CookieOf(anonymous)!;
            Assert.Equal(before + 1, Tm1.AllSessionCount(admin)); // sessions after one 401
            var login = Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=ID").WithCookie(cookie).WithBasic());
            Assert.Equal(HttpStatusCode.OK, login.Status);
            Assert.Null(RawTm1.CookieOf(login)); // no new cookie: the same session was logged in
            Assert.Equal(before + 1, Tm1.AllSessionCount(admin));
            Tm1.Close(cookie);
            Assert.Equal(before, Tm1.AllSessionCount(admin));
        }
        finally
        {
            Tm1.Close(admin);
        }
    }

    /// <summary><c>Server</c> can be read with no credentials, but even that opens a session. So the
    /// client never reads anything, the version included, outside its own session.</summary>
    [Tm1Fact]
    public void OpensASessionForAnAnonymousReadOfServer()
    {
        var admin = Tm1.Login();
        try
        {
            var before = Tm1.AllSessionCount(admin);
            var server = Tm1.Send(Tm1.Request("GET", "Server?$select=Name"));
            Assert.Equal(HttpStatusCode.OK, server.Status);
            Assert.Equal(before + 1, Tm1.AllSessionCount(admin));
            var cookie = RawTm1.CookieOf(server)!;
            Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=ID").WithCookie(cookie).WithBasic());
            Tm1.Close(cookie);
        }
        finally
        {
            Tm1.Close(admin);
        }
    }

    /// <summary>A closed session is refused at once, but TM1 lists it in <c>Sessions</c> for several
    /// seconds more, so a check that no session is left behind has to wait for the count to settle.</summary>
    [Tm1Fact]
    public async Task RefusesAClosedSessionAtOnceButListsItForSecondsMore()
    {
        var admin = Tm1.Login();
        var cookie = Tm1.Login();
        var id = Tm1.SessionId(cookie);
        try
        {
            var reads = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => Tm1.SendAsync(Tm1.Request("GET", "Server?$select=Name").WithCookie(cookie))));
            Assert.All(reads, r => Assert.Equal(HttpStatusCode.OK, r.Status));
            Assert.Equal(HttpStatusCode.NoContent, Tm1.Send(Tm1.Request("POST", "ActiveSession/tm1.Close").WithCookie(cookie).WithBody("{}")).Status);
            Assert.Equal(HttpStatusCode.OK, Tm1.Send(Tm1.Request("GET", $"Sessions({id})").WithCookie(admin)).Status); // still listed just after close
            Assert.True(Tm1.AwaitGone(admin, id, TimeSpan.FromSeconds(30)), "gone within 30 s");
        }
        finally
        {
            Tm1.Close(admin);
        }
    }

    /// <summary>Closing a session expires its cookie, and later requests with that cookie get a 401.</summary>
    [Tm1Fact]
    public void ExpiresTheCookieOnCloseAndRefusesItAfterwards()
    {
        var cookie = Tm1.Login();
        var close = Tm1.Send(Tm1.Request("POST", "ActiveSession/tm1.Close").WithCookie(cookie).WithBody("{}"));
        Assert.Equal(HttpStatusCode.NoContent, close.Status);
        Assert.Contains(close.All("Set-Cookie"), c =>
            c.StartsWith("TM1SessionId=", StringComparison.Ordinal) && c.Contains("Max-Age=0", StringComparison.Ordinal)
        );
        var after = Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=ID").WithCookie(cookie));
        Assert.Equal(HttpStatusCode.Unauthorized, after.Status);
        var leftover = RawTm1.CookieOf(after)!;
        Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=ID").WithCookie(leftover).WithBasic());
        Tm1.Close(leftover);
    }

    /// <summary>Requests on one session run in parallel: three one-second process runs finish in
    /// about one second. So one shared session doesn't serialize the client's traffic.</summary>
    [Tm1Fact]
    public async Task RunsConcurrentRequestsOnOneSessionInParallel()
    {
        var cookie = Tm1.Login();
        var process = RawTm1.Scratch();
        try
        {
            Assert.Equal(
                HttpStatusCode.Created,
                Tm1.Send(
                    Tm1.Request("POST", "Processes").WithCookie(cookie).WithBody($"{{\"Name\":\"{process}\",\"PrologProcedure\":\"Sleep(1000);\"}}")
                ).Status
            );
            var start = Stopwatch.StartNew();
            var runs = await Task.WhenAll(
                Enumerable
                    .Range(0, 3)
                    .Select(_ => Tm1.SendAsync(Tm1.Request("POST", $"Processes('{process}')/tm1.ExecuteWithReturn").WithCookie(cookie).WithBody("{}")))
            );
            Assert.All(runs, r => Assert.Equal("CompletedSuccessfully", RawTm1.Json(r).GetProperty("ProcessExecuteStatusCode").GetString()));
            Assert.True(start.Elapsed < TimeSpan.FromMilliseconds(2500), $"three 1 s runs on one session took {start.Elapsed}");
        }
        finally
        {
            Tm1.Send(Tm1.Request("DELETE", $"Processes('{process}')").WithCookie(cookie));
            Tm1.Close(cookie);
        }
    }

    /// <summary><c>TM1-SessionContext</c> labels the whole session, and the last request to send it
    /// wins. So it can't be a per-request setting on views that share one session.</summary>
    [Tm1Fact]
    public void LabelsTheSessionWithTheLastSessionContextSent()
    {
        var login = Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=Context").WithBasic().WithHeader("TM1-SessionContext", "first"));
        var cookie = RawTm1.CookieOf(login)!;
        try
        {
            Assert.Equal("first", RawTm1.Json(login).GetProperty("Context").GetString());
            Tm1.Send(Tm1.Request("GET", "Server?$select=Name").WithCookie(cookie).WithHeader("TM1-SessionContext", "second"));
            Assert.Equal(
                "second",
                RawTm1.Json(Tm1.Send(Tm1.Request("GET", "ActiveSession?$select=Context").WithCookie(cookie))).GetProperty("Context").GetString()
            );
        }
        finally
        {
            Tm1.Close(cookie);
        }
    }

    /// <summary>A create answers 201 with a relative <c>Location</c> and an <c>ETag</c>; with
    /// <c>Prefer: return=minimal</c>, 204 with <c>Location</c> and <c>OData-EntityId</c>.</summary>
    [Tm1Fact]
    public void AnswersACreateWithItsLocation()
    {
        var cookie = Tm1.Login();
        var full = RawTm1.Scratch();
        var minimal = RawTm1.Scratch();
        try
        {
            var created = Tm1.Send(Tm1.Request("POST", "Dimensions").WithCookie(cookie).WithBody(Dimension(full)));
            Assert.Equal(HttpStatusCode.Created, created.Status);
            Assert.Equal($"Dimensions('{full}')", created.Header("Location"));
            Assert.NotNull(created.Header("ETag"));
            var bare = Tm1.Send(Tm1.Request("POST", "Dimensions").WithCookie(cookie).WithHeader("Prefer", "return=minimal").WithBody(Dimension(minimal)));
            Assert.Equal(HttpStatusCode.NoContent, bare.Status);
            Assert.Equal($"Dimensions('{minimal}')", bare.Header("Location"));
            Assert.Equal($"Dimensions('{minimal}')", bare.Header("OData-EntityId"));
        }
        finally
        {
            Tm1.Send(Tm1.Request("DELETE", $"Dimensions('{full}')").WithCookie(cookie));
            Tm1.Send(Tm1.Request("DELETE", $"Dimensions('{minimal}')").WithCookie(cookie));
            Tm1.Close(cookie);
        }
    }

    /// <summary><c>odata.metadata=none</c> drops <c>@odata.etag</c> but still sends <c>@odata.context</c>.</summary>
    [Tm1Fact]
    public void DropsTheEtagButNotTheContextWithMetadataNone()
    {
        var cookie = Tm1.Login();
        var name = RawTm1.Scratch();
        try
        {
            Tm1.Send(Tm1.Request("POST", "Dimensions").WithCookie(cookie).WithBody(Dimension(name)));
            var minimal = RawTm1.Json(Tm1.Send(Tm1.Request("GET", $"Dimensions('{name}')").WithCookie(cookie)));
            var none = RawTm1.Json(
                Tm1.Send(Tm1.Request("GET", $"Dimensions('{name}')").WithCookie(cookie).WithHeader("Accept", "application/json;odata.metadata=none"))
            );
            Assert.True(minimal.TryGetProperty("@odata.etag", out _));
            Assert.False(none.TryGetProperty("@odata.etag", out _));
            Assert.True(none.TryGetProperty("@odata.context", out _));
        }
        finally
        {
            Tm1.Send(Tm1.Request("DELETE", $"Dimensions('{name}')").WithCookie(cookie));
            Tm1.Close(cookie);
        }
    }

    /// <summary>TM1 closes a kept-alive connection idle for about 90 seconds, and a request sent on
    /// it as it closes gets no response at all. The client's own handler retires idle connections
    /// after 60 seconds (<c>Connection.MaxIdle</c>) so that never happens. Takes up to 150 s.</summary>
    [Tm1Fact]
    public async Task ClosesAConnectionIdleForNinetySeconds()
    {
        var (url, user, password) = Live.Target();
        Socket? socket = null;
        var handler = new SocketsHttpHandler
        {
            UseCookies = false,
            MaxConnectionsPerServer = 1,
            PooledConnectionIdleTimeout = TimeSpan.FromMinutes(10),
            ConnectCallback = async (context, ct) =>
            {
                var s = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
                await s.ConnectAsync(context.DnsEndPoint, ct);
                socket = s;
                return new NetworkStream(s, ownsSocket: true);
            },
        };
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            handler.SslOptions.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        }
        using var http = new HttpClient(handler);
        using var request = new HttpRequestMessage(HttpMethod.Get, url + "Server?$select=Name");
        request.Headers.TryAddWithoutValidation("Authorization", RawTm1.Basic(user, password));
        using (var response = await http.SendAsync(request))
        {
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            await response.Content.ReadAsStringAsync();
        }
        var used = Stopwatch.StartNew();
        // Closed by TM1: readable with nothing to read
        while (!(socket!.Poll(1_000_000, SelectMode.SelectRead) && socket.Available == 0))
        {
            Assert.True(used.Elapsed < TimeSpan.FromSeconds(150), "TM1 didn't close the idle connection within 150 s");
        }
        Assert.InRange(used.Elapsed.TotalSeconds, 80, 100); // seconds idle before TM1 closed it
        Assert.True(Connection.MaxIdle < TimeSpan.FromSeconds(80));
    }

    private static string Dimension(string name) => $"{{\"Name\":\"{name}\",\"Hierarchies\":[{{\"Name\":\"{name}\"}}]}}";
}

/// <summary>The Java client's <c>Tm1ImpersonationBehaviorTest</c>: how TM1 itself treats
/// <c>TM1-Impersonate</c>, which the session and pool rely on.</summary>
public sealed class Tm1ImpersonationBehaviorTest : IDisposable
{
    private readonly Lazy<(RawTm1 Raw, string Admin, string Alice, string Bob)> _setUp = new(() =>
    {
        var raw = new RawTm1();
        var admin = raw.Login();
        var run = Guid.NewGuid().ToString("N")[..6];
        var alice = "zz_alice_" + run;
        var bob = "zz_bob_" + run;
        raw.CreateUser(admin, alice, "a");
        raw.CreateUser(admin, bob, "b");
        return (raw, admin, alice, bob);
    });

    private RawTm1 Tm1 => _setUp.Value.Raw;

    private string Admin => _setUp.Value.Admin;

    private string Alice => _setUp.Value.Alice;

    private string Bob => _setUp.Value.Bob;

    public void Dispose()
    {
        if (_setUp.IsValueCreated)
        {
            Tm1.DeleteUser(Admin, Alice);
            Tm1.DeleteUser(Admin, Bob);
            Tm1.Close(Admin);
            Tm1.Dispose();
        }
    }

    /// <summary>Impersonation is decided at login, and the header on later requests is ignored. So
    /// it's a session setting, and one identity means one session.</summary>
    [Tm1Fact]
    public void DecidesTheIdentityAtLoginAndIgnoresTheHeaderAfterwards()
    {
        var login = Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithBasic().WithHeader("TM1-Impersonate", Alice));
        Assert.Equal(HttpStatusCode.OK, login.Status);
        Assert.Equal(Alice, RawTm1.Json(login).GetProperty("Name").GetString());
        var session = RawTm1.CookieOf(login)!;
        try
        {
            Assert.Equal(Alice, Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(session)));
            Assert.Equal(Alice, Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(session).WithHeader("TM1-Impersonate", Bob))); // a different user on a later request
            var plain = Tm1.Login();
            Assert.Equal("Admin", Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(plain).WithHeader("TM1-Impersonate", Alice)), ignoreCase: true);
            Tm1.Close(plain);
        }
        finally
        {
            Tm1.Close(session);
        }
    }

    /// <summary>It also works when logging in the session a 401 opened, as the client's re-login does.</summary>
    [Tm1Fact]
    public void ImpersonatesOnALoginThatReusesThe401sSession()
    {
        var seed = RawTm1.CookieOf(Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name")))!;
        try
        {
            Assert.Equal(Alice, Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(seed).WithBasic().WithHeader("TM1-Impersonate", Alice)));
        }
        finally
        {
            Tm1.Close(seed);
        }
    }

    /// <summary>Impersonating a user that doesn't exist is a 400, and a non-administrator trying is a
    /// 403, both with plain-text bodies, which the client reads as the error message.</summary>
    [Tm1Fact]
    public void RefusesUnknownUsersAndNonAdministratorsWithPlainTextErrors()
    {
        var unknown = Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithBasic().WithHeader("TM1-Impersonate", "zz_nobody"));
        Assert.Equal(HttpStatusCode.BadRequest, unknown.Status);
        Assert.StartsWith("Error: Bad Request", unknown.Body);
        Assert.Contains("The user specified in the impersonation request does not exist.", unknown.Body);
        var left = RawTm1.CookieOf(unknown)!;
        Assert.Equal(HttpStatusCode.Unauthorized, Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(left)).Status); // the session a refused impersonation leaves
        var nonAdmin = Impersonate(Alice, "a", Bob);
        Assert.Equal(HttpStatusCode.Forbidden, nonAdmin.Status);
        Assert.StartsWith("Error: Forbidden", nonAdmin.Body);
        Assert.Contains("You need to be a (data-)administrator to be able to impersonate another user.", nonAdmin.Body);
    }

    /// <summary>Nobody can impersonate an administrator, not even another administrator.</summary>
    [Tm1Fact]
    public void RefusesToImpersonateAnyAdministrator()
    {
        var otherAdmin = Alice + "_adm";
        var dataAdmin = Alice + "_data";
        CreateUser(otherAdmin, "ADMIN");
        CreateUser(dataAdmin, "DataAdmin");
        try
        {
            foreach (
                var (user, password, target) in new[]
                {
                    (Tm1.User, Tm1.Password, otherAdmin),
                    (Tm1.User, Tm1.Password, "Admin"),
                    (dataAdmin, "p", otherAdmin),
                    (dataAdmin, "p", "Admin"),
                }
            )
            {
                var refused = Impersonate(user, password, target);
                Assert.True(refused.Status == HttpStatusCode.Forbidden, $"{user} impersonating {target}: {refused.Status}");
                Assert.Contains("You have insufficient rights to impersonate the user specified!", refused.Body);
            }
        }
        finally
        {
            Tm1.DeleteUser(Admin, otherAdmin);
            Tm1.DeleteUser(Admin, dataAdmin);
        }
    }

    /// <summary>Data administrators can impersonate ordinary users; security and operations
    /// administrators can't, with a different 403 message.</summary>
    [Tm1Fact]
    public void LetsDataAdministratorsImpersonateButNotOtherAdministratorRoles()
    {
        var dataAdmin = Alice + "_data";
        var securityAdmin = Alice + "_sec";
        var operationsAdmin = Alice + "_ops";
        CreateUser(dataAdmin, "DataAdmin");
        CreateUser(securityAdmin, "SecurityAdmin");
        CreateUser(operationsAdmin, "OperationsAdmin");
        try
        {
            var allowed = Impersonate(dataAdmin, "p", Bob);
            Assert.Equal(HttpStatusCode.OK, allowed.Status);
            if (RawTm1.CookieOf(allowed) is { } cookie)
            {
                Tm1.Close(cookie);
            }
            foreach (var role in new[] { securityAdmin, operationsAdmin })
            {
                var refused = Impersonate(role, "p", Bob);
                Assert.True(refused.Status == HttpStatusCode.Forbidden, role);
                Assert.Contains("You need to be a (data-)administrator to be able to impersonate another user.", refused.Body);
            }
        }
        finally
        {
            Tm1.DeleteUser(Admin, dataAdmin);
            Tm1.DeleteUser(Admin, securityAdmin);
            Tm1.DeleteUser(Admin, operationsAdmin);
        }
    }

    /// <summary>Impersonating a disabled user is a 401 with an empty body: exactly what wrong
    /// credentials look like. The client can't tell the two apart, so it reports both.</summary>
    [Tm1Fact]
    public void RefusesADisabledUserLikeWrongCredentials()
    {
        Tm1.Send(Tm1.Request("PATCH", $"Users('{Bob}')").WithCookie(Admin).WithBody("{\"Enabled\":false}"));
        var refused = Impersonate(Tm1.User, Tm1.Password, Bob);
        Assert.Equal(HttpStatusCode.Unauthorized, refused.Status);
        Assert.Empty(refused.Body);
    }

    /// <summary>An empty <c>TM1-Impersonate</c> means no impersonation. So the client must never send a blank name.</summary>
    [Tm1Fact]
    public void TreatsAnEmptyHeaderAsNoImpersonation()
    {
        var login = Impersonate(Tm1.User, Tm1.Password, "");
        try
        {
            Assert.Equal(HttpStatusCode.OK, login.Status);
            Assert.Equal("Admin", RawTm1.Json(login).GetProperty("Name").GetString(), ignoreCase: true);
        }
        finally
        {
            if (RawTm1.CookieOf(login) is { } cookie)
            {
                Tm1.Close(cookie);
            }
        }
    }

    /// <summary>User names match ignoring case and spaces, so the pool compares identities the same way.</summary>
    [Tm1Fact]
    public void MatchesUserNamesIgnoringCaseAndSpaces()
    {
        foreach (var written in new[] { Alice.ToUpperInvariant(), Alice.Replace("_", "_ ", StringComparison.Ordinal) })
        {
            var login = Impersonate(Tm1.User, Tm1.Password, written);
            try
            {
                Assert.True(login.Status == HttpStatusCode.OK, written);
                Assert.Equal(Alice, RawTm1.Json(login).GetProperty("Name").GetString());
            }
            finally
            {
                if (RawTm1.CookieOf(login) is { } cookie)
                {
                    Tm1.Close(cookie);
                }
            }
        }
    }

    /// <summary>A user whose name has non-ASCII characters can't be impersonated, however the header
    /// is encoded; the client refuses such names before sending anything.</summary>
    [Tm1Fact]
    public void CannotImpersonateUsersWithNonAsciiNames()
    {
        var accented = Alice + "_ë";
        Tm1.CreateUser(Admin, accented, "p");
        try
        {
            var utf8 = Encoding.Latin1.GetString(Encoding.UTF8.GetBytes(accented));
            foreach (var written in new[] { accented, utf8 })
            {
                var refused = Impersonate(Tm1.User, Tm1.Password, written);
                Assert.Equal(HttpStatusCode.BadRequest, refused.Status);
                Assert.Contains("The user specified in the impersonation request does not exist.", refused.Body);
            }
        }
        finally
        {
            Tm1.DeleteUser(Admin, accented);
        }
    }

    private RawTm1.RawResponse Impersonate(string user, string password, string target) =>
        Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithHeader("Authorization", RawTm1.Basic(user, password)).WithHeader("TM1-Impersonate", target));

    private void CreateUser(string name, string group) =>
        Tm1.Send(
            Tm1.Request("POST", "Users").WithCookie(Admin).WithBody($"{{\"Name\":\"{name}\",\"Password\":\"p\",\"Groups@odata.bind\":[\"Groups('{group}')\"]}}")
        );

    private string? Name(RawTm1.RawRequest request) => RawTm1.Json(Tm1.Send(request)).GetProperty("Name").GetString();
}

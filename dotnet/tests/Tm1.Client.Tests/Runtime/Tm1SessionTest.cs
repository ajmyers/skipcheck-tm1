// SPDX-License-Identifier: Apache-2.0

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
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The Java client's <c>Tm1SessionTest</c>: login, cookie, errors, reading, streaming,
/// transport failures and closing. (<c>observesLoginAndClose</c> is Micrometer's, which .NET doesn't have.)</summary>
public class Tm1SessionTest
{
    private static readonly Tm1Request<JsonElement> Server = Tm1Request.Get<JsonElement>(ODataPath.Of("Server"), ODataQuery.None().Select("Name"));

    private readonly ScriptedTm1 _tm1 = new ScriptedTm1().On("GET Server?$select=Name", _ => ScriptedTm1.Json(200, "{\"Name\":\"tm1\"}"));

    [Fact]
    public async Task GivesUpOnAnAuthenticatorThatNeverAnswers()
    {
        var session = _tm1.SessionWithOwnCredentials(b =>
            b.Authenticator(new Never()).Timeouts(Tm1Timeouts.Builder().Login(TimeSpan.FromMilliseconds(100)).Build())
        );

        var e = await Assert.ThrowsAsync<Tm1TransportException>(() => session.ExecuteAsync(Server).WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Equal(Tm1TransportException.Phase.Connect, e.TransportPhase);
        Assert.True(e.IsRetryable()); // nothing was sent to TM1
        Assert.False(e.MayHaveApplied());
        await session.CloseAsync().WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Empty(_tm1.Requests(ScriptedTm1.Login));
    }

    /// <summary>An authenticator that never supplies credentials, and doesn't stop when told to.</summary>
    private sealed class Never : Tm1Authenticator
    {
        public override async ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) =>
            await new TaskCompletionSource<string>().Task;

        public override string Describe() => "Never";
    }

    [Fact]
    public async Task LogsInOnceForConcurrentFirstRequestsAndSendsOnlyTheCookieAfterwards()
    {
        var login = new ScriptedTm1.Pending();
        _tm1.On(ScriptedTm1.Login, _ => login.Task);
        var session = _tm1.Session();
        var three = Task.WhenAll(session.ExecuteAsync(Server), session.ExecuteAsync(Server), session.ExecuteAsync(Server));
        await ScriptedTm1.Eventually(() => _tm1.Requests(ScriptedTm1.Login).Count == 1, "for the login");
        login.Answer(ScriptedTm1.Json(200, "{\"ID\":7}", "TM1SessionId=abc; Path=/api/; HttpOnly"));
        Assert.Equal(3, (await three).Length);
        var logins = _tm1.Requests(ScriptedTm1.Login);
        Assert.Single(logins);
        Assert.Equal("Basic YWRtaW46YXBwbGU=", logins[0].Authorization);
        var sent = _tm1.Requests("GET Server?$select=Name");
        Assert.Equal(3, sent.Count);
        Assert.All(sent, r =>
        {
            Assert.Equal("abc", r.Cookie);
            Assert.Null(r.Authorization); // no credentials after login
            Assert.Equal("4.0", r.Header("OData-Version"));
            Assert.Equal("4.0", r.Header("OData-MaxVersion"));
        });
        Assert.Equal(7L, await session.SessionIdAsync());
    }

    [Fact]
    public async Task FindsTheSessionCookieByNameAmongOthers()
    {
        _tm1.On(ScriptedTm1.Login, _ => ScriptedTm1.Json(200, "{\"ID\":3}", "other=1; Path=/", "TM1SessionId=right; Path=/api/; HttpOnly", "more=2"));
        await _tm1.Session().ExecuteAsync(Server);
        Assert.Equal("right", _tm1.Requests("GET Server?$select=Name")[0].Cookie);
    }

    [Fact]
    public async Task ReportsRefusedCredentialsAndCanTryAgainOnTheNextRequest()
    {
        _tm1.OnEach(
            ScriptedTm1.Login,
            _ => ScriptedTm1.Status(401, "TM1SessionId=anon; Path=/api/"),
            _ => ScriptedTm1.Json(200, "{\"ID\":2}", "TM1SessionId=ok; Path=/api/")
        );
        // With the refused-login back-off off; Tm1SessionImpersonationTest covers the back-off
        var session = _tm1.Session(b => b.Timeouts(Tm1Timeouts.Builder().RefusedLogin(TimeSpan.Zero).Build()));
        var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => session.ExecuteAsync(Server));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
        Assert.Empty(_tm1.Requests("GET Server?$select=Name")); // nothing sent without a session
        await session.ExecuteAsync(Server);
        Assert.Equal(2, _tm1.Requests(ScriptedTm1.Login).Count);
    }

    [Fact]
    public async Task MapsErrorResponsesThroughTheClassifier()
    {
        _tm1.On("GET Cubes('Nope')", _ =>
            ScriptedTm1.Json(404, "{\"error\":{\"code\":\"278\",\"message\":\"'Nope' can not be found in collection of type 'Cube'.\"}}")
        );
        var n = await Assert.ThrowsAsync<Tm1NotFoundException>(() =>
            _tm1.Session().ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("Nope")))
        );
        Assert.Equal(404, n.Status);
        Assert.Equal("278", n.Error!.Code);
        Assert.Equal(ScriptedTm1.Root + "Cubes('Nope')", n.Url);
    }

    [Fact]
    public async Task ReadsAPlainTextErrorBodyAsTheMessage()
    {
        _tm1.On("GET Cubes('x')", _ => ScriptedTm1.Text("Error: Bad Request\r\nSomething TM1 says in plain text.\r\n", 400));
        var a = await Assert.ThrowsAnyAsync<Tm1ApiException>(() => _tm1.Session().ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("x"))));
        Assert.Equal("Something TM1 says in plain text.", a.Error!.Message);
    }

    [Fact]
    public async Task ReadsTextAndNoContentAndSendsJsonBodies()
    {
        _tm1.On("GET Configuration/ProductVersion/$value", _ => ScriptedTm1.Text("11.8.03500.6"));
        _tm1.On("POST Cubes('Sales')/tm1.Lock", _ => ScriptedTm1.Status(204));
        var session = _tm1.Session();
        Assert.Equal("11.8.03500.6", await session.ExecuteAsync(Tm1Request.Text(ODataPath.Of("Configuration").Nav("ProductVersion").Value())));
        await session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Cubes").Key("Sales").Action("tm1.Lock"), null));
        Assert.Equal("text/plain, application/json", _tm1.Requests("GET Configuration/ProductVersion/$value")[0].Header("Accept"));
        Assert.StartsWith("application/json", _tm1.Requests("POST Cubes('Sales')/tm1.Lock")[0].Header("Content-Type"));
    }

    [Fact]
    public async Task StreamsCollectionsAcrossNextLinks()
    {
        _tm1.On("GET Dimensions?$select=Name", _ =>
            ScriptedTm1.Json(
                200,
                "{\"@odata.context\":\"$metadata#Dimensions(Name)\",\"value\":[{\"Name\":\"a\"},{\"Name\":\"b\"}],\"@odata.nextLink\":\"Dimensions?$skiptoken=2\"}"
            )
        );
        _tm1.On("GET Dimensions?$skiptoken=2", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"c\"}]}"));
        var names = await _tm1.Session()
            .StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Dimensions"), ODataQuery.None().Select("Name")))
            .Select(d => d.GetProperty("Name").GetString())
            .ToListAsync();
        Assert.Equal(["a", "b", "c"], names);
    }

    /// <summary>Java's <c>releasesBuffersItHasntReadWhenCancelled</c>, for what .NET holds instead of
    /// pooled buffers: the response, and with it the connection, of a body that was still arriving.</summary>
    [Fact]
    public async Task ReleasesAResponseItHasntReadWhenCancelled()
    {
        var arriving = new Tracked("{\"value\":[{\"Name\":\"a\"},", stalls: true);
        _tm1.On("GET Cubes", _ => arriving.Response());
        using var cancel = new CancellationTokenSource();
        var cubes = _tm1.Session().StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes")), cancel.Token).ToListAsync(cancel.Token).AsTask();
        await arriving.Started.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(arriving.Released);

        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cubes.WaitAsync(TimeSpan.FromSeconds(5)));
        await ScriptedTm1.Eventually(() => arriving.Released, "for the response to be released");
    }

    [Fact]
    public async Task ReleasesTheResponseOfAStreamThatFails()
    {
        var broken = new Tracked("{\"value\":[{\"Name\":\"a\"},");
        _tm1.On("GET Cubes", _ => broken.Response());
        await Assert.ThrowsAsync<Tm1ProtocolException>(async () =>
            await _tm1.Session().StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"))).ToListAsync()
        );
        Assert.True(broken.Released);
    }

    /// <summary>Java's <c>asksForBuffersOnlyAsItemsAreTaken</c>, for .NET's unit of reading, the page:
    /// it reads a page whole and releases its response, and asks for the next only when its items
    /// have been taken.</summary>
    [Fact]
    public async Task AsksForPagesOnlyAsItemsAreTaken()
    {
        var first = new Tracked("{\"value\":[{\"Name\":\"a\"},{\"Name\":\"b\"}],\"@odata.nextLink\":\"Cubes?$skiptoken=2\"}");
        _tm1.On("GET Cubes", _ => first.Response()).On("GET Cubes?$skiptoken=2", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"c\"}]}"));
        var taken = new List<string?>();
        await foreach (var cube in _tm1.Session().StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"))))
        {
            taken.Add(cube.GetProperty("Name").GetString());
            Assert.True(first.Released); // nothing is held while the caller works
            Assert.Empty(_tm1.Requests("GET Cubes?$skiptoken=2")); // no more than the items taken need
            if (taken.Count == 2)
            {
                break;
            }
        }
        Assert.Equal(["a", "b"], taken);
        Assert.Empty(_tm1.Requests("GET Cubes?$skiptoken=2")); // stopped early: the next page was never asked for
    }

    /// <summary>A response body that says when it's released, and can stall part-way like a slow connection.</summary>
    private sealed class Tracked(string start, bool stalls = false) : HttpContent
    {
        private readonly TaskCompletionSource _started = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task Started => _started.Task;

        public bool Released { get; private set; }

        public HttpResponseMessage Response()
        {
            Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = this };
        }

        protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context) =>
            SerializeToStreamAsync(stream, context, CancellationToken.None);

        protected override async Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context, CancellationToken cancellationToken)
        {
            await stream.WriteAsync(System.Text.Encoding.UTF8.GetBytes(start), cancellationToken);
            _started.TrySetResult();
            if (stalls)
            {
                await Task.Delay(Timeout.Infinite, cancellationToken);
            }
        }

        protected override bool TryComputeLength(out long length)
        {
            length = 0;
            return false;
        }

        protected override void Dispose(bool disposing)
        {
            Released = true;
            base.Dispose(disposing);
        }
    }

    [Fact]
    public async Task RefusesToStreamASingleResponse() =>
        await Assert.ThrowsAsync<ArgumentException>(async () => await _tm1.Session().StreamAsync(Server).ToListAsync());

    [Fact]
    public async Task RefusesToExecuteACollectionWithoutSendingIt()
    {
        await Assert.ThrowsAsync<ArgumentException>(() => _tm1.Session().ExecuteAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"))));
        Assert.Empty(_tm1.Requests());
    }

    [Fact]
    public async Task ReportsABodyThatIsntJsonAsTheCallersMistakeNotATransportFailure()
    {
        var e = await Assert.ThrowsAnyAsync<Exception>(() => _tm1.Session().ExecuteAsync(Tm1Request.Post(ODataPath.Of("Cubes"), new Unwritable())));
        Assert.IsType<ArgumentException>(e);
    }

    [Fact]
    public async Task PassesOtherFailuresThroughUnwrapped()
    {
        _tm1.Fail("GET Server?$select=Name", new InvalidOperationException("a bug, not the network"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => _tm1.Session().ExecuteAsync(Server));
    }

    [Fact]
    public async Task MapsConnectFailuresAndOtherTransportFailures()
    {
        _tm1.Fail(
            "GET Server?$select=Name",
            new HttpRequestException(HttpRequestError.ConnectionError, "refused", new SocketException((int)SocketError.ConnectionRefused))
        );
        _tm1.Fail("POST Processes('p')/tm1.ExecuteWithReturn", new IOException("Connection reset"));
        var session = _tm1.Session();
        var connect = await Assert.ThrowsAsync<Tm1TransportException>(() => session.ExecuteAsync(Server));
        Assert.Equal(Tm1TransportException.Phase.Connect, connect.TransportPhase);
        Assert.False(connect.MayHaveApplied());
        var response = await Assert.ThrowsAsync<Tm1TransportException>(() =>
            session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Processes").Key("p").Action("tm1.ExecuteWithReturn"), null))
        );
        Assert.Equal(Tm1TransportException.Phase.Response, response.TransportPhase);
        Assert.True(response.MayHaveApplied());
        Assert.False(response.IsRetryable()); // a POST that may have run
    }

    [Fact]
    public async Task SendsNothingOnCloseIfItNeverLoggedIn()
    {
        var session = _tm1.Session();
        await session.CloseAsync();
        Assert.Empty(_tm1.Requests());
        Assert.True(session.IsClosed());
        await Assert.ThrowsAsync<Tm1SessionClosedException>(() => session.ExecuteAsync(Server));
        Assert.Empty(_tm1.Requests());
    }

    [Fact]
    public async Task ClosesTheSessionOnceWithAJsonBodyAndRefusesRequestsAfterwards()
    {
        var session = _tm1.Session();
        await session.ExecuteAsync(Server);
        await Task.WhenAll(session.CloseAsync(), session.CloseAsync());
        await session.CloseAsync();
        var closes = _tm1.Requests(ScriptedTm1.Close);
        Assert.Single(closes);
        Assert.Equal("c1", closes[0].Cookie);
        Assert.StartsWith("application/json", closes[0].Header("Content-Type"));
        Assert.Equal("close", closes[0].Header("Connection")); // not reused after a close
        await Assert.ThrowsAsync<Tm1SessionClosedException>(() => session.ExecuteAsync(Server));
        Assert.Single(_tm1.Requests(ScriptedTm1.Login)); // a closed session doesn't log in again
    }

    [Fact]
    public async Task WaitsForALoginInProgressAndClosesIt()
    {
        var login = new ScriptedTm1.Pending();
        _tm1.On(ScriptedTm1.Login, _ => login.Task);
        var session = _tm1.Session();
        var first = session.ExecuteAsync(Server);
        await ScriptedTm1.Eventually(() => _tm1.Requests(ScriptedTm1.Login).Count == 1, "for the login");
        var close = session.CloseAsync();
        await Task.Delay(50);
        Assert.Empty(_tm1.Requests(ScriptedTm1.Close));
        login.Answer(ScriptedTm1.Json(200, "{\"ID\":5}", "TM1SessionId=late; Path=/api/"));
        await close;
        try
        {
            await first;
        }
        catch (Exception)
        {
            // Whether the request still went out on the closing session doesn't matter here
        }
        Assert.Single(_tm1.Requests(ScriptedTm1.Close));
        Assert.Equal("late", _tm1.Requests(ScriptedTm1.Close)[0].Cookie);
    }

    [Fact]
    public async Task FinishesALoginWhoseRequestWasCancelledSoCloseCanEndIt()
    {
        var login = new ScriptedTm1.Pending();
        _tm1.On(ScriptedTm1.Login, _ => login.Task);
        var session = _tm1.Session();
        using var cancel = new CancellationTokenSource();
        var request = session.ExecuteAsync(Server, cancel.Token);
        await ScriptedTm1.Eventually(() => _tm1.Requests(ScriptedTm1.Login).Count == 1, "for the login");
        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => request);
        login.Answer(ScriptedTm1.Json(200, "{\"ID\":6}", "TM1SessionId=orphan; Path=/api/"));
        await session.CloseAsync();
        Assert.Single(_tm1.Requests(ScriptedTm1.Close));
        Assert.Equal("orphan", _tm1.Requests(ScriptedTm1.Close)[0].Cookie);
    }

    [Fact]
    public async Task TreatsA401OnCloseAsAlreadyClosed()
    {
        _tm1.On(ScriptedTm1.Close, _ => ScriptedTm1.Status(401));
        var session = _tm1.Session();
        await session.ConnectAsync();
        await session.CloseAsync();
    }

    /// <summary>A body that can't be written as JSON: reading its property fails.</summary>
    private sealed class Unwritable
    {
        public string Name => throw new NotSupportedException("not readable");
    }
}

/// <summary>The Java client's <c>Tm1SessionReloginTest</c>: logging in again when TM1 drops the
/// session, with the cookie TM1 set with the 401. (<c>observesTheReloginWithItsReason</c> is Micrometer's.)</summary>
public class Tm1SessionReloginTest
{
    private const string CreateKey = "POST Dimensions";
    private static readonly Tm1Request<JsonElement> Create = Tm1Request.Post<JsonElement>(
        ODataPath.Of("Dimensions"),
        new Dictionary<string, object?> { ["Name"] = "d" }
    );
    private static readonly Tm1Request<JsonElement> Server = Tm1Request.Get<JsonElement>(ODataPath.Of("Server"));

    private readonly ScriptedTm1 _tm1 = new();

    [Fact]
    public async Task LogsInAgainWithTheCookieFromThe401AndReplaysThePostOnce()
    {
        _tm1.OnEach(CreateKey, _ => ScriptedTm1.Status(401, "TM1SessionId=fresh; Path=/api/; HttpOnly"), _ => ScriptedTm1.Json(201, "{\"Name\":\"d\"}"));
        var session = _tm1.Session();
        await session.ConnectAsync();
        await session.ExecuteAsync(Create);
        var logins = _tm1.Requests(ScriptedTm1.Login);
        Assert.Equal(2, logins.Count);
        Assert.Equal("fresh", logins[1].Cookie); // the re-login logs in the session TM1 opened for the 401
        Assert.NotNull(logins[1].Authorization);
        var creates = _tm1.Requests(CreateKey);
        Assert.Equal(2, creates.Count);
        Assert.Equal("c1", creates[0].Cookie);
        Assert.Equal("fresh", creates[1].Cookie);
        Assert.Null(creates[1].Authorization);
        Assert.Equal(2L, await session.SessionIdAsync());
    }

    [Fact]
    public async Task SharesOneReloginBetweenRequestsThatSawTheSameExpiry()
    {
        var relogin = new ScriptedTm1.Pending();
        var first = true;
        _tm1.On(ScriptedTm1.Login, r =>
        {
            if (first)
            {
                first = false;
                return Task.FromResult(ScriptedTm1.Json(200, "{\"ID\":1}", "TM1SessionId=old; Path=/api/"));
            }
            return relogin.Task;
        });
        var anon = 0;
        // Answered a moment later, so the three are all sent on the old session, as concurrent requests are
        _tm1.On("GET Server", async r =>
        {
            await Task.Delay(20);
            return r.Cookie == "old" ? ScriptedTm1.Status(401, "TM1SessionId=anon" + Interlocked.Increment(ref anon)) : ScriptedTm1.Json(200, "{}");
        });
        var session = _tm1.Session();
        await session.ConnectAsync();
        var three = Task.WhenAll(session.ExecuteAsync(Server), session.ExecuteAsync(Server), session.ExecuteAsync(Server));
        await ScriptedTm1.Eventually(() => _tm1.Requests("GET Server").Count == 3, "for the three requests");
        await Task.Delay(50);
        relogin.Answer(ScriptedTm1.Json(200, "{\"ID\":2}", "TM1SessionId=new; Path=/api/"));
        Assert.Equal(3, (await three).Length);
        Assert.Equal(2, _tm1.Requests(ScriptedTm1.Login).Count);
    }

    [Fact]
    public async Task GivesUpIfTheReplayIsRefusedTooWithoutLoggingInAgain()
    {
        _tm1.On("GET Server", _ => ScriptedTm1.Status(401, "TM1SessionId=anon; Path=/api/"));
        var a = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => _tm1.Session().ExecuteAsync(Server));
        Assert.Equal(Tm1ErrorKind.Unauthenticated, a.Kind);
        Assert.Equal(2, _tm1.Requests(ScriptedTm1.Login).Count);
        Assert.Equal(2, _tm1.Requests("GET Server").Count);
    }

    [Fact]
    public async Task ReportsRefusedCredentialsOnReloginWithoutReplaying()
    {
        _tm1.OnEach(ScriptedTm1.Login, _ => ScriptedTm1.Json(200, "{\"ID\":1}", "TM1SessionId=c1; Path=/api/"), _ => ScriptedTm1.Status(401));
        _tm1.On(CreateKey, _ => ScriptedTm1.Status(401, "TM1SessionId=anon; Path=/api/"));
        var session = _tm1.Session();
        await session.ConnectAsync();
        var a = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => session.ExecuteAsync(Create));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, a.Kind);
        Assert.Single(_tm1.Requests(CreateKey)); // the POST is sent once
    }

    [Fact]
    public async Task ClosesASessionLoggedInAgainRatherThanTheExpiredOne()
    {
        _tm1.OnEach(CreateKey, _ => ScriptedTm1.Status(401, "TM1SessionId=fresh; Path=/api/"), _ => ScriptedTm1.Json(201, "{}"));
        var session = _tm1.Session();
        await session.ConnectAsync();
        await session.ExecuteAsync(Create);
        await session.CloseAsync();
        Assert.Single(_tm1.Requests(ScriptedTm1.Close));
        Assert.Equal("fresh", _tm1.Requests(ScriptedTm1.Close)[0].Cookie);
    }

    [Fact]
    public async Task DoesntLogInAgainOnceClosed()
    {
        var response = new ScriptedTm1.Pending();
        _tm1.On("GET Server", _ => response.Task);
        var session = _tm1.Session();
        await session.ConnectAsync();
        var request = session.ExecuteAsync(Server);
        await ScriptedTm1.Eventually(() => _tm1.Requests("GET Server").Count == 1, "for the request");
        await session.CloseAsync();
        response.Answer(ScriptedTm1.Status(401, "TM1SessionId=anon; Path=/api/"));
        await Assert.ThrowsAsync<Tm1SessionClosedException>(() => request);
        Assert.Single(_tm1.Requests(ScriptedTm1.Login));
    }

    [Fact]
    public async Task AsksTheAuthenticatorForFreshCredentialsOnEachLogin()
    {
        var issued = 0;
        _tm1.OnEach(CreateKey, _ => ScriptedTm1.Status(401, "TM1SessionId=fresh; Path=/api/"), _ => ScriptedTm1.Json(201, "{}"));
        var session = _tm1.SessionWithOwnCredentials(b =>
            b.Authenticator(Tm1Authenticator.CamPassport(_ => ValueTask.FromResult("passport" + Interlocked.Increment(ref issued))))
        );
        await session.ConnectAsync();
        await session.ExecuteAsync(Create);
        var logins = _tm1.Requests(ScriptedTm1.Login);
        Assert.Equal("CAMPassport passport1", logins[0].Authorization);
        Assert.Equal("CAMPassport passport2", logins[1].Authorization);
    }

    [Fact]
    public async Task PassesAnAuthenticatorFailureThroughWithoutSendingALogin()
    {
        var noPassport = new InvalidOperationException("Cognos is down");
        var session = _tm1.SessionWithOwnCredentials(b => b.Authenticator(Tm1Authenticator.CamPassport(_ => ValueTask.FromException<string>(noPassport))));
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => session.ConnectAsync());
        Assert.Same(noPassport, e);
        Assert.Empty(_tm1.Requests(ScriptedTm1.Login));
    }
}

/// <summary>The Java client's <c>Tm1SessionImpersonationTest</c>: <c>TM1-Impersonate</c> at login,
/// the errors of a refused impersonation, and the refused-login back-off.</summary>
public class Tm1SessionImpersonationTest
{
    private static readonly Tm1Request<JsonElement> Server = Tm1Request.Get<JsonElement>(ODataPath.Of("Server"));

    private readonly ScriptedTm1 _tm1 = new ScriptedTm1().On("GET Server", _ => ScriptedTm1.Json(200, "{}"));
    private DateTimeOffset _now = DateTimeOffset.UnixEpoch;

    [Fact]
    public async Task ImpersonatesAtLoginOnly()
    {
        await _tm1.Session(b => b.Impersonate("alice")).ExecuteAsync(Server);
        Assert.Equal("alice", _tm1.Requests(ScriptedTm1.Login)[0].Header("TM1-Impersonate"));
        Assert.Null(_tm1.Requests("GET Server")[0].Header("TM1-Impersonate"));
    }

    [Fact]
    public void RefusesNamesTm1WouldMisread()
    {
        Assert.Contains("no impersonation", Assert.Throws<ArgumentException>(() => Tm1Session.Builder().Impersonate("")).Message);
        Assert.Throws<ArgumentException>(() => Tm1Session.Builder().Impersonate("  "));
        Assert.Contains("control character", Assert.Throws<ArgumentException>(() => Tm1Session.Builder().Impersonate("a\nb")).Message);
        Tm1Session.Builder().Impersonate("carol smith");
    }

    /// <summary>.NET refuses a header value outside ASCII from a handler that isn't told its encoding,
    /// so the login fails before anything is sent, rather than impersonating the wrong name.</summary>
    [Fact]
    public async Task FailsTheLoginThroughAHandlerThatIsntToldTheEncoding()
    {
        using var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        var port = ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
        using var handler = new System.Net.Http.SocketsHttpHandler { UseCookies = false };
        var session = Tm1Session.Builder().BaseUrl($"http://127.0.0.1:{port}/api/v1/").BasicAuth("admin", "").Impersonate("zoë").HttpHandler(handler).Build();
        var e = await Assert.ThrowsAnyAsync<Exception>(() => session.ExecuteAsync(Server));
        var causes = new List<Exception>();
        for (Exception? c = e; c is not null; c = c.InnerException)
        {
            causes.Add(c);
        }
        Assert.Contains(causes, c => c is System.Net.Http.HttpRequestException && c.Message.Contains("ASCII", StringComparison.Ordinal));
    }

    /// <summary>Over a real socket, as the scripted TM1 is a handler and never sees the bytes: with the
    /// client's own handler, and with one of the caller's that's told the encoding.</summary>
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task SendsANameOutsideAsciiAsItsUtf8Bytes(bool ownHandler)
    {
        using var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        var heads = new System.Collections.Concurrent.ConcurrentQueue<byte[]>();
        using var stop = new CancellationTokenSource();
        var serving = Task.Run(async () =>
        {
            // Answers every request alike, as a login that opens session 1
            var answer = System.Text.Encoding.ASCII.GetBytes(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: TM1SessionId=c1; Path=/api/\r\nContent-Length: 8\r\n\r\n{\"ID\":1}"
            );
            try
            {
                while (true)
                {
                    using var socket = await listener.AcceptTcpClientAsync(stop.Token);
                    var stream = socket.GetStream();
                    var head = new System.IO.MemoryStream();
                    var buffer = new byte[4096];
                    int read;
                    while ((read = await stream.ReadAsync(buffer, stop.Token)) > 0)
                    {
                        head.Write(buffer, 0, read);
                        if (System.Text.Encoding.Latin1.GetString(head.ToArray()).EndsWith("\r\n\r\n", StringComparison.Ordinal))
                        {
                            heads.Enqueue(head.ToArray());
                            head.SetLength(0);
                            await stream.WriteAsync(answer, stop.Token);
                        }
                    }
                }
            }
            catch (OperationCanceledException)
            {
                // the test is over
            }
        });
        var port = ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
        var settings = Tm1Session
            .Builder()
            .BaseUrl($"http://127.0.0.1:{port}/api/v1/")
            .BasicAuth("admin", "")
            .CheckCapabilities(false)
            .Impersonate("zoë")
            .SessionContext("café");
        using var handler = new System.Net.Http.SocketsHttpHandler { UseCookies = false, RequestHeaderEncodingSelector = Tm1Session.Utf8Headers };
        var session = (ownHandler ? settings : settings.HttpHandler(handler)).Build();
        await session.ExecuteAsync(Server);

        Assert.True(heads.TryPeek(out var login));
        Assert.True(login.AsSpan().IndexOf([.. "TM1-Impersonate: "u8, .. "zoë"u8, .. "\r\n"u8]) >= 0, System.Text.Encoding.Latin1.GetString(login));
        Assert.True(login.AsSpan().IndexOf([.. "TM1-SessionContext: "u8, .. "café"u8, .. "\r\n"u8]) >= 0, System.Text.Encoding.Latin1.GetString(login));
        await stop.CancelAsync();
        await serving;
    }

    [Fact]
    public async Task ReportsAnUnknownUser()
    {
        _tm1.On(ScriptedTm1.Login, _ => ScriptedTm1.Text("Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n", 400));
        var a = await Assert.ThrowsAsync<Tm1BadRequestException>(() => _tm1.Session(b => b.Impersonate("nobody")).ExecuteAsync(Server));
        Assert.Equal(Tm1ErrorKind.UnknownUser, a.Kind);
    }

    [Fact]
    public async Task ReportsARefusedImpersonationWhateverTheWording()
    {
        _tm1.On(ScriptedTm1.Login, _ => ScriptedTm1.Text("Error: Forbidden\r\nSome other version's words.\r\n", 403));
        var a = await Assert.ThrowsAsync<Tm1ForbiddenException>(() => _tm1.Session(b => b.Impersonate("Admin")).ExecuteAsync(Server));
        Assert.Equal(Tm1ErrorKind.ImpersonationRefused, a.Kind);
    }

    [Fact]
    public async Task NamesBothCausesOfA401WhenImpersonating()
    {
        _tm1.On(ScriptedTm1.Login, _ => ScriptedTm1.Status(401));
        var a = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => _tm1.Session(b => b.Impersonate("bob")).ExecuteAsync(Server));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, a.Kind);
        Assert.Contains("bob", a.Message);
        Assert.Contains("disabled", a.Message);
    }

    [Fact]
    public async Task RemembersARefusedLoginAndFailsFastUntilTheBackOffEnds()
    {
        _tm1.OnEach(
            ScriptedTm1.Login,
            _ => ScriptedTm1.Status(401),
            _ => ScriptedTm1.Status(401),
            _ => ScriptedTm1.Json(200, "{\"ID\":9}", "TM1SessionId=ok; Path=/api/")
        );
        var session = Session(Tm1Timeouts.Builder().RefusedLogin(TimeSpan.FromSeconds(30)).Build());
        await Assert.ThrowsAsync<Tm1AuthenticationException>(() => session.ExecuteAsync(Server));
        _now += TimeSpan.FromSeconds(29);
        await Assert.ThrowsAsync<Tm1AuthenticationException>(() => session.ExecuteAsync(Server));
        Assert.Single(_tm1.Requests(ScriptedTm1.Login)); // within the back-off: no new login
        _now += TimeSpan.FromSeconds(2);
        await Assert.ThrowsAsync<Tm1AuthenticationException>(() => session.ExecuteAsync(Server));
        Assert.Equal(2, _tm1.Requests(ScriptedTm1.Login).Count); // after the back-off: tried again
        _now += TimeSpan.FromSeconds(31);
        await session.ExecuteAsync(Server);
    }

    [Fact]
    public async Task RetriesAtOnceAfterAFailureThatIsntARefusal()
    {
        var first = true;
        _tm1.On(ScriptedTm1.Login, _ =>
        {
            if (first)
            {
                first = false;
                return Task.FromException<System.Net.Http.HttpResponseMessage>(new IOException("Connection reset"));
            }
            return Task.FromResult(ScriptedTm1.Json(200, "{\"ID\":9}", "TM1SessionId=ok; Path=/api/"));
        });
        var session = Session(Tm1Timeouts.Defaults);
        await Assert.ThrowsAsync<Tm1TransportException>(() => session.ExecuteAsync(Server));
        await session.ExecuteAsync(Server);
    }

    [Fact]
    public async Task TriesAgainAtOnceWhenTheBackOffIsZero()
    {
        _tm1.OnEach(ScriptedTm1.Login, _ => ScriptedTm1.Status(401), _ => ScriptedTm1.Json(200, "{\"ID\":9}", "TM1SessionId=ok; Path=/api/"));
        var session = Session(Tm1Timeouts.Builder().RefusedLogin(TimeSpan.Zero).Build());
        await Assert.ThrowsAnyAsync<Tm1ApiException>(() => session.ExecuteAsync(Server));
        await session.ExecuteAsync(Server);
    }

    private Tm1Session Session(Tm1Timeouts timeouts) => _tm1.Session(b => b.Timeouts(timeouts).Clock(() => _now));
}

/// <summary>The Java client's <c>Tm1SessionViewsTest</c>: sandbox views sharing one login, and the session context.</summary>
public class Tm1SessionViewsTest
{
    private static readonly Tm1Request<JsonElement> Cube = Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("Sales"), ODataQuery.None().Select("Name"));

    private readonly ScriptedTm1 _tm1 = new ScriptedTm1()
        .On("GET Cubes('Sales')?$select=Name", _ => ScriptedTm1.Json(200, "{}"))
        .On("GET Cubes('Sales')?$select=Name&!sandbox=What-if%201", _ => ScriptedTm1.Json(200, "{}"))
        .On("GET Cubes('Sales')?$select=Name&!sandbox=Mine", _ => ScriptedTm1.Json(200, "{}"));

    [Fact]
    public async Task AddsTheSandboxToEveryRequestOfTheViewAndSharesTheLogin()
    {
        var @base = _tm1.Session();
        var whatIf = @base.WithSandbox("What-if 1");
        await whatIf.ExecuteAsync(Cube);
        await @base.ExecuteAsync(Cube);
        await whatIf.WithoutSandbox().ExecuteAsync(Cube);
        Assert.Single(_tm1.Requests("GET Cubes('Sales')?$select=Name&!sandbox=What-if%201"));
        Assert.Equal(2, _tm1.Requests("GET Cubes('Sales')?$select=Name").Count);
        Assert.Single(_tm1.Requests(ScriptedTm1.Login)); // views share one login
        Assert.Equal("What-if 1", whatIf.Sandbox());
        Assert.Null(@base.Sandbox());
        Assert.Equal("Tm1Session[" + ScriptedTm1.Root + ", sandbox=What-if 1]", whatIf.ToString());
    }

    [Fact]
    public async Task KeepsASandboxTheRequestSetsItself()
    {
        var whatIf = _tm1.Session().WithSandbox("What-if 1");
        await whatIf.ExecuteAsync(Cube.WithQuery(Cube.Query.Option("!sandbox", "Mine")));
        Assert.Single(_tm1.Requests("GET Cubes('Sales')?$select=Name&!sandbox=Mine"));
    }

    [Fact]
    public async Task ClosingAViewClosesTheSharedSession()
    {
        var @base = _tm1.Session();
        var whatIf = @base.WithSandbox("x");
        await @base.ConnectAsync();
        await whatIf.CloseAsync();
        Assert.True(@base.IsClosed());
        Assert.Single(_tm1.Requests(ScriptedTm1.Close));
    }

    [Fact]
    public async Task SendsTheSessionContextWithTheLoginOnly()
    {
        await _tm1.Session(b => b.SessionContext("planning-app")).ExecuteAsync(Cube);
        Assert.Equal("planning-app", _tm1.Requests(ScriptedTm1.Login)[0].Header("TM1-SessionContext"));
        Assert.Null(_tm1.Requests("GET Cubes('Sales')?$select=Name")[0].Header("TM1-SessionContext"));
    }

    [Fact]
    public void RefusesBlankSandboxesAndOverlongContexts()
    {
        Assert.Throws<ArgumentException>(() => _tm1.Session().WithSandbox(" "));
        Assert.Throws<ArgumentException>(() => Tm1Session.Builder().SessionContext(new string('x', 251)));
    }
}

/// <summary>The Java client's <c>Tm1SessionBatchTest</c>: a JSON <c>$batch</c> of queued requests.</summary>
public class Tm1SessionBatchTest
{
    private const string BatchKey = "POST $batch";

    [Fact]
    public async Task SendsQueuedRequestsTogetherAndHandsEachItsResult()
    {
        var tm1 = new ScriptedTm1().On(BatchKey, _ =>
            ScriptedTm1.Json(
                200,
                """
                {"responses":[
                 {"id":"1","status":200,"body":{"Name":"Sales"}},
                 {"id":"2","status":404,"body":{"error":{"code":"278","message":"'Nope' can not be found"}}},
                 {"id":"3","status":200,"body":{"value":[{"Name":"a"},{"Name":"b"}]}},
                 {"id":"4","status":204}
                ]}
                """
            )
        );
        var batch = tm1.Session().Batch();
        var queued = batch.Session;
        var sales = queued.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("Sales")));
        var nope = queued.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("Nope")));
        var listed = queued
            .StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes")))
            .Select(n => n.GetProperty("Name").GetString())
            .ToListAsync()
            .AsTask();
        await ScriptedTm1.Eventually(() => batch.Size() == 3, "for the stream to queue");
        var deleted = queued.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Cubes").Key("Old")));
        Assert.Equal(4, batch.Size());
        Assert.Empty(tm1.Requests(BatchKey)); // nothing sent before run
        await batch.RunAsync();
        Assert.Equal("Sales", (await sales).GetProperty("Name").GetString());
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => nope);
        await deleted;
        Assert.Equal(["a", "b"], await listed);
        Assert.Single(tm1.Requests(BatchKey)); // one $batch
    }

    [Fact]
    public async Task RefusesCallsAfterItRan()
    {
        var tm1 = new ScriptedTm1().On(BatchKey, _ => ScriptedTm1.Json(200, "{\"responses\":[]}"));
        var batch = tm1.Session().Batch();
        await batch.RunAsync();
        await Assert.ThrowsAsync<InvalidOperationException>(() => batch.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Cubes").Key("x"))));
        await Assert.ThrowsAsync<InvalidOperationException>(() => batch.RunAsync());
    }

    [Fact]
    public async Task RefusesACallWhoseBodyCantBeWrittenAndStillRunsTheRest()
    {
        var tm1 = new ScriptedTm1().On(BatchKey, _ => ScriptedTm1.Json(200, "{\"responses\":[{\"id\":\"1\",\"status\":204}]}"));
        var batch = tm1.Session().Batch();
        var deleted = batch.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Cubes").Key("Old")));
        var unwritable = batch.Session.ExecuteAsync(Tm1Request.Post<JsonElement>(ODataPath.Of("Cubes"), new Unwritable()));

        await Assert.ThrowsAsync<ArgumentException>(() => unwritable.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Equal(1, batch.Size()); // never queued
        await batch.RunAsync().WaitAsync(TimeSpan.FromSeconds(5));
        await deleted.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(1, Assert.Single(tm1.Requests(BatchKey)).Json.GetProperty("requests").GetArrayLength());
    }

    [Fact]
    public async Task FailsEveryCallWhenTm1AnswersTheBatchWithNoBody()
    {
        var tm1 = new ScriptedTm1().On(BatchKey, _ => ScriptedTm1.Status(200));
        var batch = tm1.Session().Batch();
        var first = batch.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Cubes").Key("a")));
        var second = batch.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Cubes").Key("b")));

        await batch.RunAsync().WaitAsync(TimeSpan.FromSeconds(5));
        await Assert.ThrowsAsync<Tm1ProtocolException>(() => first.WaitAsync(TimeSpan.FromSeconds(5)));
        await Assert.ThrowsAsync<Tm1ProtocolException>(() => second.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    /// <summary>A body that can't be written as JSON: reading its property fails.</summary>
    private sealed class Unwritable
    {
        public string Name => throw new NotSupportedException("not readable");
    }

    [Fact]
    public async Task FollowsANextLinkInAnAnswer()
    {
        var tm1 = new ScriptedTm1()
            .On(BatchKey, _ =>
                ScriptedTm1.Json(
                    200,
                    """
                    {"responses":[
                     {"id":"1","status":200,"body":{"value":[{"Name":"a"}],"@odata.nextLink":"Cubes?$skiptoken=2"}}
                    ]}
                    """
                )
            )
            .On("GET Cubes?$skiptoken=2", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"b\"}]}"));
        var batch = tm1.Session().Batch();
        var names = batch
            .Session.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes")))
            .Select(cube => cube.GetProperty("Name").GetString())
            .ToListAsync()
            .AsTask();

        await batch.RunAsync().WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(["a", "b"], await names.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public async Task SplitsABigQueue()
    {
        var tm1 = new ScriptedTm1().On(BatchKey, _ => ScriptedTm1.Json(200, "{\"responses\":[]}"));
        var batch = tm1.Session().Batch();
        var calls = Enumerable.Range(0, 5).Select(i => batch.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Cubes").Key("c" + i)))).ToList();
        await batch.RunAsync(2);
        Assert.Equal(3, tm1.Requests(BatchKey).Count);
        foreach (var call in calls)
        {
            try
            {
                await call;
            }
            catch (Tm1Exception)
            {
                // The fake answers no request in the batch
            }
        }
    }
}

/// <summary>The Java client's <c>Tm1SessionAsyncTest</c>: <c>Prefer: respond-async</c>, polling
/// the monitor, and reading its <c>application/http</c> result.</summary>
public class Tm1SessionAsyncTest
{
    private const string Run = "POST Processes('p')/tm1.ExecuteWithReturn";
    private const string Monitor = "GET _async('a1')";
    private static readonly Tm1Request<JsonElement> Request = Tm1Request.Post<JsonElement>(
        ODataPath.Of("Processes").Key("p").Action("tm1.ExecuteWithReturn"),
        null
    );

    private static HttpResponseMessage Accepted()
    {
        var response = ScriptedTm1.Status(202);
        response.Headers.TryAddWithoutValidation("Location", "../_async('a1')");
        return response;
    }

    private static HttpResponseMessage Done(string statusLine, string body)
    {
        var message = $"HTTP/1.1 {statusLine}\r\nContent-Type: application/json\r\nOData-Version: 4.0\r\n\r\n{body}";
        var response = ScriptedTm1.Text(message);
        response.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/http");
        return response;
    }

    [Fact]
    public async Task PollsTheMonitorUntilTheResultIsThere()
    {
        var tm1 = new ScriptedTm1()
            .On(Run, _ => Accepted())
            .OnEach(
                Monitor,
                _ => ScriptedTm1.Status(202),
                _ => ScriptedTm1.Status(202),
                _ => Done("201 Created", "{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}")
            );
        var result = await tm1.Session().WithAsync(TimeSpan.FromMilliseconds(10)).ExecuteAsync(Request);
        Assert.Equal("CompletedSuccessfully", result.GetProperty("ProcessExecuteStatusCode").GetString());
        Assert.Equal("respond-async", Assert.Single(tm1.Requests(Run)).Header("Prefer"));
        Assert.Equal(3, tm1.Requests(Monitor).Count);
        Assert.Empty(tm1.Requests("DELETE _async('a1')")); // not deleted once done
    }

    [Fact]
    public async Task RaisesTheErrorTheWorkEndedWith()
    {
        var tm1 = new ScriptedTm1()
            .On(Run, _ => Accepted())
            .On(Monitor, _ => Done("500 Internal Server Error", "{\"error\":{\"code\":\"174\",\"message\":\"TM1UserException: Cancel\"}}"));
        var e = await Assert.ThrowsAsync<Tm1ServerException>(() => tm1.Session().WithAsync(TimeSpan.FromMilliseconds(10)).ExecuteAsync(Request));
        Assert.Contains("Cancel", e.Message);
    }

    [Fact]
    public async Task ReadsAnAnswerTm1GaveAtOnce()
    {
        var tm1 = new ScriptedTm1().On(Run, _ => ScriptedTm1.Json(201, "{\"ProcessExecuteStatusCode\":\"Aborted\"}"));
        var result = await tm1.Session().WithAsync(TimeSpan.FromMilliseconds(10)).ExecuteAsync(Request);
        Assert.Equal("Aborted", result.GetProperty("ProcessExecuteStatusCode").GetString());
        Assert.Single(tm1.Requests(Run)); // sent once
    }

    [Fact]
    public async Task DeletesTheMonitorWhenCancelled()
    {
        var tm1 = new ScriptedTm1().On(Run, _ => Accepted()).On(Monitor, _ => ScriptedTm1.Status(202)).On("DELETE _async('a1')", _ => ScriptedTm1.Status(204));
        using var cancel = new CancellationTokenSource();
        var running = tm1.Session().WithAsync(TimeSpan.FromMilliseconds(10)).ExecuteAsync(Request, cancel.Token);
        await Task.Delay(200);
        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => running);
        Assert.NotEmpty(tm1.Requests(Monitor));
        Assert.Single(tm1.Requests("DELETE _async('a1')"));
    }

    /// <summary>Java's codecs refused to buffer more than 256 KB; .NET reads a body whole, whatever its size, which this pins.</summary>
    [Fact]
    public async Task ReadsAResultBiggerThanTheCodecsBufferLimit()
    {
        var big = new string('x', 300_000);
        var tm1 = new ScriptedTm1().On(Run, _ => Accepted()).On(Monitor, _ => Done("200 OK", "{\"Text\":\"" + big + "\"}"));
        var result = await tm1.Session().WithAsync(TimeSpan.FromMilliseconds(10)).ExecuteAsync(Request);
        Assert.Equal(300_000, result.GetProperty("Text").GetString()!.Length);
    }

    [Fact]
    public async Task ReadsABigCollectionTm1GaveAtOnce()
    {
        var big = new string('x', 300_000);
        var tm1 = new ScriptedTm1().On("GET Cubes", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"" + big + "\"}]}"));
        var cubes = await tm1.Session().WithAsync(TimeSpan.FromMilliseconds(10)).StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"))).ToListAsync();
        Assert.Equal(300_000, Assert.Single(cubes).GetProperty("Name").GetString()!.Length);
    }

    [Fact]
    public async Task FollowsANextLinkInAResult()
    {
        var tm1 = new ScriptedTm1()
            .On("GET Cubes", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Cubes?$skiptoken=2\"}"))
            .On("GET Cubes?$skiptoken=2", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"b\"}]}"));
        var names = await tm1.Session()
            .WithAsync(TimeSpan.FromMilliseconds(10))
            .StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes")))
            .Select(cube => cube.GetProperty("Name").GetString())
            .ToListAsync();
        Assert.Equal(["a", "b"], names);
        Assert.Equal("respond-async", Assert.Single(tm1.Requests("GET Cubes")).Header("Prefer"));
        Assert.Null(Assert.Single(tm1.Requests("GET Cubes?$skiptoken=2")).Header("Prefer")); // the rest with ordinary requests
    }

    [Fact]
    public async Task ReadsABigErrorBody()
    {
        var big = new string('x', 300_000);
        var tm1 = new ScriptedTm1().On(Run, _ => ScriptedTm1.Json(500, "{\"error\":{\"code\":\"174\",\"message\":\"" + big + "\"}}"));
        var e = await Assert.ThrowsAsync<Tm1ServerException>(() => tm1.Session().ExecuteAsync(Request));
        Assert.Contains(big, e.Message);
    }

    [Fact]
    public void UnwrapsAnApplicationHttpMessage()
    {
        var result = Connection.AsyncResult(System.Text.Encoding.ASCII.GetBytes("HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\n{}"));
        Assert.Equal(201, result.Status);
        Assert.Equal("{}", System.Text.Encoding.ASCII.GetString(result.Body));
    }

    [Fact]
    public void DecompressesAGzippedInnerBody()
    {
        using var zipped = new MemoryStream();
        using (var gzip = new System.IO.Compression.GZipStream(zipped, System.IO.Compression.CompressionLevel.Optimal, leaveOpen: true))
        {
            gzip.Write(System.Text.Encoding.UTF8.GetBytes("{\"Name\":\"tm1\"}"));
        }
        var head = System.Text.Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: application/json\r\n\r\n");
        var message = head.Concat(zipped.ToArray()).ToArray();
        Assert.Equal("{\"Name\":\"tm1\"}", System.Text.Encoding.UTF8.GetString(Connection.AsyncResult(message).Body));
    }
}

/// <summary>The Java client's <c>Tm1SessionCapabilitiesTest</c>: the version and <c>$metadata</c>,
/// read once, and requests checked against them.</summary>
public class Tm1SessionCapabilitiesTest
{
    private const string VersionKey = "GET Server?$select=ProductVersion";
    private const string MetadataKey = "GET $metadata";

    private readonly ScriptedTm1 _tm1 = new ScriptedTm1()
        .On(VersionKey, _ => ScriptedTm1.Json(200, "{\"ProductVersion\":\"11.8.03500.6\"}"))
        .On(MetadataKey, _ => Xml(File.ReadAllText(Repo.Snapshot)))
        .On("GET Cubes('Sales')/tm1.DimensionsStorageOrder()", _ => ScriptedTm1.Json(200, "{}"));

    [Fact]
    public async Task ReadsTheVersionOnceInsideTheSession()
    {
        var session = _tm1.Session();
        Assert.Equal(Version.Tm1Version.Parse("11.8.03500.6"), await session.VersionAsync());
        await session.VersionAsync();
        Assert.Single(_tm1.Requests(VersionKey));
        Assert.NotNull(_tm1.Requests(VersionKey)[0].Cookie); // never an anonymous read
    }

    [Fact]
    public async Task FetchesMetadataOncePerServerVersionForEverySessionWithTheSameSettings()
    {
        var settings = _tm1.Builder().BasicAuth("admin", "apple");
        var first = settings.Build();
        var second = settings.Build();
        var c = await first.CapabilitiesAsync();
        Assert.Equal("11.8.35", c.SchemaVersion);
        Assert.Equal(Version.Tm1Version.Parse("11.8.03500.6"), c.ProductVersion);
        await second.CapabilitiesAsync();
        Assert.Single(_tm1.Requests(MetadataKey));
        Assert.Equal("application/xml", _tm1.Requests(MetadataKey)[0].Header("Accept"));
    }

    [Fact]
    public async Task SendsARequestWhoseRequirementTheServerMeets()
    {
        var order = Tm1Request
            .Get<JsonElement>(ODataPath.Of("Cubes").Key("Sales").Function("tm1.DimensionsStorageOrder", new Dictionary<string, object?>()))
            .Requires(Version.Tm1Capability.BoundOperation("tm1.Cube", "tm1.DimensionsStorageOrder").Since("11.4.0"));
        await _tm1.Session().ExecuteAsync(order);
    }

    [Fact]
    public async Task RefusesARequestTheServerCantServeWithoutSendingIt()
    {
        var bananas = Tm1Request.List<JsonElement>(ODataPath.Of("Bananas")).Requires(Version.Tm1Capability.EntitySet("Bananas").Since("99.0"));
        var u = await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(async () => await _tm1.Session().StreamAsync(bananas).ToListAsync());
        Assert.Equal("entity set Bananas", u.Missing);
        Assert.Contains("added in schema 99.0", u.Message);
        Assert.Contains("reports schema 11.8.35", u.Message);
        Assert.Empty(_tm1.Requests("GET Bananas"));
    }

    [Fact]
    public async Task RefusesARequestTheServerCantServeInABatchAndStillSendsTheRest()
    {
        // Answers each request it's sent, under the ID it came with
        _tm1.On("POST $batch", r =>
            ScriptedTm1.Json(
                200,
                "{\"responses\":["
                    + string.Join(
                        ",",
                        r.Json.GetProperty("requests")
                            .EnumerateArray()
                            .Select(q => $"{{\"id\":\"{q.GetProperty("id").GetString()}\",\"status\":200,\"body\":{{}}}}")
                    )
                    + "]}"
            )
        );
        var batch = _tm1.Session().Batch();
        var bananas = batch
            .Session.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Bananas")).Requires(Version.Tm1Capability.EntitySet("Bananas").Since("99.0")))
            .ToListAsync()
            .AsTask();
        var server = batch.Session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Server")));
        Assert.Equal(2, batch.Size()); // checked at the run, not when queued
        Assert.Empty(_tm1.Requests()); // so queueing sends nothing

        await batch.RunAsync().WaitAsync(TimeSpan.FromSeconds(5));
        await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(() => bananas.WaitAsync(TimeSpan.FromSeconds(5)));
        await server.WaitAsync(TimeSpan.FromSeconds(5));
        var sent = Assert.Single(_tm1.Requests("POST $batch")).Text;
        Assert.Contains("Server", sent);
        Assert.DoesNotContain("Bananas", sent);
    }

    private const string CheckFeedersKey = "POST Cubes('Sales')/tm1.CheckFeeders";

    /// <summary>A server whose CheckFeeders has no Selection, as before schema 11.8.32.</summary>
    private void WithoutSelection()
    {
        var snapshot = File.ReadAllText(Repo.Snapshot);
        var metadata = new System.Text.RegularExpressions.Regex(
            "(<Action Name=\"CheckFeeders\".*?)<Parameter Name=\"Selection\".*?</Parameter>",
            System.Text.RegularExpressions.RegexOptions.Singleline
        ).Replace(snapshot, "$1", 1);
        Assert.NotEqual(snapshot, metadata);
        _tm1.On(MetadataKey, _ => Xml(metadata));
    }

    private static Tm1Request<JsonElement> CheckFeeders(object selection) =>
        Tm1Request
            .PostList<JsonElement>(
                ODataPath.Of("Cubes").Key("Sales").Action("tm1.CheckFeeders"),
                new Dictionary<string, object?>
                {
                    ["Tuple@odata.bind"] = new List<string> { "Dimensions('Region')/Hierarchies('Region')/Elements('Total')" },
                    ["Selection"] = selection,
                }
            )
            .Requires(Model.Tm1Operations.CubeCheckFeeders)
            .WithParameterDefault("Selection", "Unfed");

    [Fact]
    public void KnowsTheParametersACallerPassesForEachKindOfOperation()
    {
        var c = Version.Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot));
        var checkFeeders = Model.Tm1Operations.CubeCheckFeeders.Capability();
        Assert.True(c.SupportsParameter(checkFeeders, "Selection"));
        Assert.False(c.SupportsParameter(checkFeeders, "Cube")); // the binding parameter isn't one a caller passes
        // The import Update and the actions Cellset/Update and Cube/Update share a name: only the
        // import's own parameters count
        Assert.True(c.SupportsParameter(Version.Tm1Capability.UnboundOperation("Update"), "Cells"));
        Assert.False(c.SupportsParameter(Version.Tm1Capability.UnboundOperation("Update"), "BeginOrdinal"));
    }

    [Fact]
    public async Task ChecksFeedersWithUnfedOnAServerWithoutSelectionAndRefusesTheRest()
    {
        WithoutSelection();
        _tm1.On(CheckFeedersKey, _ => ScriptedTm1.Json(200, "{\"value\":[]}"));
        var cubes = new Skipcheck.Tm1.Client.Services.CubeService(_tm1.Session());
        var total = new Dictionary<string, string> { ["Region"] = "Total" };
        await cubes.CheckFeedersAsync("Sales", total, Model.CheckFeedersSelection.Unfed).ToListAsync();
        var sent = Assert.Single(_tm1.Requests(CheckFeedersKey)).Text;
        Assert.Contains("Tuple@odata.bind", sent);
        Assert.DoesNotContain("Selection", sent);
        await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(async () =>
            await cubes.CheckFeedersAsync("Sales", total, Model.CheckFeedersSelection.Both).ToListAsync()
        );
        Assert.Single(_tm1.Requests(CheckFeedersKey));
    }

    [Fact]
    public async Task RefusesABoundParameterTheServerDoesntDefineWhateverTheBodyIsBuiltAs()
    {
        // A server whose CheckFeeders has no Tuple either: a binding is refused, default or not, and
        // the body is checked as written, here an anonymous object rather than a dictionary
        var snapshot = File.ReadAllText(Repo.Snapshot);
        var metadata = new System.Text.RegularExpressions.Regex(
            "(<Action Name=\"CheckFeeders\".*?)<Parameter Name=\"Tuple\".*?</Parameter>",
            System.Text.RegularExpressions.RegexOptions.Singleline
        ).Replace(snapshot, "$1", 1);
        Assert.NotEqual(snapshot, metadata);
        _tm1.On(MetadataKey, _ => Xml(metadata));
        var request = Tm1Request
            .PostList<JsonElement>(
                ODataPath.Of("Cubes").Key("Sales").Action("tm1.CheckFeeders"),
                new Dictionary<string, object?>
                {
                    ["Tuple@odata.bind"] = new[] { "Dimensions('Region')/Hierarchies('Region')/Elements('Total')" },
                }.ToDictionary(e => e.Key, e => e.Value)
            )
            .WithBody(new TupleOnly(["Dimensions('Region')/Hierarchies('Region')/Elements('Total')"]))
            .Requires(Model.Tm1Operations.CubeCheckFeeders)
            .WithParameterDefault("Tuple", "x");
        var u = await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(async () => await _tm1.Session().StreamAsync(request).ToListAsync());
        Assert.Equal("parameter Tuple of Cube.CheckFeeders", u.Missing);
        Assert.Empty(_tm1.Requests(CheckFeedersKey));
    }

    private sealed record TupleOnly([property: System.Text.Json.Serialization.JsonPropertyName("Tuple@odata.bind")] string[] Tuple);

    [Fact]
    public async Task ChecksTheBodyAsItWasQueuedInABatch()
    {
        WithoutSelection();
        _tm1.On("POST $batch", _ => ScriptedTm1.Json(200, "{\"responses\":[]}"));
        var body = new Dictionary<string, object?>
        {
            ["Tuple@odata.bind"] = new List<string> { "Dimensions('Region')/Hierarchies('Region')/Elements('Total')" },
            ["Selection"] = "Both",
        };
        var batch = _tm1.Session().Batch();
        var both = batch
            .Session.StreamAsync(
                Tm1Request
                    .PostList<JsonElement>(ODataPath.Of("Cubes").Key("Sales").Action("tm1.CheckFeeders"), body)
                    .Requires(Model.Tm1Operations.CubeCheckFeeders)
            )
            .ToListAsync()
            .AsTask();
        // A change after queueing isn't sent, so it isn't what's checked either
        body.Remove("Selection");
        await batch.RunAsync().WaitAsync(TimeSpan.FromSeconds(5));
        await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(() => both.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public async Task ChecksNoParametersOnceARequirementReplacesTheOperation()
    {
        WithoutSelection();
        _tm1.On(CheckFeedersKey, _ => ScriptedTm1.Json(400, "{\"error\":{\"code\":\"278\",\"message\":\"Invalid parameter\"}}"));
        var request = CheckFeeders("Both").Requires(Version.Tm1Capability.BoundOperation("tm1.Cube", "tm1.CheckFeeders"));
        Assert.Null(request.Operation);
        await Assert.ThrowsAsync<Tm1BadRequestException>(async () => await _tm1.Session().StreamAsync(request).ToListAsync());
    }

    [Fact]
    public async Task SendsEveryParameterTheServerDefines()
    {
        _tm1.On(CheckFeedersKey, _ => ScriptedTm1.Json(200, "{\"value\":[]}"));
        await _tm1.Session().StreamAsync(CheckFeeders("Both")).ToListAsync();
        Assert.Contains("\"Selection\":\"Both\"", Assert.Single(_tm1.Requests(CheckFeedersKey)).Text);
    }

    [Fact]
    public async Task RefusesAParameterTheServerDoesntDefineWithoutSendingIt()
    {
        WithoutSelection();
        var u = await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(async () => await _tm1.Session().StreamAsync(CheckFeeders("Both")).ToListAsync());
        Assert.Equal("parameter Selection of Cube.CheckFeeders", u.Missing);
        Assert.Contains("reports schema 11.8.35", u.Message);
        Assert.Empty(_tm1.Requests(CheckFeedersKey));
    }

    [Fact]
    public async Task LeavesOutAParameterTheServerDoesntDefineWhenItsValueIsWhatLeavingItOutMeans()
    {
        WithoutSelection();
        _tm1.On(CheckFeedersKey, _ => ScriptedTm1.Json(200, "{\"value\":[]}"));
        await _tm1.Session().StreamAsync(CheckFeeders("Unfed")).ToListAsync();
        var sent = Assert.Single(_tm1.Requests(CheckFeedersKey)).Text;
        Assert.Contains("Tuple@odata.bind", sent);
        Assert.DoesNotContain("Selection", sent);
    }

    [Fact]
    public async Task ComparesADefaultAsJsonWhateverItsTypeOrKeyOrder()
    {
        WithoutSelection();
        _tm1.On(CheckFeedersKey, _ => ScriptedTm1.Json(200, "{\"value\":[]}"));
        await _tm1.Session().StreamAsync(CheckFeeders(5).WithParameterDefault("Selection", 5L)).ToListAsync();
        await _tm1.Session()
            .StreamAsync(
                CheckFeeders(new Dictionary<string, object> { ["B"] = 2, ["A"] = 1 })
                    .WithParameterDefault("Selection", new Dictionary<string, object> { ["A"] = 1, ["B"] = 2 })
            )
            .ToListAsync();
        var sent = _tm1.Requests(CheckFeedersKey);
        Assert.Equal(2, sent.Count);
        Assert.All(sent, s =>
        {
            Assert.Contains("Tuple@odata.bind", s.Text);
            Assert.DoesNotContain("Selection", s.Text);
        });
    }

    [Fact]
    public async Task LeavesOutAParameterInABatchToo()
    {
        WithoutSelection();
        _tm1.On("POST $batch", r =>
            ScriptedTm1.Json(
                200,
                "{\"responses\":["
                    + string.Join(
                        ",",
                        r.Json.GetProperty("requests")
                            .EnumerateArray()
                            .Select(q => $"{{\"id\":\"{q.GetProperty("id").GetString()}\",\"status\":200,\"body\":{{\"value\":[]}}}}")
                    )
                    + "]}"
            )
        );
        var batch = _tm1.Session().Batch();
        var unfed = batch.Session.StreamAsync(CheckFeeders("Unfed")).ToListAsync().AsTask();
        var both = batch.Session.StreamAsync(CheckFeeders("Both")).ToListAsync().AsTask();
        await batch.RunAsync().WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Empty(await unfed.WaitAsync(TimeSpan.FromSeconds(5)));
        await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(() => both.WaitAsync(TimeSpan.FromSeconds(5)));
        var sent = Assert.Single(_tm1.Requests("POST $batch")).Text;
        Assert.Contains("tm1.CheckFeeders", sent);
        Assert.DoesNotContain("Selection", sent);
    }

    [Fact]
    public async Task ReadsTheVersionAgainAfterLoggingInAgain()
    {
        _tm1.OnEach("GET Server", _ => ScriptedTm1.Status(401, "TM1SessionId=fresh; Path=/api/"), _ => ScriptedTm1.Json(200, "{}"));
        var session = _tm1.Session();
        await session.VersionAsync();
        await session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Server")));
        await session.VersionAsync();
        Assert.Equal(2, _tm1.Requests(VersionKey).Count);
    }

    [Fact]
    public async Task ReportsMetadataThatIsntCsdl()
    {
        _tm1.On(MetadataKey, _ => Xml("<html>Not metadata</html>"));
        await Assert.ThrowsAsync<Tm1ProtocolException>(() => _tm1.Session().CapabilitiesAsync());
    }

    [Fact]
    public async Task DoesntKeepAFailedRead()
    {
        _tm1.OnEach(
            VersionKey,
            _ => ScriptedTm1.Json(503, "{\"error\":{\"code\":\"1\",\"message\":\"busy\"}}"),
            _ => ScriptedTm1.Json(200, "{\"ProductVersion\":\"11.8.03500.6\"}")
        );
        var session = _tm1.Session();
        await Assert.ThrowsAnyAsync<Exception>(() => session.VersionAsync());
        await session.VersionAsync();
    }

    private static HttpResponseMessage Xml(string body)
    {
        var response = ScriptedTm1.Text(body);
        response.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/xml");
        return response;
    }
}

/// <summary>The Java client's <c>Tm1TimeoutsTest</c>.</summary>
public class Tm1TimeoutsTest
{
    [Fact]
    public void FillsInDefaults()
    {
        Assert.Equal(
            new Tm1Timeouts(TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(30), TimeSpan.FromMinutes(10), TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(30)),
            Tm1Timeouts.Defaults
        );
        var longRequests = Tm1Timeouts.Builder().Request(TimeSpan.FromHours(1)).Build();
        Assert.Equal(TimeSpan.FromHours(1), longRequests.Request);
        Assert.Equal(TimeSpan.FromSeconds(30), longRequests.Login);
        Assert.Equal(TimeSpan.FromSeconds(1), longRequests.ToBuilder().Close(TimeSpan.FromSeconds(1)).Build().Close);
    }

    [Fact]
    public void RefusesTimeoutsThatArentPositive()
    {
        Assert.Throws<ArgumentException>(() => Tm1Timeouts.Builder().Login(TimeSpan.Zero).Build());
        Assert.Throws<ArgumentException>(() => Tm1Timeouts.Builder().Connect(TimeSpan.FromSeconds(-1)).Build());
    }

    /// <summary>The request timeout in the tests against a slow server: ten times the pause between pieces.</summary>
    private static readonly TimeSpan Request = TimeSpan.FromSeconds(1);

    private static readonly TimeSpan Pause = TimeSpan.FromMilliseconds(100);

    private static readonly Tm1Request<JsonElement> Server = Tm1Request.Get<JsonElement>(ODataPath.Of("Server"), ODataQuery.None().Select("Name"));

    [Fact]
    public async Task KeepsReadingABodyThatArrivesSlowlyButSteadily()
    {
        // Twice the request timeout in all, never pausing for more than a tenth of it
        await using var tm1 = new SlowTm1(Request, async (body, ct) =>
        {
            await SlowTm1.WriteAsync(body, "{\"Name\":\"tm1\"", ct);
            for (var i = 0; i < 20; i++)
            {
                await Task.Delay(Pause, ct);
                await SlowTm1.WriteAsync(body, " ", ct);
            }
            await SlowTm1.WriteAsync(body, "}", ct);
        });
        var started = Stopwatch.StartNew();
        var server = await tm1.Session.ExecuteAsync(Server).WaitAsync(TimeSpan.FromSeconds(20));
        Assert.Equal("tm1", server.GetProperty("Name").GetString());
        Assert.True(started.Elapsed > Request, "the body took longer than the timeout");
    }

    [Fact]
    public async Task FailsABodyThatStallsForLongerThanTheRequestTimeout()
    {
        await using var tm1 = new SlowTm1(Request, async (body, ct) =>
        {
            await SlowTm1.WriteAsync(body, "{\"Name\":", ct);
            await Task.Delay(Timeout.Infinite, ct);
        });
        var e = await Assert.ThrowsAsync<Tm1TransportException>(() => tm1.Session.ExecuteAsync(Server).WaitAsync(TimeSpan.FromSeconds(20)));
        Assert.Equal(Tm1TransportException.Phase.Response, e.TransportPhase);
    }

    /// <summary>A TM1 on a real socket: it logs in, and answers anything else with a 200 whose body a
    /// test writes, piece by piece, ended by closing the connection. The session has a short request
    /// timeout.</summary>
    private sealed class SlowTm1 : IAsyncDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly CancellationTokenSource _done = new();
        private readonly Func<NetworkStream, CancellationToken, Task> _body;
        private readonly Task _accepting;

        public SlowTm1(TimeSpan request, Func<NetworkStream, CancellationToken, Task> body)
        {
            _body = body;
            _listener.Start();
            _accepting = AcceptAsync();
            Session = Tm1Session
                .Builder()
                .BaseUrl($"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}/api/v1/")
                .BasicAuth("admin", "apple")
                .Timeouts(Tm1Timeouts.Builder().Request(request).Build())
                .Build();
        }

        public Tm1Session Session { get; }

        public static async Task WriteAsync(NetworkStream stream, string text, CancellationToken ct)
        {
            await stream.WriteAsync(Encoding.UTF8.GetBytes(text), ct);
            await stream.FlushAsync(ct);
        }

        private async Task AcceptAsync()
        {
            try
            {
                while (true)
                {
                    _ = AnswerAsync(await _listener.AcceptTcpClientAsync(_done.Token));
                }
            }
            catch (Exception e) when (e is OperationCanceledException or SocketException or ObjectDisposedException)
            {
                // The test is over
            }
        }

        private async Task AnswerAsync(TcpClient client)
        {
            using (client)
            {
                var stream = client.GetStream();
                try
                {
                    var head = await ReadHeadAsync(stream);
                    if (head.Contains("ActiveSession?", StringComparison.Ordinal))
                    {
                        await WriteAsync(
                            stream,
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: TM1SessionId=s1; Path=/api/; HttpOnly\r\n"
                                + "Content-Length: 8\r\nConnection: close\r\n\r\n{\"ID\":1}",
                            _done.Token
                        );
                        return;
                    }
                    await WriteAsync(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n", _done.Token);
                    await _body(stream, _done.Token);
                }
                catch (Exception e) when (e is IOException or OperationCanceledException or ObjectDisposedException)
                {
                    // The client gave up, or the test is over
                }
            }
        }

        private async Task<string> ReadHeadAsync(NetworkStream stream)
        {
            var head = new StringBuilder();
            var one = new byte[1];
            while (!head.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal) && await stream.ReadAsync(one, _done.Token) == 1)
            {
                head.Append((char)one[0]);
            }
            return head.ToString();
        }

        public async ValueTask DisposeAsync()
        {
            await _done.CancelAsync();
            _listener.Stop();
            await _accepting;
            _done.Dispose();
        }
    }
}

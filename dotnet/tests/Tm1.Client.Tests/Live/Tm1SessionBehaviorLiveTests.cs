// SPDX-License-Identifier: Apache-2.0

using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1SessionBehaviorTest</c>: what TM1 itself does that the session
/// relies on, checked over plain HTTP.</summary>
public sealed class Tm1SessionBehaviorLiveTests : IDisposable
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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
    [Tm1Fact(V11Only = true)]
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

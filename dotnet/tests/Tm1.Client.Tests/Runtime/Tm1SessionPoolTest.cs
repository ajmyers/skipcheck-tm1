// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The Java client's <c>Tm1SessionPoolTest</c>: one session per identity, evicted when
/// idle, old or least recently used, and closed after a grace period, on a clock the test moves.
/// (<c>observesEvictionsWithTheirReason</c> is Micrometer's, which .NET doesn't have.)</summary>
public sealed class Tm1SessionPoolTest : IAsyncLifetime
{
    private static readonly Tm1Request<JsonElement> Server = Tm1Request.Get<JsonElement>(ODataPath.Of("Server"));

    private readonly ScriptedTm1 _tm1 = new ScriptedTm1().On("GET Server", _ => ScriptedTm1.Json(200, "{}"));
    private readonly ManualTime _time = new();
    private Tm1SessionPool _pool;

    public Tm1SessionPoolTest() => _pool = Pool(b => b);

    public Task InitializeAsync() => Task.CompletedTask;

    public Task DisposeAsync() => _pool.CloseAsync().WaitAsync(TimeSpan.FromSeconds(5));

    [Fact]
    public void KeepsOneSessionPerIdentityMatchingNamesAsTm1Does()
    {
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        Assert.Same(alice, _pool.Session(Tm1Identity.Impersonating("ALICE")));
        Assert.Same(alice, _pool.Session(Tm1Identity.Impersonating("al ice")));
        Assert.NotSame(alice, _pool.Session(Tm1Identity.Impersonating("bob")));
        Assert.NotSame(alice, _pool.Session());
        Assert.Equal(3, _pool.Stats().Held);
        Assert.Empty(_tm1.Requests()); // getting a session sends nothing
    }

    [Fact]
    public async Task LogsEachIdentityInAsItself()
    {
        await _pool.Session(Tm1Identity.Impersonating("alice")).ExecuteAsync(Server);
        await _pool.Session().ExecuteAsync(Server);
        await _pool.Session(Tm1Identity.WithCredentials("carol", Tm1Authenticator.Basic("carol", "c"))).ExecuteAsync(Server);
        var logins = _tm1.Requests(ScriptedTm1.Login);
        Assert.Equal(["alice", null, null], logins.Select(r => r.Header("TM1-Impersonate")));
        Assert.Equal(["Basic YWRtaW46YXBwbGU=", "Basic YWRtaW46YXBwbGU=", "Basic Y2Fyb2w6Yw=="], logins.Select(r => r.Authorization));
    }

    [Fact]
    public async Task EvictsAnIdleSessionAndClosesItAfterTheGracePeriod()
    {
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        await alice.ExecuteAsync(Server);
        _time.Advance(TimeSpan.FromMinutes(10));
        var fresh = _pool.Session(Tm1Identity.Impersonating("alice"));
        Assert.NotSame(alice, fresh);
        Assert.Equal(1, _pool.Stats().EvictedIdle);
        Assert.False(alice.IsClosed()); // still open during the grace period
        _time.Advance(TimeSpan.FromSeconds(30));
        await ScriptedTm1.Eventually(() => _pool.Stats().Closed == 1, "for the close");
        Assert.True(alice.IsClosed());
        Assert.Single(_tm1.Requests(ScriptedTm1.Close));
        Assert.Equal(0, _pool.Stats().Closing);
    }

    [Fact]
    public void KeepsASessionThatsInUse()
    {
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        for (int i = 0; i < 5; i++)
        {
            _time.Advance(TimeSpan.FromMinutes(9));
            Assert.Same(alice, _pool.Session(Tm1Identity.Impersonating("alice")));
        }
    }

    [Fact]
    public async Task GivesDifferentCredentialsForTheSameNameTheirOwnSession()
    {
        var carol = _pool.Session(Tm1Identity.WithCredentials("carol", Tm1Authenticator.Basic("carol", "c")));
        Assert.Same(carol, _pool.Session(Tm1Identity.WithCredentials("Carol", Tm1Authenticator.Basic("carol", "c")))); // the same credentials, made again
        await carol.ExecuteAsync(Server);

        var other = _pool.Session(Tm1Identity.WithCredentials("carol", Tm1Authenticator.Basic("carol", "wrong")));
        Assert.NotSame(carol, other); // never the session someone else's credentials opened
        await other.ExecuteAsync(Server);
        // Each logs in with what it was given
        Assert.Equal(["Basic Y2Fyb2w6Yw==", "Basic Y2Fyb2w6d3Jvbmc="], _tm1.Requests(ScriptedTm1.Login).Select(r => r.Authorization));
    }

    [Fact]
    public void SharesASessionForTheSameAuthenticatorWhenItsCredentialsCantBeCompared()
    {
        var passport = Tm1Authenticator.CamPassport(_ => ValueTask.FromResult("p1"));
        var dave = _pool.Session(Tm1Identity.WithCredentials("dave", passport));
        Assert.Same(dave, _pool.Session(Tm1Identity.WithCredentials("dave", passport)));
        Assert.NotSame(dave, _pool.Session(Tm1Identity.WithCredentials("dave", Tm1Authenticator.CamPassport(_ => ValueTask.FromResult("p1")))));
        Assert.Equal(Tm1Authenticator.CamPassport("p1"), Tm1Authenticator.CamPassport("p1"));
        Assert.NotEqual(Tm1Authenticator.CamPassport("p1"), Tm1Authenticator.CamPassport("p2"));
        Assert.Equal(
            Tm1Authenticator.CognosLogon("http://cognos:9300", "LDAP", "dave", "d"),
            Tm1Authenticator.CognosLogon("http://cognos:9300", "LDAP", "dave", "d")
        );
        Assert.NotEqual(
            Tm1Authenticator.CognosLogon("http://cognos:9300", "LDAP", "dave", "d"),
            Tm1Authenticator.CognosLogon("http://cognos:9300", "LDAP", "dave", "other")
        );
    }

    /// <summary>.NET only: what the fixed authenticators send and show, and that equal ones hash alike.</summary>
    [Fact]
    public async Task LogsInWithAPassportItWasHandedAndNeverShowsIt()
    {
        var passport = Tm1Authenticator.CamPassport("p1");
        Assert.Equal("CAMPassport p1", await passport.AuthorizationAsync());
        Assert.Equal("CAMPassport", passport.Describe());
        Assert.DoesNotContain("p1", passport.ToString());
        Assert.Equal(passport.GetHashCode(), Tm1Authenticator.CamPassport("p1").GetHashCode());
        Assert.Equal(Tm1Authenticator.Basic("carol", "c"), Tm1Authenticator.Basic("carol", "c"));
        Assert.NotEqual(Tm1Authenticator.Basic("carol", "c"), Tm1Authenticator.CamNamespace("carol", "c", "LDAP"));
        Assert.Equal(Tm1Authenticator.CamNamespace("carol", "c", "LDAP"), Tm1Authenticator.CamNamespace("carol", "c", "LDAP"));
        Assert.Equal(
            Tm1Identity.WithCredentials("carol", Tm1Authenticator.Basic("carol", "c")).GetHashCode(),
            Tm1Identity.WithCredentials("Carol", Tm1Authenticator.Basic("carol", "c")).GetHashCode()
        );
        Assert.Throws<ArgumentNullException>(() => Tm1Authenticator.CamPassport((string)null!));
    }

    [Fact]
    public async Task KeepsASessionWhileARequestIsRunningOnIt()
    {
        var answer = new ScriptedTm1.Pending();
        _tm1.On("GET Server", _ => answer.Task);
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        var result = alice.ExecuteAsync(Server);
        await ScriptedTm1.Eventually(() => _tm1.Requests("GET Server").Count == 1, "for the request");

        _time.Advance(TimeSpan.FromMinutes(25));
        Assert.Equal(0, _pool.Stats().EvictedIdle); // a running request is use
        Assert.False(alice.IsClosed());

        answer.Answer(ScriptedTm1.Json(200, "{}"));
        await result.WaitAsync(TimeSpan.FromSeconds(5));
        _time.Advance(TimeSpan.FromMinutes(12));
        Assert.Equal(1, _pool.Stats().EvictedIdle); // idle once it has finished
        await ScriptedTm1.Eventually(() => alice.IsClosed(), "for the close");
    }

    /// <summary>.NET only: a collection counts as running until its stream ends or is disposed, as
    /// Java's does while its body is read, though this client reads a page whole.</summary>
    [Fact]
    public async Task KeepsASessionWhileACollectionIsBeingRead()
    {
        _tm1.On("GET Cubes", _ => ScriptedTm1.Json(200, "{\"value\":[{\"Name\":\"a\"},{\"Name\":\"b\"}]}"));
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        await using (var cubes = alice.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"))).GetAsyncEnumerator())
        {
            Assert.True(await cubes.MoveNextAsync());
            _time.Advance(TimeSpan.FromMinutes(25));
            Assert.Equal(0, _pool.Stats().EvictedIdle); // the caller is still reading
        }
        _time.Advance(TimeSpan.FromMinutes(12));
        Assert.Equal(1, _pool.Stats().EvictedIdle); // idle once the stream is disposed
    }

    [Fact]
    public async Task ClosesAnEvictedSessionOnlyOnceItsRequestsHaveFinished()
    {
        await _pool.CloseAsync();
        _pool = Pool(b => b.MaxAge(TimeSpan.FromMinutes(30)));
        var answer = new ScriptedTm1.Pending();
        _tm1.On("GET Server", _ => answer.Task);
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        var result = alice.ExecuteAsync(Server);
        await ScriptedTm1.Eventually(() => _tm1.Requests("GET Server").Count == 1, "for the request");

        _time.Advance(TimeSpan.FromMinutes(40));
        Assert.Equal(1, _pool.Stats().EvictedAge); // out of the pool at its age
        for (int i = 0; i < 5; i++)
        {
            // The pool looks again every second; its timers are set as the ones before them fire
            await Task.Delay(20);
            _time.Advance(TimeSpan.FromSeconds(1));
            Assert.False(alice.IsClosed()); // but open for the request still running
        }

        answer.Answer(ScriptedTm1.Json(200, "{}"));
        await result.WaitAsync(TimeSpan.FromSeconds(5));
        await ScriptedTm1.Eventually(
            () =>
            {
                _time.Advance(TimeSpan.FromSeconds(1));
                return alice.IsClosed();
            },
            "for the close"
        );
        await ScriptedTm1.Eventually(() => _tm1.Requests(ScriptedTm1.Close).Count == 1, "for TM1 to be told");
    }

    [Fact]
    public async Task EvictsASessionPastItsMaximumAgeEvenIfInUse()
    {
        await _pool.CloseAsync();
        _pool = Pool(b => b.MaxAge(TimeSpan.FromMinutes(30)));
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        for (int i = 0; i < 3; i++)
        {
            _time.Advance(TimeSpan.FromMinutes(9));
            _pool.Session(Tm1Identity.Impersonating("alice"));
        }
        _time.Advance(TimeSpan.FromMinutes(4));
        Assert.NotSame(alice, _pool.Session(Tm1Identity.Impersonating("alice")));
        Assert.Equal(1, _pool.Stats().EvictedAge);
    }

    [Fact]
    public async Task EvictsTheLeastRecentlyUsedSessionWhenFull()
    {
        await _pool.CloseAsync();
        _pool = Pool(b => b.MaxSize(2));
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        var bob = _pool.Session(Tm1Identity.Impersonating("bob"));
        _pool.Session(Tm1Identity.Impersonating("alice"));
        _pool.Session(Tm1Identity.Impersonating("carol"));
        Assert.Equal(2, _pool.Stats().Held);
        Assert.Equal(1, _pool.Stats().EvictedSize);
        Assert.Same(alice, _pool.Session(Tm1Identity.Impersonating("alice")));
        Assert.NotSame(bob, _pool.Session(Tm1Identity.Impersonating("bob")));
    }

    [Fact]
    public async Task SweepsExpiredSessionsOnItsOwn()
    {
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        await alice.ExecuteAsync(Server);
        _time.Advance(TimeSpan.FromMinutes(11));
        Assert.Equal(0, _pool.Stats().Held);
        _time.Advance(TimeSpan.FromSeconds(30));
        await ScriptedTm1.Eventually(() => alice.IsClosed(), "for the close");
    }

    [Fact]
    public async Task ClosesEverySessionAtOnceIncludingThoseInTheirGracePeriod()
    {
        var alice = _pool.Session(Tm1Identity.Impersonating("alice"));
        await alice.ExecuteAsync(Server);
        _time.Advance(TimeSpan.FromMinutes(10));
        var fresh = _pool.Session(Tm1Identity.Impersonating("alice"));
        await fresh.ExecuteAsync(Server);
        await _pool.CloseAsync().WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(alice.IsClosed());
        Assert.True(fresh.IsClosed());
        Assert.Equal(2, _tm1.Requests(ScriptedTm1.Close).Count);
        Assert.Throws<Tm1SessionClosedException>(() => _pool.Session());
    }

    [Fact]
    public void RefusesIdentitiesTm1WouldMisread()
    {
        Assert.Throws<ArgumentException>(() => Tm1Identity.Impersonating(" "));
        Assert.Throws<ArgumentException>(() => Tm1Identity.WithCredentials("", Tm1Authenticator.Basic("x", "y")));
        Assert.Equal(Tm1Identity.Impersonating("alice"), Tm1Identity.Impersonating("Alice"));
        Assert.NotEqual(Tm1Identity.WithCredentials("alice", Tm1Authenticator.Basic("alice", "a")), Tm1Identity.Impersonating("Alice"));
        Assert.DoesNotContain("secret", Tm1Identity.WithCredentials("carol", Tm1Authenticator.Basic("carol", "secret")).ToString());
    }

    [Fact]
    public void RefusesSettingsOutOfRange()
    {
        var builder = Tm1SessionPool.Builder(_tm1.Builder().BasicAuth("admin", "apple"));
        Assert.Throws<ArgumentException>(() => builder.IdleTimeout(TimeSpan.Zero));
        Assert.Throws<ArgumentException>(() => builder.MaxAge(TimeSpan.FromSeconds(-1)));
        Assert.Throws<ArgumentException>(() => builder.MaxSize(0));
        Assert.Throws<ArgumentException>(() => builder.CloseGrace(TimeSpan.FromSeconds(-1)));
        Assert.Throws<ArgumentException>(() => builder.SweepInterval(TimeSpan.Zero));
        builder.CloseGrace(TimeSpan.Zero);
    }

    [Fact]
    public async Task PoolsPerUserApplicationSessions()
    {
        const string dbRoot = "http://tm1.test/tm1/api/v1/Databases('sales')/";
        const string authUrl = "POST http://tm1.test/tm1/auth/v1/session";
        using var v12 = new ScriptedTm1()
            .OnEach(
                authUrl,
                _ => ScriptedTm1.Status(201, "TM1SessionId=u1; Path=/; HttpOnly"),
                _ => ScriptedTm1.Status(201, "TM1SessionId=u2; Path=/; HttpOnly")
            )
            .On("GET " + dbRoot + "ActiveSession?$select=ID", _ => ScriptedTm1.Json(200, "{\"ID\":1}"))
            .On("GET " + dbRoot + "Server", _ => ScriptedTm1.Json(200, "{}"))
            .On("POST http://tm1.test/tm1/api/v1/ActiveSession/tm1s.Close", _ => ScriptedTm1.Status(204));

        var v12Pool = Tm1SessionPool
            .Builder(
                Tm1Session
                    .Builder()
                    .Dialect(Skipcheck.Tm1.Client.Version.Tm1Dialect.V12("sales"))
                    .Address("http://tm1.test")
                    .Authenticator(Tm1Authenticator.Application("svc", "secret", "default-user"))
                    .HttpHandler(v12)
            )
            .TimeProvider(_time)
            .Build();
        try
        {
            var alice = v12Pool.Session(Tm1Identity.WithCredentials("alice", Tm1Authenticator.Application("svc", "secret", "alice")));
            Assert.Same(alice, v12Pool.Session(Tm1Identity.WithCredentials("ALICE", Tm1Authenticator.Application("svc", "secret", "alice"))));
            var bob = v12Pool.Session(Tm1Identity.WithCredentials("bob", Tm1Authenticator.Application("svc", "secret", "bob")));
            Assert.NotSame(alice, bob);
            Assert.Throws<InvalidOperationException>(() => v12Pool.Session(Tm1Identity.Impersonating("charlie")));

            await alice.ExecuteAsync(Server);
            await bob.ExecuteAsync(Server);
            Assert.Equal(["{\"User\":\"alice\"}", "{\"User\":\"bob\"}"], v12.Requests(authUrl).Select(r => r.Text).ToArray());
        }
        finally
        {
            await v12Pool.CloseAsync().WaitAsync(TimeSpan.FromSeconds(5));
        }
    }

    private Tm1SessionPool Pool(Func<Tm1SessionPoolBuilder, Tm1SessionPoolBuilder> settings) =>
        settings(Tm1SessionPool.Builder(_tm1.Builder().BasicAuth("admin", "apple")).TimeProvider(_time)).Build();
}

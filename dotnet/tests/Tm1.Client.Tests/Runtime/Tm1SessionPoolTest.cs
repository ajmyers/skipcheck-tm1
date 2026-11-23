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

    private Tm1SessionPool Pool(Func<Tm1SessionPoolBuilder, Tm1SessionPoolBuilder> settings) =>
        settings(Tm1SessionPool.Builder(_tm1.Builder().BasicAuth("admin", "apple")).TimeProvider(_time)).Build();
}

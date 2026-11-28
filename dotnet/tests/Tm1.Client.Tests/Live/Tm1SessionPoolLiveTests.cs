// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1SessionPoolTm1Test</c>.</summary>
public sealed class Tm1SessionPoolLiveTests : IAsyncLifetime
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
        var targetUser = Live.Target().User;
        if (Live.IsEngine)
        {
            Assert.Equal(_alice, await Name(Pool.Session(Identity(_alice, "a"))));
            Assert.Equal(_bob, await Name(Pool.Session(Identity(_bob, "b"))));
            Assert.Equal(targetUser, await Name(Pool.Session()), ignoreCase: true);
            Assert.Equal(3, Pool.Stats().Held);
            return;
        }
        Assert.Equal(_alice, await Name(Pool.Session(Tm1Identity.Impersonating(_alice))));
        Assert.Equal(_bob, await Name(Pool.Session(Tm1Identity.Impersonating(_bob))));
        Assert.Equal(_bob, await Name(Pool.Session(Tm1Identity.WithCredentials(_bob, Tm1Authenticator.Basic(_bob, "b")))));
        Assert.Equal(targetUser, await Name(Pool.Session()), ignoreCase: true);
        Assert.Equal(4, Pool.Stats().Held);
    }

    [Tm1Fact]
    public async Task ClosesEvictedSessionsOnTm1()
    {
        var session = Pool.Session(Identity(_alice, "a"));
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
        await Name(Pool.Session(Identity(_alice, "a")));
        await Name(Pool.Session(Identity(_bob, "b")));
        await Pool.CloseAsync().WaitAsync(Wait);
        Assert.Equal(0, Raw.AwaitSessionCount(_admin, _context, 0, Wait));
    }

    /// <summary>Asking for an administrator fails; nothing runs as the pool's own user instead.</summary>
    [Tm1Fact]
    public async Task NeverFallsBackToAnotherIdentity()
    {
        if (Live.IsEngine)
        {
            return; // TM1 v12 does not support TM1-Impersonate (xUnit 2 can't skip once started)
        }
        var e = await Assert.ThrowsAnyAsync<Tm1ApiException>(() => Pool.Session(Tm1Identity.Impersonating("Admin")).ExecuteAsync(ActiveUser));
        Assert.Equal(Tm1ErrorKind.ImpersonationRefused, e.Kind);
    }

    private static Tm1Identity Identity(string user, string password) =>
        Live.IsEngine ? Tm1Identity.WithCredentials(user, Live.Authenticator(user, password)) : Tm1Identity.Impersonating(user);

    private static async Task<string?> Name(Tm1Session session) => (await session.ExecuteAsync(ActiveUser)).GetProperty("Name").GetString();
}

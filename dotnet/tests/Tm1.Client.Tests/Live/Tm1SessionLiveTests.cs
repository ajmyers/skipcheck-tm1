using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1SessionTm1Test</c>: the session against a real TM1, leaving no session behind.</summary>
public sealed class Tm1SessionLiveTests : IDisposable
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

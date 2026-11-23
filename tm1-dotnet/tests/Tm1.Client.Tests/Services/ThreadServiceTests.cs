using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ThreadService against a fake TM1.</summary>
public class ThreadServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsSessionAndCancels()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = 99, Function = "POST /api/v1/Processes('Load')/tm1.ExecuteWithReturn" });
        var tm1 = _tm1.Client();
        var thread = Assert.Single(await tm1.Threads.ListAsync(ODataQuery.None().Filter(new ODataProperty("State").Ne("Idle"))).ToListAsync());
        Assert.Equal(99L, thread.Id);
        Assert.Equal("GET Threads?$filter=(State ne 'Idle')", $"{_tm1.Last.Method} {System.Uri.UnescapeDataString(_tm1.Last.Target)}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = 99 };
        Assert.Equal(99L, (await tm1.Threads.GetAsync(99)).Id);
        Assert.Equal("GET Threads(99)", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = 7 };
        Assert.Equal(7L, (await tm1.Threads.SessionAsync(99, ODataQuery.None().Select("ID"))).Id);
        Assert.Equal("Threads(99)/Session?$select=ID", _tm1.Last.Target);

        _tm1.Respond = _ => null;
        await tm1.Threads.CancelAsync(99);
        Assert.Equal(("POST", "Threads(99)/tm1.CancelOperation", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }
}

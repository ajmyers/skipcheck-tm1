using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>SessionService against a fake TM1.</summary>
public class SessionServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsAndTheActiveSession()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = 7, Context = "loader" });
        var tm1 = _tm1.Client();
        Assert.Equal("loader", Assert.Single(await tm1.Sessions.ListAsync(ODataQuery.None().Expand("User")).ToListAsync()).Context);
        Assert.Equal("GET Sessions?$expand=User", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = 7 };
        Assert.Equal(7L, (await tm1.Sessions.GetAsync(7)).Id);
        Assert.Equal("GET Sessions(7)", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        await tm1.Sessions.ActiveSessionAsync(ODataQuery.None().Select("ID"));
        Assert.Equal("GET ActiveSession?$select=ID", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task UserThreadsAndClose()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "admin" };
        var tm1 = _tm1.Client();
        Assert.Equal("admin", (await tm1.Sessions.UserAsync(7)).Name);
        Assert.Equal("Sessions(7)/User", _tm1.Last.Target);
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = 99 });
        Assert.Equal(99L, Assert.Single(await tm1.Sessions.ThreadsAsync(7).ToListAsync()).Id);
        Assert.Equal("Sessions(7)/Threads", _tm1.Last.Target);
        _tm1.Respond = _ => null;
        await tm1.Sessions.CloseAsync(7);
        Assert.Equal(("POST", "Sessions(7)/tm1.Close", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }
}

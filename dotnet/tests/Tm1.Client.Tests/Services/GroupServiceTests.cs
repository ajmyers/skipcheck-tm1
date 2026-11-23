// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>GroupService against a fake TM1.</summary>
public class GroupServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesDeletesAndListsUsers()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "ADMIN" });
        var tm1 = _tm1.Client();
        Assert.Equal("ADMIN", Assert.Single(await tm1.Groups.ListAsync(ODataQuery.None().Top(5)).ToListAsync()).Name);
        Assert.Equal("GET Groups?$top=5", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Finance" };
        Assert.Equal("Finance", (await tm1.Groups.GetAsync("Finance")).Name);
        Assert.Equal("GET Groups('Finance')", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        await tm1.Groups.CreateAsync("Finance");
        Assert.Equal(("POST", "Groups", """{"Name":"Finance"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Groups.DeleteAsync("Finance");
        Assert.Equal(("DELETE", "Groups('Finance')"), (_tm1.Last.Method, _tm1.Last.Target));

        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "jsmith" });
        Assert.Equal("jsmith", Assert.Single(await tm1.Groups.UsersAsync("Finance", ODataQuery.None().Select("Name")).ToListAsync()).Name);
        Assert.Equal("GET Groups('Finance')/Users?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }
}

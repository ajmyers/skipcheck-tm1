using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>UserService against a fake TM1: each method sends what the Java client's does, and
/// group membership is only ever set whole, never by the <c>$ref</c> delete that deletes the group.</summary>
public class UserServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsAndTheActiveUser()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "admin" });
        var tm1 = _tm1.Client();
        Assert.Equal("admin", Assert.Single(await tm1.Users.ListAsync(ODataQuery.None().Select("Name")).ToListAsync()).Name);
        Assert.Equal("GET Users?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "J Smith" };
        Assert.Equal("J Smith", (await tm1.Users.GetAsync("J Smith", ODataQuery.None().Expand("Groups"))).Name);
        Assert.Equal("Users('J%20Smith')?$expand=Groups", _tm1.Last.Target);
        await tm1.Users.ActiveUserAsync();
        Assert.Equal("GET ActiveUser", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task CreatesUpdatesAndDeletes()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "jsmith" };
        var tm1 = _tm1.Client();
        var groups = ODataPath.Of("Groups");
        await tm1.Users.CreateAsync(UserPatch.Builder().Name("jsmith").Password("pw").BindGroups(groups.Key("Finance"), groups.Key("Planners")).Build());
        Assert.Equal(("POST", "Users"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"Name":"jsmith","Password":"pw","Groups@odata.bind":["Groups('Finance')","Groups('Planners')"]}""", _tm1.Last.Text);

        _tm1.Respond = _ => null;
        await tm1.Users.UpdateAsync("jsmith", UserPatch.Builder().Enabled(false).Build());
        Assert.Equal(("PATCH", "Users('jsmith')", """{"Enabled":false}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.Users.DeleteAsync("jsmith");
        Assert.Equal(("DELETE", "Users('jsmith')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task UpdateRefusesGroups()
    {
        var tm1 = _tm1.Client();
        await Assert.ThrowsAsync<ArgumentException>(() => tm1.Users.UpdateAsync("jsmith", UserPatch.Builder().BindGroups(ODataPath.Of("Groups").Key("Finance")).Build()));
        await Assert.ThrowsAsync<ArgumentException>(() => tm1.Users.UpdateAsync("jsmith", UserPatch.Builder().Groups([]).Build()));
        Assert.DoesNotContain(_tm1.Requests, r => r.Method == "PATCH");
    }

    [Fact]
    public async Task SetGroupsReplacesThemAndNeverDeletesALink()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        await tm1.Users.SetGroupsAsync("jsmith", ["Finance", "O'Brien Team"]);
        Assert.Equal(("PATCH", "Users('jsmith')"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"Groups@odata.bind":["Groups('Finance')","Groups('O''Brien%20Team')"]}""", _tm1.Last.Text);
        await tm1.Users.SetGroupsAsync("jsmith", []);
        Assert.Equal("""{"Groups@odata.bind":[]}""", _tm1.Last.Text);
        Assert.DoesNotContain(_tm1.Requests, r => r.Method == "DELETE" || r.Target.Contains("$ref", StringComparison.Ordinal));
    }

    [Fact]
    public async Task GroupsSessionsAndDisconnect()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Finance" });
        var tm1 = _tm1.Client();
        Assert.Equal("Finance", Assert.Single(await tm1.Users.GroupsAsync("jsmith").ToListAsync()).Name);
        Assert.Equal("GET Users('jsmith')/Groups", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = 7 });
        Assert.Equal(7L, Assert.Single(await tm1.Users.SessionsAsync("jsmith", ODataQuery.None().Expand("Threads")).ToListAsync()).Id);
        Assert.Equal("Users('jsmith')/Sessions?$expand=Threads", _tm1.Last.Target);
        _tm1.Respond = _ => null;
        await tm1.Users.DisconnectAsync("jsmith");
        Assert.Equal(("POST", "Users('jsmith')/tm1.Disconnect", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }
}

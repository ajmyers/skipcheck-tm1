using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ViewService against a fake TM1: public and private views by their navigation.</summary>
public class ViewServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public void ScopesNavigateToTheirViews()
    {
        Assert.Equal("Views", ViewScope.Public.Navigation());
        Assert.Equal("PrivateViews", ViewScope.Private.Navigation());
    }

    [Fact]
    public async Task ListsAndGetsInEitherScope()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Default" });
        var tm1 = _tm1.Client();
        var views = await tm1.Views.ListAsync("Sales", ViewScope.Public, ODataQuery.None().Select("Name")).ToListAsync();
        Assert.Equal("Default", Assert.Single(views).Name);
        Assert.Equal("GET Cubes('Sales')/Views?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        await tm1.Views.ListAsync("Sales", ViewScope.Private).ToListAsync();
        Assert.Equal("Cubes('Sales')/PrivateViews", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Default" };
        var view = await tm1.Views.GetAsync("Sales", ViewScope.Private, "Default");
        Assert.Equal("Default", view.Name);
        Assert.Equal("Cubes('Sales')/PrivateViews('Default')", _tm1.Last.Target);
    }

    [Fact]
    public async Task GetsANativeViewByItsCast()
    {
        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["@odata.type"] = "#ibm.tm1.api.v1.NativeView",
            ["Name"] = "Default",
            ["SuppressEmptyRows"] = true,
        };
        var view = await _tm1.Client().Views.GetNativeAsync("Sales", ViewScope.Public, "Default", ODataQuery.None().Expand("Columns"));
        Assert.Equal("Default", view.Name);
        Assert.True(view.SuppressEmptyRows);
        Assert.Equal("Cubes('Sales')/Views('Default')/ibm.tm1.api.v1.NativeView?$expand=Columns", _tm1.Last.Target);
    }

    [Fact]
    public async Task CreatesUpdatesAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Mine" };
        var created = await tm1.Views.CreateAsync("Sales", ViewScope.Private, ViewPatch.Builder().Name("Mine").Build());
        Assert.Equal("Mine", created.Name);
        Assert.Equal(("POST", "Cubes('Sales')/PrivateViews", """{"Name":"Mine"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Views.UpdateAsync("Sales", ViewScope.Public, "Default", ViewPatch.Builder().Name("Renamed").Build());
        Assert.Equal(("PATCH", "Cubes('Sales')/Views('Default')"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Views.UpdateAsync("Sales", ViewScope.Public, "Default", ViewPatch.Builder().Name("Renamed").Build(), "W/\"3\"");
        Assert.Equal("W/\"3\"", _tm1.Last.Headers["if-match"]);

        await tm1.Views.DeleteAsync("Sales", ViewScope.Public, "Default");
        Assert.Equal(("DELETE", "Cubes('Sales')/Views('Default')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task ReadsTheViewsCube()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Sales" };
        var cube = await _tm1.Client().Views.CubeAsync("Sales", ViewScope.Public, "Default", ODataQuery.None().Select("Name"));
        Assert.Equal("Sales", cube.Name);
        Assert.Equal("Cubes('Sales')/Views('Default')/Cube?$select=Name", _tm1.Last.Target);
    }
}

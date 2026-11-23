using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>LevelService against a fake TM1: each method sends what the Java client's does.</summary>
public class LevelServiceTests
{
    private const string Levels = "Dimensions('Region')/Hierarchies('Region')/Levels";
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsAndGets()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Number = 0, Name = "Level000" }, new { Number = 1, Name = "Level001" });
        Assert.Equal(2, (await tm1.Levels.ListAsync("Region", "Region", ODataQuery.None().OrderBy("Number")).ToListAsync()).Count);
        Assert.Equal(Levels + "?$orderby=Number", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Number"] = 1, ["Name"] = "Level001" };
        Assert.Equal(1, (await tm1.Levels.GetAsync("Region", "Region", 1)).Number);
        Assert.Equal(("GET", Levels + "(1)"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task Navigations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "West" });
        Assert.Equal("West", Assert.Single(await tm1.Levels.MembersAsync("Region", "Region", 0, ODataQuery.None().Select("Name")).ToListAsync()).Name);
        Assert.Equal(Levels + "(0)/Members?$select=Name", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Region" };
        await tm1.Levels.HierarchyAsync("Region", "Region", 0);
        Assert.Equal(Levels + "(0)/Hierarchy", _tm1.Last.Target);
    }
}

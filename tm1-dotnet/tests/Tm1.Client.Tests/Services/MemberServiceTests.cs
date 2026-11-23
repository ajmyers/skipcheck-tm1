using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>MemberService against a fake TM1: each method sends what the Java client's does.</summary>
public class MemberServiceTests
{
    private const string Members = "Dimensions('Region')/Hierarchies('Region')/Members";
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsAndGets()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "West", UniqueName = "[Region].[Region].[West]" });
        Assert.Equal(
            "[Region].[Region].[West]",
            Assert.Single(await tm1.Members.ListAsync("Region", "Region", ODataQuery.None().Top(1)).ToListAsync()).UniqueName
        );
        Assert.Equal(Members + "?$top=1", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "West" };
        await tm1.Members.GetAsync("Region", "Region", "West", ODataQuery.None().Select("Name"));
        Assert.Equal(("GET", Members + "('West')?$select=Name"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task Navigations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Total" };
        Assert.Equal("Total", (await tm1.Members.ParentAsync("Region", "Region", "West"))!.Name);
        Assert.Equal(Members + "('West')/Parent", _tm1.Last.Target);
        await tm1.Members.ElementAsync("Region", "Region", "West");
        Assert.Equal(Members + "('West')/Element", _tm1.Last.Target);
        await tm1.Members.LevelAsync("Region", "Region", "West");
        Assert.Equal(Members + "('West')/Level", _tm1.Last.Target);
        await tm1.Members.HierarchyAsync("Region", "Region", "West");
        Assert.Equal(Members + "('West')/Hierarchy", _tm1.Last.Target);

        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "West" });
        await tm1.Members.ChildrenAsync("Region", "Region", "Total", ODataQuery.None().Select("Name")).ToListAsync();
        Assert.Equal(Members + "('Total')/Children?$select=Name", _tm1.Last.Target);

        // A root has no parent: TM1 sends no content
        _tm1.Respond = _ => null;
        Assert.Null(await tm1.Members.ParentAsync("Region", "Region", "Total"));
    }
}

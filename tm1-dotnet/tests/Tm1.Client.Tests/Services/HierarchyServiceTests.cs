using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>HierarchyService against a fake TM1: each method sends what the Java client's does.</summary>
public class HierarchyServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesUpdatesAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Region" }, new { Name = "Leaves" });
        Assert.Equal(2, (await tm1.Hierarchies.ListAsync("Region", ODataQuery.None().Select("Name")).ToListAsync()).Count);
        Assert.Equal("Dimensions('Region')/Hierarchies?$select=Name", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Leaves" };
        Assert.Equal("Leaves", (await tm1.Hierarchies.GetAsync("Region", "Leaves")).Name);
        Assert.Equal(("GET", "Dimensions('Region')/Hierarchies('Leaves')"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Hierarchies.CreateAsync("Region", HierarchyPatch.Builder().Name("Leaves").Build());
        Assert.Equal(("POST", "Dimensions('Region')/Hierarchies", """{"Name":"Leaves"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Hierarchies.UpdateAsync("Region", "Leaves", HierarchyPatch.Builder().Visible(false).Build());
        Assert.Equal(("PATCH", "Dimensions('Region')/Hierarchies('Leaves')", """{"Visible":false}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Hierarchies.UpdateAsync("Region", "Leaves", HierarchyPatch.Builder().Visible(true).Build(), "W/\"2\"");
        Assert.Equal("W/\"2\"", _tm1.Last.Headers["if-match"]);
        await tm1.Hierarchies.DeleteAsync("Region", "Leaves");
        Assert.Equal(("DELETE", "Dimensions('Region')/Hierarchies('Leaves')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task AnUpdateWithStructureIsRefused()
    {
        var tm1 = _tm1.Client();
        var withElements = HierarchyPatch.Builder().Elements([ElementPatch.Builder().Name("x").Build()]).Build();
        var withBinding = HierarchyPatch.Builder().BindElements(ODataPath.Of("Dimensions").Key("d")).Build();
        var withEdges = HierarchyPatch.Builder().Edges([]).Build();
        foreach (var patch in new[] { withElements, withBinding, withEdges })
        {
            var e = Assert.Throws<ArgumentException>(() => { _ = tm1.Hierarchies.UpdateAsync("Region", "Region", patch); });
            Assert.Contains("ReplaceStructureAsync", e.Message);
            Assert.Throws<ArgumentException>(() => { _ = tm1.Hierarchies.UpdateAsync("Region", "Region", patch, "W/\"1\""); });
        }
        Assert.Empty(_tm1.Requests);
    }

    [Fact]
    public async Task ReplacesTheStructure()
    {
        _tm1.Respond = _ => null;
        await _tm1.Client().Hierarchies.ReplaceStructureAsync("Region", "Region",
            [ElementPatch.Builder().Name("All").Type(ElementType.Consolidated).Build()],
            [EdgePatch.Builder().ParentName("All").ComponentName("North").Weight(1.0).Build()]);
        Assert.Equal(("PATCH", "Dimensions('Region')/Hierarchies('Region')"), (_tm1.Last.Method, _tm1.Last.Target));
        var body = _tm1.Last.Json;
        Assert.Equal("All", body.GetProperty("Elements")[0].GetProperty("Name").GetString());
        Assert.Equal("North", body.GetProperty("Edges")[0].GetProperty("ComponentName").GetString());
    }

    [Fact]
    public async Task Navigations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Region" };
        await tm1.Hierarchies.DefaultHierarchyAsync("Region", ODataQuery.None().Select("Name"));
        Assert.Equal("Dimensions('Region')/DefaultHierarchy?$select=Name", _tm1.Last.Target);
        await tm1.Hierarchies.DimensionAsync("Region", "Leaves");
        Assert.Equal("Dimensions('Region')/Hierarchies('Leaves')/Dimension", _tm1.Last.Target);
        Assert.Equal("Region", (await tm1.Hierarchies.AllMemberAsync("Region", "Region"))!.Name);
        Assert.Equal("Dimensions('Region')/Hierarchies('Region')/AllMember", _tm1.Last.Target);
        await tm1.Hierarchies.DefaultMemberAsync("Region", "Region");
        Assert.Equal("Dimensions('Region')/Hierarchies('Region')/DefaultMember", _tm1.Last.Target);

        // Several roots: TM1 sends no content
        _tm1.Respond = _ => null;
        Assert.Null(await tm1.Hierarchies.AllMemberAsync("Region", "Region"));
        Assert.Null(await tm1.Hierarchies.DefaultMemberAsync("Region", "Region"));

        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Total" });
        Assert.Equal("Total", Assert.Single(await tm1.Hierarchies.RootMembersAsync("Region", "Region", ODataQuery.None().Top(5)).ToListAsync()).Name);
        Assert.Equal(("GET", "Dimensions('Region')/Hierarchies('Region')/tm1.RootMembers()?$top=5"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task SavesAs()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Copy" };
        var tm1 = _tm1.Client();
        Assert.Equal("Copy", (await tm1.Hierarchies.SaveAsAsync("Region", "Region", "Copy")).Name);
        Assert.Equal(("POST", "Dimensions('Region')/Hierarchies('Region')/tm1.SaveAs", """{"Name":"Copy"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.Hierarchies.SaveAsAsync("Region", "Region", "Copy", true, true);
        Assert.Equal("""{"Name":"Copy","Overwrite":true,"KeepExistingAttributes":true}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task SetsAnElement()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        await tm1.Hierarchies.SetElementAsync("Region", "Region", "West");
        Assert.Equal(("POST", "Dimensions('Region')/Hierarchies('Region')/tm1.SetElement"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"Element@odata.bind":"Dimensions('Region')/Hierarchies('Region')/Elements('West')"}""", _tm1.Last.Text);
        await tm1.Hierarchies.SetElementAsync("Region", "Region", "West", "East");
        Assert.Equal("Dimensions('Region')/Hierarchies('Region')/Elements('East')", _tm1.Last.Json.GetProperty("Before@odata.bind").GetString());
        Assert.Equal(2, _tm1.Requests.Count(r => r.Target.EndsWith("tm1.SetElement", StringComparison.Ordinal)));
    }
}

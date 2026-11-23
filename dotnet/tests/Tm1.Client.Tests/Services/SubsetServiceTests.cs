// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>SubsetService against a fake TM1: each method sends what the Java client's does.</summary>
public class SubsetServiceTests
{
    private const string Hierarchy = "Dimensions('Region')/Hierarchies('Region')";
    private readonly FakeTm1 _tm1 = new();

    [Theory]
    [InlineData(SubsetScope.Public, "Subsets")]
    [InlineData(SubsetScope.Private, "PrivateSubsets")]
    [InlineData(SubsetScope.Session, "SessionSubsets")]
    public async Task ListsGetsCreatesUpdatesAndDeletesInEachScope(SubsetScope scope, string navigation)
    {
        var tm1 = _tm1.Client();
        var subsets = $"{Hierarchy}/{navigation}";
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "West Coast" });
        Assert.Equal("West Coast", Assert.Single(await tm1.Subsets.ListAsync("Region", "Region", scope, ODataQuery.None().Select("Name")).ToListAsync()).Name);
        Assert.Equal(subsets + "?$select=Name", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "West Coast" };
        await tm1.Subsets.GetAsync("Region", "Region", scope, "West Coast");
        Assert.Equal(("GET", subsets + "('West%20Coast')"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Subsets.CreateAsync("Region", "Region", scope, SubsetPatch.Builder().Name("Leaves").Expression("{TM1SUBSETALL([Region])}").Build());
        Assert.Equal(("POST", subsets, """{"Name":"Leaves","Expression":"{TM1SUBSETALL([Region])}"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Subsets.UpdateAsync("Region", "Region", scope, "Leaves", SubsetPatch.Builder().Expression("{}").Build());
        Assert.Equal(("PATCH", subsets + "('Leaves')", """{"Expression":"{}"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Subsets.UpdateAsync("Region", "Region", scope, "Leaves", SubsetPatch.Builder().Expression("{}").Build(), "W/\"4\"");
        Assert.Equal("W/\"4\"", _tm1.Last.Headers["if-match"]);
        await tm1.Subsets.DeleteAsync("Region", "Region", scope, "Leaves");
        Assert.Equal(("DELETE", subsets + "('Leaves')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public void AnUpdateWithElementsIsRefused()
    {
        var tm1 = _tm1.Client();
        var set = SubsetPatch.Builder().Elements([ElementPatch.Builder().Name("West").Build()]).Build();
        var bound = SubsetPatch.Builder().BindElements(ODataPath.Of("x")).Build();
        var e = Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Subsets.UpdateAsync("Region", "Region", SubsetScope.Public, "s", set);
        });
        Assert.Contains("AddElementsAsync", e.Message);
        Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Subsets.UpdateAsync("Region", "Region", SubsetScope.Public, "s", bound, "W/\"1\"");
        });
        Assert.Empty(_tm1.Requests);
    }

    [Fact]
    public async Task AddsClearsAndListsElements()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => null;
        await tm1.Subsets.AddElementsAsync("Region", "Region", SubsetScope.Public, "s", ["West", "East"]);
        Assert.Equal(("PATCH", Hierarchy + "/Subsets('s')"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal($$"""{"Elements@odata.bind":["{{Hierarchy}}/Elements('West')","{{Hierarchy}}/Elements('East')"]}""", _tm1.Last.Text);
        await tm1.Subsets.ClearElementsAsync("Region", "Region", SubsetScope.Private, "s");
        Assert.Equal(("DELETE", Hierarchy + "/PrivateSubsets('s')/Elements/$ref"), (_tm1.Last.Method, _tm1.Last.Target));

        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "West" });
        await tm1.Subsets.ElementsAsync("Region", "Region", SubsetScope.Public, "s", ODataQuery.None().Select("Name")).ToListAsync();
        Assert.Equal(Hierarchy + "/Subsets('s')/Elements?$select=Name", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Region" };
        await tm1.Subsets.HierarchyAsync("Region", "Region", SubsetScope.Session, "s");
        Assert.Equal(Hierarchy + "/SessionSubsets('s')/Hierarchy", _tm1.Last.Target);
    }

    [Fact]
    public async Task SavesAs()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Copy" };
        var tm1 = _tm1.Client();
        Assert.Equal("Copy", (await tm1.Subsets.SaveAsAsync("Region", "Region", SubsetScope.Public, "s", "Copy")).Name);
        Assert.Equal(("POST", Hierarchy + "/Subsets('s')/tm1.SaveAs", """{"Name":"Copy"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.Subsets.SaveAsAsync("Region", "Region", SubsetScope.Public, "s", "Copy", true, false);
        Assert.Equal("""{"Name":"Copy","MakePrivate":true,"MakeStatic":false}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task SetsElements()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        await tm1.Subsets.SetElementAsync("Region", "Region", SubsetScope.Public, "s", "West");
        Assert.Equal(("POST", Hierarchy + "/Subsets('s')/tm1.SetElement"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal($$"""{"Element@odata.bind":"{{Hierarchy}}/Elements('West')"}""", _tm1.Last.Text);
        await tm1.Subsets.SetElementBeforeAsync("Region", "Region", SubsetScope.Public, "s", "West", "East");
        Assert.Equal($$"""{"Element@odata.bind":"{{Hierarchy}}/Elements('West')","Before@odata.bind":"{{Hierarchy}}/Elements('East')"}""", _tm1.Last.Text);
        await tm1.Subsets.SetElementAfterAsync("Region", "Region", SubsetScope.Public, "s", "West", "East");
        Assert.Equal($$"""{"Element@odata.bind":"{{Hierarchy}}/Elements('West')","After@odata.bind":"{{Hierarchy}}/Elements('East')"}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task CreatesSessionSubsets()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "$session" };
        var tm1 = _tm1.Client();
        await tm1.Subsets.CreateSessionSubsetAsync("Region", "Region", SubsetPatch.Builder().Expression("{[Region].[West]}").Build());
        Assert.Equal(("POST", Hierarchy + "/tm1.CreateSessionSubset"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"Subset":{"Expression":"{[Region].[West]}"}}""", _tm1.Last.Text);
        await tm1.Subsets.CreateSessionSubsetFromAsync("Region", "Region", SubsetScope.Private, "Mine");
        Assert.Equal($$"""{"Subset@odata.bind":"{{Hierarchy}}/PrivateSubsets('Mine')"}""", _tm1.Last.Text);
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ElementService against a fake TM1: each method sends what the Java client's does.</summary>
public class ElementServiceTests
{
    private const string Elements = "Dimensions('Region')/Hierarchies('Region')/Elements";
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesUpdatesAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "West", Type = "Numeric" });
        var west = Assert.Single(await tm1.Elements.ListAsync("Region", "Region", ODataQuery.None().Select("Name", "Type")).ToListAsync());
        Assert.Equal(("West", ElementType.Numeric), (west.Name, west.Type));
        Assert.Equal(Elements + "?$select=Name,Type", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "West" };
        await tm1.Elements.GetAsync("Region", "Region", "West");
        Assert.Equal(("GET", Elements + "('West')"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Elements.CreateAsync("Region", "Region", ElementPatch.Builder().Name("West").Type(ElementType.Numeric).Build());
        Assert.Equal(("POST", Elements, """{"Name":"West","Type":"Numeric"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Elements.UpdateAsync("Region", "Region", "West", ElementPatch.Builder().Type(ElementType.String).Build());
        Assert.Equal(("PATCH", Elements + "('West')", """{"Type":"String"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Elements.UpdateAsync("Region", "Region", "West", ElementPatch.Builder().Type(ElementType.Numeric).Build(), "W/\"3\"");
        Assert.Equal("W/\"3\"", _tm1.Last.Headers["if-match"]);
        await tm1.Elements.DeleteAsync("Region", "Region", "West");
        Assert.Equal(("DELETE", Elements + "('West')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public void AnUpdateWithANameOrAttributesIsRefused()
    {
        var tm1 = _tm1.Client();
        var rename = Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Elements.UpdateAsync("Region", "Region", "West", ElementPatch.Builder().Name("East").Build());
        });
        Assert.Contains("can't be renamed", rename.Message);
        var attributes = ElementPatch.Builder().Attributes(AttributesPatch.Builder().Caption("W").Build()).Build();
        var e = Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Elements.UpdateAsync("Region", "Region", "West", attributes, "W/\"1\"");
        });
        Assert.Contains("}ElementAttributes_", e.Message);
        Assert.Empty(_tm1.Requests);
    }

    [Fact]
    public async Task Navigations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Total" });
        await tm1.Elements.ParentsAsync("Region", "Region", "West", ODataQuery.None().Select("Name")).ToListAsync();
        Assert.Equal(Elements + "('West')/Parents?$select=Name", _tm1.Last.Target);
        await tm1.Elements.ComponentsAsync("Region", "Region", "Total").ToListAsync();
        Assert.Equal(Elements + "('Total')/Components", _tm1.Last.Target);
        _tm1.Respond = _ =>
            FakeTm1.Collection(
                new
                {
                    ParentName = "Total",
                    ComponentName = "West",
                    Weight = 1.0,
                }
            );
        var edge = Assert.Single(await tm1.Elements.EdgesAsync("Region", "Region", "Total").ToListAsync());
        Assert.Equal(("Total", "West", 1.0), (edge.ParentName, edge.ComponentName, edge.Weight));
        Assert.Equal(Elements + "('Total')/Edges", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Region" };
        await tm1.Elements.HierarchyAsync("Region", "Region", "West");
        Assert.Equal(Elements + "('West')/Hierarchy", _tm1.Last.Target);
    }

    [Fact]
    public async Task SetsAComponent()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        await tm1.Elements.SetComponentAsync("Region", "Region", "Total", "West");
        Assert.Equal(("POST", Elements + "('Total')/tm1.SetComponent"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal($$"""{"Element@odata.bind":"{{Elements}}('West')"}""", _tm1.Last.Text);
        await tm1.Elements.SetComponentAsync("Region", "Region", "Total", "West", 2.0);
        Assert.Equal($$"""{"Element@odata.bind":"{{Elements}}('West')","Weight":2}""", _tm1.Last.Text);
        await tm1.Elements.SetComponentAsync("Region", "Region", "Total", "West", "East", -1.0);
        Assert.Equal($$"""{"Element@odata.bind":"{{Elements}}('West')","Before@odata.bind":"{{Elements}}('East')","Weight":-1}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task Operations()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        foreach (
            var (call, action) in new (Func<Task>, string)[]
            {
                (() => tm1.Elements.LockAsync("Region", "Region", "West"), "Lock"),
                (() => tm1.Elements.UnlockAsync("Region", "Region", "West"), "Unlock"),
                (() => tm1.Elements.ReserveAsync("Region", "Region", "West"), "Reserve"),
                (() => tm1.Elements.ReleaseAsync("Region", "Region", "West"), "Release"),
            }
        )
        {
            await call();
            Assert.Equal(("POST", $"{Elements}('West')/tm1.{action}", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        }
    }
}

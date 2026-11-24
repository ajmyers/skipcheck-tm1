// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>EdgeService against a fake TM1: each method sends what the Java client's does.</summary>
public class EdgeServiceTests
{
    private const string Edges = "Dimensions('Region')/Hierarchies('Region')/Edges";
    private const string Edge = Edges + "(ParentName='Total',ComponentName='O''Brien')";
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesUpdatesAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ =>
            FakeTm1.Collection(
                new
                {
                    ParentName = "Total",
                    ComponentName = "West",
                    Weight = 1.0,
                }
            );
        Assert.Equal(1.0, Assert.Single(await tm1.Edges.ListAsync("Region", "Region", ODataQuery.None().Top(1)).ToListAsync()).Weight);
        Assert.Equal(Edges + "?$top=1", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["ParentName"] = "Total",
            ["ComponentName"] = "O'Brien",
            ["Weight"] = 2.0,
        };
        Assert.Equal(2.0, (await tm1.Edges.GetAsync("Region", "Region", "Total", "O'Brien")).Weight);
        Assert.Equal(("GET", Edge), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Edges.CreateAsync("Region", "Region", EdgeCreate.Builder().ParentName("Total").ComponentName("West").Weight(1.0).Build());
        Assert.Equal(("POST", Edges, """{"ParentName":"Total","ComponentName":"West","Weight":1}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Edges.UpdateAsync("Region", "Region", "Total", "O'Brien", EdgePatch.Builder().Weight(-1.0).Build());
        Assert.Equal(("PATCH", Edge, """{"Weight":-1}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.Edges.DeleteAsync("Region", "Region", "Total", "O'Brien");
        Assert.Equal(("DELETE", Edge), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task Navigations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Total" };
        Assert.Equal("Total", (await tm1.Edges.ParentAsync("Region", "Region", "Total", "O'Brien", ODataQuery.None().Select("Name"))).Name);
        Assert.Equal(Edge + "/Parent?$select=Name", _tm1.Last.Target);
        await tm1.Edges.ComponentAsync("Region", "Region", "Total", "O'Brien");
        Assert.Equal(Edge + "/Component", _tm1.Last.Target);
        await tm1.Edges.HierarchyAsync("Region", "Region", "Total", "O'Brien");
        Assert.Equal(Edge + "/Hierarchy", _tm1.Last.Target);
    }
}

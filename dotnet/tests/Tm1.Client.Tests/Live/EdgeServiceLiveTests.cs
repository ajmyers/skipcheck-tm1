// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>EdgeService against a real TM1: a port of the Java client's EdgeServiceTm1Test.</summary>
public class EdgeServiceLiveTests
{
    private static Task<LiveDimension.Made> ElementsDimension() =>
        LiveDimension.Made.Of(n =>
            LiveDimension.DimensionOf(
                n,
                [
                    LiveDimension.Consolidated("Total"),
                    LiveDimension.Numeric("North"),
                    LiveDimension.Numeric("South"),
                    LiveDimension.Numeric("East"),
                    LiveDimension.Text("Label"),
                ],
                [LiveDimension.Edge("Total", "North"), LiveDimension.Edge("Total", "South")]
            )
        );

    private static async Task<List<string>> Components(Tm1Client tm1, string d, string parent) =>
        LiveDimension.Names(await tm1.Elements.ComponentsAsync(d, d, parent).ToListAsync());

    [Tm1Fact]
    public async Task CreatesReadsReweightsAndDeletesEdges()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        var edges = tm1.Edges;
        Assert.Equal(2, (await edges.ListAsync(d, d).ToListAsync()).Count);
        Assert.Equal(1.0, (await edges.GetAsync(d, d, "Total", "North")).Weight);
        await edges.UpdateAsync(d, d, "Total", "North", EdgePatch.Builder().Weight(3.0).Build());
        Assert.Equal(3.0, (await edges.GetAsync(d, d, "Total", "North")).Weight);
        Assert.Equal("Total", (await edges.ParentAsync(d, d, "Total", "North")).Name);
        Assert.Equal("North", (await edges.ComponentAsync(d, d, "Total", "North")).Name);
        Assert.Equal(d, (await edges.HierarchyAsync(d, d, "Total", "North")).Name);
        Assert.Equal("East", (await edges.CreateAsync(d, d, LiveDimension.Edge("Total", "East"))).ComponentName);
        await edges.DeleteAsync(d, d, "Total", "North");
        Assert.Equal(["South", "East"], await Components(tm1, d, "Total"));
        Assert.Empty(await tm1.Elements.ParentsAsync(d, d, "North").ToListAsync()); // still an element, now a root
    }
}

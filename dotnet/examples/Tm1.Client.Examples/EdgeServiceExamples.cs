using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;

namespace Examples;

/// <summary>Examples for <c>EdgeService</c>, shown in the guide.</summary>
public static class EdgeServiceExamples
{
    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // Every parent-child link of the hierarchy, as "parent > child (weight)"
        return tm1.Edges.ListAsync("Region", "Region").Select(edge => $"{edge.ParentName} > {edge.ComponentName} ({edge.Weight})");
        // @end
    }

    public static async Task Reweight(Tm1Client tm1)
    {
        // @start region = reweight
        // Make Cost count negatively in Margin
        await tm1.Edges.UpdateAsync("Account", "Account", "Margin", "Cost", EdgePatch.Builder().Weight(-1.0).Build());
        // @end
    }

    public static async Task<Edge> Move(Tm1Client tm1)
    {
        // @start region = move
        // Move North from Total to Total Sales: add the new edge, then delete the old
        var edge = await tm1.Edges.CreateAsync("Region", "Region", EdgePatch.Builder().ParentName("Total Sales").ComponentName("North").Weight(1.0).Build());
        await tm1.Edges.DeleteAsync("Region", "Region", "Total", "North");
        return edge;
        // @end
    }
}

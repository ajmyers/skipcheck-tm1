// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>SandboxService</c>, shown in the guide.</summary>
public static class SandboxServiceExamples
{
    public static async Task Publish(Tm1Client tm1)
    {
        // @start region = publish
        // Write to a new sandbox, leaving base data alone until the changes are published
        var cell = new Dictionary<string, string>
        {
            ["Version"] = "Budget",
            ["Region"] = "North",
            ["Month"] = "Jan",
            ["Measure"] = "Revenue",
        };
        var whatIf = tm1.WithSandbox("What If");
        await tm1.Sandboxes.CreateAsync(SandboxCreate.Builder().Name("What If").Build());
        await whatIf.Cells.UpdateAsync("Sales", cell, 1500);
        await tm1.Sandboxes.PublishAsync("What If");
        // @end
    }

    public static async Task<string> Compare(Tm1Client tm1)
    {
        // @start region = compare
        // The same cell read in base data and in a sandbox: the same query, through a
        // client for each
        var mdx =
            "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales]"
            + " WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])";
        var values = ODataQuery.None().Select(CellsetCell.Fields.Value);
        var inBase = tm1.Cellsets.StreamAsync(mdx, values, 1, (cellset, cells) => cells.Select(cell => cell.Value)).FirstAsync();
        var inWhatIf = tm1.WithSandbox("What If").Cellsets.StreamAsync(mdx, values, 1, (cellset, cells) => cells.Select(cell => cell.Value)).FirstAsync();
        return $"base {await inBase}, what-if {await inWhatIf}";
        // @end
    }
}

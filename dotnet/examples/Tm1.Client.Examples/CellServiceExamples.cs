using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>CellService</c>, shown in the guide.</summary>
public static class CellServiceExamples
{
    public static async Task Update(Tm1Client tm1)
    {
        // @start region = update
        // The cell: an element for every dimension of the cube, in the cube's order
        var cell = new Dictionary<string, string>
        {
            ["Version"] = "Budget",
            ["Region"] = "North",
            ["Month"] = "Jan",
            ["Measure"] = "Revenue",
        };
        await tm1.Cells.UpdateAsync("Sales", cell, 1200);
        // @end
    }

    public static async Task UpdateCells(Tm1Client tm1)
    {
        // @start region = update-cells
        // A budget for North, one value per month, written in one request
        var budget = new Dictionary<string, int>
        {
            ["Jan"] = 1200,
            ["Feb"] = 1350,
            ["Mar"] = 1100,
        };
        var updates = budget
            .Select(month =>
            {
                var cell = new Dictionary<string, string>
                {
                    ["Version"] = "Budget",
                    ["Region"] = "North",
                    ["Month"] = month.Key,
                    ["Measure"] = "Revenue",
                };
                return CubeUpdatePatch.Builder().BindTuple(Tuples.Of(cell)).Value(month.Value).Build();
            })
            .ToList();
        await tm1.Cells.UpdateCellsAsync("Sales", updates);
        // @end
    }
}

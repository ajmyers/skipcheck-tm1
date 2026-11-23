// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>The walkthrough "Read and write cells" (api-site/content/walkthroughs/tm1-client), on SData's
/// SalesCube. The live tests run its steps and record what they print (WalkthroughExamplesLiveTests).</summary>
public static class CellsWalkthrough
{
    public static async Task Main()
    {
        var tm1 = ConnectWalkthrough.Connect();
        try
        {
            await Read(tm1);
            await Write(tm1);
            await ReadBack(tm1);
        }
        finally
        {
            await tm1.CloseAsync();
        }
    }

    public static async Task Read(Tm1Client tm1)
    {
        // @start region = read
        // Units of one model sold in France, January to March
        var mdx = """
            SELECT {[month].[month].[Jan], [month].[month].[Feb], [month].[month].[Mar]} ON COLUMNS
            FROM [SalesCube]
            WHERE ([actvsbud].[actvsbud].[Actual], [region].[region].[France],
              [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])
            """;
        // TM1 keeps the result as a cellset: get its cells' values with it, then delete it
        var cellset = await tm1.Cellsets.ExecuteAsync(mdx, ODataQuery.None().Expand(Cellset.Fields.Cells, cells => cells.Select(CellsetCell.Fields.Value)));
        string[] months = ["Jan", "Feb", "Mar"];
        for (var i = 0; i < months.Length; i++)
        {
            Console.WriteLine($"{months[i]}: {cellset.Cells![i].Value}");
        }
        await tm1.Cellsets.DeleteAsync(cellset.Id!);
        // @end
    }

    public static async Task Write(Tm1Client tm1)
    {
        // @start region = write
        // A cell is an element of each of the cube's dimensions, in the cube's order
        var cell = new Dictionary<string, string>
        {
            ["actvsbud"] = "Budget",
            ["region"] = "France",
            ["model"] = "S Series 1.8 L Sedan",
            ["account1"] = "Units",
            ["month"] = "Jan",
        };
        await tm1.Cells.UpdateAsync("SalesCube", cell, 150);
        // @end
    }

    public static async Task ReadBack(Tm1Client tm1)
    {
        // @start region = read-back
        var mdx =
            "SELECT {[month].[month].[Jan]} ON COLUMNS FROM [SalesCube]"
            + " WHERE ([actvsbud].[actvsbud].[Budget], [region].[region].[France],"
            + " [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])";
        var cellset = await tm1.Cellsets.ExecuteAsync(mdx, ODataQuery.None().Expand(Cellset.Fields.Cells, cells => cells.Select(CellsetCell.Fields.Value)));
        Console.WriteLine($"Budget for Jan: {cellset.Cells![0].Value}");
        await tm1.Cellsets.DeleteAsync(cellset.Id!);
        // @end
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>CellsetService</c>, shown in the guide.</summary>
public static class CellsetServiceExamples
{
    public static IAsyncEnumerable<string> Stream(Tm1Client tm1)
    {
        // @start region = stream
        var mdx = """
            SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb], [Month].[Month].[Mar]} ON 0,
              {TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)} ON 1
            FROM [Sales]
            WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])
            """;
        return tm1.Cellsets.StreamAsync(mdx, ODataQuery.None().Select(CellsetCell.Fields.Ordinal, CellsetCell.Fields.Value), 10_000, (cellset, cells) =>
        {
            // Axis 0 is the columns, axis 1 the rows; ordinals run along a row
            var columns = cellset.Axes![0].Tuples!;
            var rows = cellset.Axes[1].Tuples!;
            return cells.Select(cell =>
            {
                var row = rows[(int)(cell.Ordinal!.Value / columns.Count)];
                var column = columns[(int)(cell.Ordinal.Value % columns.Count)];
                return $"{row.Members![0].Name}, {column.Members![0].Name}: {cell.Value}";
            });
        });
        // @end
    }

    public static async Task Write(Tm1Client tm1)
    {
        // @start region = write
        // Write by ordinal into the cells of a query, then delete its cellset; the cells
        // must be leaves, as TM1 doesn't spread to a consolidation
        var cellsets = tm1.Cellsets;
        var mdx =
            "SELECT {[Month].[Month].[Jan], [Month].[Month].[Feb]} ON 0, {[Region].[Region].[North]} ON 1"
            + " FROM [Sales] WHERE ([Version].[Version].[Budget], [Measure].[Measure].[Revenue])";
        var cellset = await cellsets.ExecuteAsync(mdx);
        try
        {
            await cellsets.UpdateCellsAsync(
                cellset.Id!,
                [CellsetUpdatePatch.Builder().Ordinal(0).Value(1200).Build(), CellsetUpdatePatch.Builder().Ordinal(1).Value(1350).Build()]
            );
        }
        finally
        {
            await cellsets.DeleteAsync(cellset.Id!);
        }
        // @end
    }

    public static async Task<List<JsonElement?>> View(Tm1Client tm1)
    {
        // @start region = view
        // The values of a saved view, read in the same round trip as its cellset
        var cellsets = tm1.Cellsets;
        var cellset = await cellsets.ExecuteViewAsync(
            "Sales",
            ViewScope.Public,
            "Revenue by Region",
            ODataQuery.None().Expand(Cellset.Fields.Cells, cells => cells.Select(CellsetCell.Fields.Value))
        );
        try
        {
            return cellset.Cells!.Select(cell => cell.Value).ToList();
        }
        finally
        {
            await cellsets.DeleteAsync(cellset.Id!);
        }
        // @end
    }
}

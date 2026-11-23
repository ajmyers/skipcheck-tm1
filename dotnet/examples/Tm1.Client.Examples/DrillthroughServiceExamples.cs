using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>DrillthroughService</c>, shown in the guide.</summary>
public static class DrillthroughServiceExamples
{
    public static async Task<IReadOnlyList<CellsetCell>> Cell(Tm1Client tm1)
    {
        // @start region = cell
        // The detail behind one cell: query it, run its first cube drillthrough, and read
        // the cellset that returns. Both cellsets are deleted at the end.
        var cellsets = tm1.Cellsets;
        var drillthrough = tm1.Drillthrough;
        var mdx =
            "SELECT {[Month].[Month].[Jan]} ON 0, {[Region].[Region].[North]} ON 1 FROM [Sales]"
            + " WHERE ([Version].[Version].[Actual], [Measure].[Measure].[Revenue])";
        var cells = ODataQuery.None().Expand(Cellset.Fields.Cells, c => c.Select(CellsetCell.Fields.Ordinal, CellsetCell.Fields.Value));
        var source = await cellsets.ExecuteAsync(mdx);
        try
        {
            var script = await drillthrough.ScriptsAsync(source.Id!, 0).OfType<CubeDrillthrough>().FirstOrDefaultAsync();
            if (script is null)
            {
                return [];
            }
            var detail = await drillthrough.ExecuteAsync(source.Id!, 0, script.Name!, cells);
            await cellsets.DeleteAsync(detail.Id!);
            return detail.Cells!;
        }
        finally
        {
            await cellsets.DeleteAsync(source.Id!);
        }
        // @end
    }

    public static async Task<IReadOnlyList<CellsetCell>> Process(Tm1Client tm1)
    {
        // @start region = process
        // A drillthrough process run directly, with the parameters it declares
        var detail = await tm1.Drillthrough.ExecuteProcessAsync(
            "}Drill_Sales Detail",
            [ProcessParameterPatch.Builder().Name("pRegion").Value("North").Build(), ProcessParameterPatch.Builder().Name("pMonth").Value("Jan").Build()],
            ODataQuery.None().Expand(Cellset.Fields.Cells, c => c.Select(CellsetCell.Fields.Value))
        );
        await tm1.Cellsets.DeleteAsync(detail.Id!);
        return detail.Cells!;
        // @end
    }

    public static async Task<List<string?>> Rowset(Tm1Client tm1)
    {
        // @start region = rowset
        // Rows from a relational drillthrough, kept as a rowset and streamed from it.
        // Each row's columns are in its dynamic properties.
        var drillthrough = tm1.Drillthrough;
        var rowset = await drillthrough.ExecuteRelationalProcessWithRowsetAsync("}Drill_Sales Orders", []);
        try
        {
            return await drillthrough.RowsAsync(rowset.Id!).Select(row => row.DynamicProperties!["OrderNumber"].GetString()).ToListAsync();
        }
        finally
        {
            await drillthrough.DeleteRowsetAsync(rowset.Id!);
        }
        // @end
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>CellService and cellset writes, on a cube of Region (with a consolidation) and Measure (with
/// a string element): Java's CellServiceTm1Test.</summary>
public sealed class CellServiceLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string Region => _cube + "-region";
    private string Measure => _cube + "-measure";
    private CellService Cells => _tm1.Cells;

    public async Task InitializeAsync()
    {
        await LiveModel.DimensionAsync(
            _tm1,
            Region,
            [
                LiveModel.Element("Total", ElementType.Consolidated),
                LiveModel.Element("North", ElementType.Numeric),
                LiveModel.Element("South", ElementType.Numeric),
            ],
            [LiveModel.Edge("Total", "North"), LiveModel.Edge("Total", "South")]
        );
        await LiveModel.DimensionAsync(_tm1, Measure, [LiveModel.Element("Amount", ElementType.Numeric), LiveModel.Element("Note", ElementType.String)], []);
        await _tm1.Cubes.CreateAsync(CubeCreate.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
    }

    public async Task DisposeAsync()
    {
        await LiveModel.Quietly(() => _tm1.Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, Region, Measure);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task WritesNumbersTextAndNullsCellByCell()
    {
        await Cells.UpdateAsync(_cube, Cell("North", "Amount"), 5);
        await Cells.UpdateAsync(_cube, Cell("South", "Amount"), 7.5);
        await Cells.UpdateAsync(_cube, Cell("North", "Note"), "hello");
        Assert.Equal(5.0, await Number("North", "Amount"));
        Assert.Equal(12.5, await Number("Total", "Amount")); // consolidated
        Assert.Equal("hello", (await Read("North", "Note"))!.Value.GetString());
        await Cells.UpdateAsync(_cube, Cell("North", "Amount"), null);
        Assert.Equal(0.0, await Number("North", "Amount")); // null clears
    }

    [Tm1Fact]
    public async Task NeedsAValueInEveryUpdateAndDoesntCopyFromAReference()
    {
        await Cells.UpdateAsync(_cube, Cell("North", "Amount"), 40);
        CubeUpdatePatch.PatchBuilder WithReference() =>
            CubeUpdatePatch
                .Builder()
                .BindTuple(Tuples.Of(Cell("South", "Amount")))
                .BindReferenceCube(ODataPath.Of("Cubes").Key(_cube))
                .BindReferenceCell(Tuples.Of(Cell("North", "Amount")));
        var e = await Assert.ThrowsAsync<Tm1BadRequestException>(() => Cells.UpdateCellsAsync(_cube, [WithReference().Build()]));
        Assert.Contains("Value", e.Message, StringComparison.Ordinal);
        await Cells.UpdateCellsAsync(_cube, [WithReference().Value(5).Build()]);
        Assert.Equal(5.0, await Number("South", "Amount")); // the value, not the reference's 40
    }

    [Tm1Fact]
    public async Task WritesOnlyTheFirstOfSeveralCellsAndRefusesConsolidations()
    {
        // Through the raw session: the service only offers what TM1 does
        await _tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                ODataPath.Of("Cubes").Key(_cube).Action("tm1.Update"),
                new Dictionary<string, object?>
                {
                    ["Cells"] = new[] { Paths(Cell("North", "Amount")), Paths(Cell("South", "Amount")) }
                        .Select(p => new Dictionary<string, object?> { ["Tuple@odata.bind"] = p })
                        .ToList(),
                    ["Value"] = 3,
                }
            )
        );
        Assert.Equal(3.0, await Number("North", "Amount"));
        Assert.Equal(0.0, await Number("South", "Amount")); // only the first cell is written
        var e = await Assert.ThrowsAsync<Tm1BadRequestException>(() => Cells.UpdateAsync(_cube, Cell("Total", "Amount"), 100));
        Assert.Contains("Consolidated", e.Message, StringComparison.Ordinal); // no spread to a consolidation
    }

    [Tm1Fact]
    public async Task WritesManyCellsInOneRequest()
    {
        await Cells.UpdateCellsAsync(_cube, [Write("North", "Amount", 10), Write("South", "Amount", 20), Write("North", "Note", "n")]);
        Assert.Equal(30.0, await Number("Total", "Amount"));
        Assert.Equal("n", (await Read("North", "Note"))!.Value.GetString());
        await Cells.UpdateCellsAsync(_cube, [Write("North", "Amount", 1), Write("South", "Amount", 2)], UpdateOrder.ByLevel);
        Assert.Equal(3.0, await Number("Total", "Amount"));
    }

    [Tm1Fact]
    public async Task WritesThroughACellsetAndSavesItAsAView()
    {
        var mdx = $"SELECT {{[{Measure}].[{Measure}].[Amount]}} ON 0, {{[{Region}].[{Region}].[North], [{Region}].[{Region}].[South]}} ON 1 FROM [{_cube}]";
        var id = (await _tm1.Cellsets.ExecuteAsync(mdx)).Id!;
        try
        {
            await _tm1.Cellsets.UpdateAsync(id, 0, 4);
            Assert.Equal(4.0, await Number("North", "Amount"));
            await _tm1.Cellsets.UpdateAsync(id, 1, 6);
            Assert.Equal(10.0, await Number("Total", "Amount"));

            await _tm1.Session.ExecuteAsync(
                Tm1Request.Post(
                    ODataPath.Of("Cellsets").Key(id).Action("tm1.Update"),
                    new Dictionary<string, object?>
                    {
                        ["BeginOrdinal"] = 0,
                        ["EndOrdinal"] = 1,
                        ["Value"] = 9,
                    }
                )
            );
            Assert.Equal(15.0, await Number("Total", "Amount")); // a range writes only its first cell

            await _tm1.Cellsets.UpdateCellsAsync(
                id,
                [CellsetUpdatePatch.Builder().Ordinal(0).Value(1).Build(), CellsetUpdatePatch.Builder().Ordinal(1).Value(2).Build()]
            );
            Assert.Equal(3.0, await Number("Total", "Amount"));

            Assert.Equal("Saved", (await _tm1.Cellsets.SaveViewAsAsync(id, "Saved")).Name);
            Assert.Equal("Saved", (await _tm1.Views.GetAsync(_cube, ViewScope.Public, "Saved")).Name);
            Assert.Equal("Mine", (await _tm1.Cellsets.SaveViewAsAsync(id, "Mine", false, true)).Name);
            Assert.Equal("Mine", (await _tm1.Views.GetAsync(_cube, ViewScope.Private, "Mine")).Name);
        }
        finally
        {
            await _tm1.Cellsets.DeleteAsync(id);
        }
    }

    private Dictionary<string, string> Cell(string region, string measure) => new() { [Region] = region, [Measure] = measure };

    private static List<string> Paths(Dictionary<string, string> tuple) => Tuples.Of(tuple).Select(p => p.ToString()).ToList();

    private CubeUpdatePatch Write(string region, string measure, object value) =>
        CubeUpdatePatch.Builder().BindTuple(Tuples.Of(Cell(region, measure))).Value(value).Build();

    private Task<double> Number(string region, string measure) => LiveModel.NumberAsync(_tm1, Mdx(region, measure));

    private Task<JsonElement?> Read(string region, string measure) => LiveModel.ValueAsync(_tm1, Mdx(region, measure));

    private string Mdx(string region, string measure) =>
        $"SELECT {{[{Measure}].[{Measure}].[{measure}]}} ON 0, {{[{Region}].[{Region}].[{region}]}} ON 1 FROM [{_cube}]";
}

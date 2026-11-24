// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>CellsetService on a 2 × 4 cube whose cells hold 1 to 8 in ordinal order: Java's
/// CellsetServiceTm1Test.</summary>
public sealed class CellsetServiceLiveTests : IAsyncLifetime
{
    private static readonly string[] Regions = ["N", "S", "E", "W"];
    private static readonly string[] Measures = ["Amount", "Count"];

    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string Region => _cube + "-region";
    private string Measure => _cube + "-measure";
    private CellsetService Cellsets => _tm1.Cellsets;
    private string Mdx => $"SELECT {{[{Measure}].[{Measure}].Members}} ON 0, {{[{Region}].[{Region}].Members}} ON 1 FROM [{_cube}]";

    public async Task InitializeAsync()
    {
        await LiveModel.DimensionAsync(_tm1, Region, Regions);
        await LiveModel.DimensionAsync(_tm1, Measure, Measures);
        await _tm1.Cubes.CreateAsync(CubeCreate.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
        // Ordinals run across the columns (measures) first: cell (row r, column c) is r * 2 + c
        var value = 1;
        foreach (var r in Regions)
        {
            foreach (var m in Measures)
            {
                await LiveModel.WriteAsync(
                    _tm1,
                    _cube,
                    [LiveModel.Element(Region, r), LiveModel.Element(Measure, m)],
                    (value++).ToString(System.Globalization.CultureInfo.InvariantCulture)
                );
            }
        }
    }

    public async Task DisposeAsync()
    {
        await LiveModel.Quietly(() => _tm1.Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, Region, Measure);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task RunsMdxAndReadsTheCellsetPieceByPiece()
    {
        var cellset = await Cellsets.ExecuteAsync(Mdx, ODataQuery.None().Expand("Axes").Expand("Cells", c => c.Select("Ordinal", "Value")));
        var id = cellset.Id!;
        Assert.Equal(3, cellset.Axes!.Count); // columns, rows and the slicer TM1 adds
        Assert.Equal(8, cellset.Cells!.Count);
        try
        {
            Assert.Empty(await Cellsets.ListAsync().ToListAsync()); // TM1 lists none, though each reads by ID
            Assert.Equal(id, (await Cellsets.GetAsync(id)).Id);
            Assert.Equal([2, 4, 1], (await Cellsets.AxesAsync(id).ToListAsync()).Select(a => a.Cardinality));
            Assert.Equal([Region], await Cellsets.HierarchiesAsync(id, 1).Select(h => h.Name).ToListAsync());
            Assert.Equal(4, (await Cellsets.TuplesAsync(id, 1).ToListAsync()).Count);
            Assert.Equal(["E"], await Cellsets.TupleMembersAsync(id, 1, 2).Select(m => m.Name).ToListAsync());
            Assert.Equal(
                [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
                Values(await Cellsets.CellsAsync(id, ODataQuery.None().Select("Ordinal", "Value")).ToListAsync())
            );
            Assert.Equal(["Base", "E", "Count"], await Cellsets.CellMembersAsync(id, 5).Select(m => m.Name).ToListAsync()); // Sandboxes' Base first
            Assert.Equal(_cube, (await Cellsets.CubeAsync(id)).Name);
            // A rectangle: column 0 of rows 1 and 2
            Assert.Equal([2L, 4L], await Cellsets.PartitionAsync(id, 2, 4, ODataQuery.None().Select("Ordinal")).Select(c => c.Ordinal).ToListAsync());
            // Whole rows
            Assert.Equal([2L, 3L, 4L, 5L], await Cellsets.PartitionAsync(id, 2, 5, ODataQuery.None().Select("Ordinal")).Select(c => c.Ordinal).ToListAsync());
        }
        finally
        {
            await Cellsets.DeleteAsync(id);
        }
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => Cellsets.GetAsync(id));
    }

    [Tm1Fact]
    public async Task StreamsCellsInOrderAcrossPagesAndDeletesTheCellset()
    {
        string? id = null;
        var values = await Cellsets
            .StreamAsync(Mdx, ODataQuery.None().Select("Ordinal", "Value"), 3, (cellset, cells) =>
            {
                id = cellset.Id;
                return cells.Select(c => c.Value!.Value.GetDouble());
            })
            .ToListAsync();
        Assert.Equal([1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0], values);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => Cellsets.GetAsync(id!)); // the cellset is deleted
    }

    [Tm1Fact]
    public async Task StreamsAtLeastARowPerPage()
    {
        Assert.Equal(
            [0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L],
            await Cellsets.StreamAsync(Mdx, ODataQuery.None().Select("Ordinal", "Value"), 1, (_, cells) => cells.Select(c => c.Ordinal)).ToListAsync()
        );
    }

    [Tm1Fact]
    public async Task DeletesTheCellsetWhenTheStreamIsCancelled()
    {
        string? id = null;
        var first = await Cellsets
            .StreamAsync(Mdx, ODataQuery.None().Select("Ordinal"), 2, (cellset, cells) =>
            {
                id = cellset.Id;
                return cells.Select(c => c.Ordinal);
            })
            .Take(3)
            .ToListAsync();
        Assert.Equal([0L, 1L, 2L], first);
        // Unlike Java's cancel, disposing the enumerator waits for the delete
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => Cellsets.GetAsync(id!));
    }

    [Tm1Fact]
    public async Task ExecutesMdxAndNativeViews()
    {
        await _tm1.Views.CreateAsync(_cube, ViewScope.Public, MDXViewCreate.Builder().Name("Mdx").Mdx(Mdx).Build());
        foreach (var d in new[] { Region, Measure })
        {
            await LiveModel.SubsetAllAsync(_tm1, d);
        }
        await _tm1.Views.CreateAsync(
            _cube,
            ViewScope.Public,
            NativeViewCreate
                .Builder()
                .Name("Native")
                .Columns([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(Measure)).Build()])
                .Rows([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(Region)).Build()])
                .Build()
        );
        foreach (var view in new[] { "Mdx", "Native" })
        {
            var cellset = await Cellsets.ExecuteViewAsync(_cube, ViewScope.Public, view, ODataQuery.None().Expand("Cells", c => c.Select("Value")));
            try
            {
                Assert.Equal([1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0], Values(cellset.Cells!));
            }
            finally
            {
                await Cellsets.DeleteAsync(cellset.Id!);
            }
        }
    }

    private static List<double> Values(IEnumerable<CellsetCell> cells) => cells.Select(c => c.Value!.Value.GetDouble()).ToList();
}

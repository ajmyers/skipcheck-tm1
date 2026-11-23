using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;
using Skipcheck.Tm1.Client.Session;

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
        await _tm1.Cubes.CreateAsync(CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
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
        await _tm1.Cubes.CreateAsync(CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
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
    public async Task DeletesTheCellsetWhenTheStreamIsAbandoned()
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
        await _tm1.Views.CreateAsync(_cube, ViewScope.Public, MDXViewPatch.Builder().Name("Mdx").Mdx(Mdx).Build());
        foreach (var d in new[] { Region, Measure })
        {
            await LiveModel.SubsetAllAsync(_tm1, d);
        }
        await _tm1.Views.CreateAsync(
            _cube,
            ViewScope.Public,
            NativeViewPatch
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

/// <summary>Drillthrough, through a cube whose drillthrough rule names a process that returns a view:
/// Java's DrillthroughTm1Test.</summary>
public sealed class DrillthroughLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube;
    private readonly string _process;
    private readonly string[] _dimensions;

    public DrillthroughLiveTests()
    {
        _cube = Live.Name();
        _process = "}Drill_" + _cube;
        _dimensions = [_cube + "-a", _cube + "-b"];
    }

    public async Task InitializeAsync()
    {
        foreach (var d in _dimensions)
        {
            await LiveModel.DimensionAsync(_tm1, d, "X", "Y");
            await LiveModel.SubsetAllAsync(_tm1, d);
        }
        await _tm1.Cubes.CreateAsync(
            CubePatch
                .Builder()
                .Name(_cube)
                .BindDimensions(LiveModel.Dim(_dimensions[0]), LiveModel.Dim(_dimensions[1]))
                .DrillthroughRules($"[] = S: '{_process["}Drill_".Length..]}';\n")
                .Build()
        );
        await _tm1.Views.CreateAsync(
            _cube,
            ViewScope.Public,
            NativeViewPatch
                .Builder()
                .Name("Detail")
                .Columns([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(_dimensions[1])).Build()])
                .Rows([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(_dimensions[0])).Build()])
                .Build()
        );
        // Processes are another service's; the raw session makes the drillthrough process here
        await _tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                ODataPath.Of("Processes"),
                ProcessPatch
                    .Builder()
                    .Name(_process)
                    .Parameters([Parameter("pCube"), Parameter("p0"), Parameter("p1")])
                    .EpilogProcedure($"ReturnViewHandle('{_cube}', 'Detail');\n")
                    .Build()
            )
        );
    }

    public async Task DisposeAsync()
    {
        await LiveModel.Quietly(() => _tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(_process))));
        await LiveModel.Quietly(() => _tm1.Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, _dimensions);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task RunsADrillthroughProcessDirectly()
    {
        var cellset = await _tm1.Drillthrough.ExecuteProcessAsync(_process, [], ODataQuery.None().Expand("Cells", c => c.Select("Ordinal")));
        try
        {
            Assert.Equal(4, cellset.Cells!.Count); // the Detail view's 2 × 2 cells
        }
        finally
        {
            await _tm1.Cellsets.DeleteAsync(cellset.Id!);
        }
    }

    [Tm1Fact]
    public async Task ListsAndRunsACellsDrillthrough()
    {
        var (a, b) = (_dimensions[0], _dimensions[1]);
        var source = await _tm1.Cellsets.ExecuteAsync($"SELECT {{[{b}].[{b}].[X]}} ON 0, {{[{a}].[{a}].[X]}} ON 1 FROM [{_cube}]");
        try
        {
            var scripts = await _tm1.Drillthrough.ScriptsAsync(source.Id!, 0).ToListAsync();
            Assert.IsType<CubeDrillthrough>(Assert.Single(scripts));
            var detail = await _tm1.Drillthrough.ExecuteAsync(source.Id!, 0, scripts[0].Name!, ODataQuery.None().Expand("Cells", c => c.Select("Ordinal")));
            try
            {
                Assert.Equal(4, detail.Cells!.Count);
            }
            finally
            {
                await _tm1.Cellsets.DeleteAsync(detail.Id!);
            }
        }
        finally
        {
            await _tm1.Cellsets.DeleteAsync(source.Id!);
        }
    }

    [Fact(Skip = "Relational drillthrough reads an ODBC data source, which the TM1 test container can't host")]
    public async Task RunsRelationalDrillthrough()
    {
        Assert.NotNull(await _tm1.Drillthrough.ExecuteRelationalProcessAsync("}Drill_Relational", []).ToListAsync());
        var rowset = (await _tm1.Drillthrough.ExecuteRelationalProcessWithRowsetAsync("}Drill_Relational", [])).Id!;
        Assert.NotNull(await _tm1.Drillthrough.RowsAsync(rowset).ToListAsync());
        await _tm1.Drillthrough.DeleteRowsetAsync(rowset);
    }

    private static ProcessParameterPatch Parameter(string name) =>
        ProcessParameterPatch.Builder().Name(name).Value("").Type(ProcessVariableType.String).Build();
}

/// <summary>Member sets, MDX statements, and model against control objects, on the server's own control
/// objects: Java's MemberSetServiceTm1Test and MdxAndModelTm1Test.</summary>
public sealed class MdxAndModelLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync() => await _tm1.DisposeAsync();

    [Tm1Fact]
    public async Task KeepsASetExpressionsResultByOrdinalButListsNone()
    {
        var set = await _tm1.MemberSets.ExecuteAsync(
            "{TM1SUBSETALL([}Clients].[}Clients])}",
            ODataQuery.None().Expand("Tuples", t => t.Expand("Members", m => m.Select("Name")))
        );
        Assert.Contains(set.Tuples!, t => t.Members!.Select(m => m.Name).SequenceEqual(["Admin"]));
        var ordinal = set.Ordinal!.Value;
        Assert.Equal(set.Cardinality, (await _tm1.MemberSets.GetAsync(ordinal)).Cardinality);
        Assert.Empty(await _tm1.MemberSets.ListAsync().ToListAsync()); // TM1 lists none
        await _tm1.MemberSets.DeleteAsync(ordinal);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.MemberSets.GetAsync(ordinal));
    }

    [Tm1Fact]
    public async Task RunsAnMdxStatementButNotCreateSet()
    {
        await _tm1.Cellsets.ExecuteStatementAsync("SELECT {} ON 0 FROM [}ClientGroups]");
        await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
            _tm1.Cellsets.ExecuteStatementAsync("CREATE SET [}Clients].[Probe] AS {TM1SUBSETALL([}Clients].[}Clients])}")
        );
    }

    [Tm1Fact]
    public async Task SeparatesModelAndControlObjects()
    {
        var model = await _tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)).Select(c => c.Name!).ToListAsync();
        var control = await _tm1.Cubes.ControlCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)).Select(c => c.Name!).ToListAsync();
        Assert.DoesNotContain(model, n => n.StartsWith('}'));
        Assert.NotEmpty(control);
        Assert.All(control, n => Assert.StartsWith("}", n, StringComparison.Ordinal));
        Assert.Contains("}ClientGroups", control);

        // Dimensions are another service's; the raw session reads them here
        var modelDims = await Names("ModelDimensions");
        var controlDims = await Names("ControlDimensions");
        Assert.DoesNotContain(modelDims, n => n.StartsWith('}'));
        Assert.NotEmpty(controlDims);
        Assert.All(controlDims, n => Assert.StartsWith("}", n, StringComparison.Ordinal));
        Assert.Contains("}Clients", controlDims);
    }

    private async Task<List<string>> Names(string function) =>
        await _tm1
            .Session.StreamAsync(Tm1Request.List<Dimension>(ODataPath.FunctionImport(function), ODataQuery.None().Select(Dimension.Fields.Name)))
            .Select(d => d.Name!)
            .ToListAsync();
}

/// <summary>Sandboxes and change sets, on a cube of two dimensions of X and Y: Java's
/// SandboxAndChangeSetTm1Test.</summary>
public sealed class SandboxAndChangeSetLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string A => _cube + "-a";
    private string B => _cube + "-b";

    public async Task InitializeAsync()
    {
        foreach (var d in new[] { A, B })
        {
            await LiveModel.DimensionAsync(_tm1, d, "X", "Y");
        }
        await _tm1.Cubes.CreateAsync(CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(A), LiveModel.Dim(B)).Build());
    }

    public async Task DisposeAsync()
    {
        foreach (var s in await _tm1.Sandboxes.ListAsync().ToListAsync())
        {
            await LiveModel.Quietly(() => _tm1.Sandboxes.DeleteAsync(s.Name!));
        }
        await LiveModel.Quietly(() => _tm1.Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, A, B);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task KeepsSandboxWritesApartUntilPublished()
    {
        await _tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("S").Build());
        Assert.Contains("S", await _tm1.Sandboxes.ListAsync().Select(s => s.Name).ToListAsync());
        await _tm1.WithSandbox("S").Cells.UpdateAsync(_cube, Cell("X"), 5);
        Assert.Equal(5.0, await Value(_tm1.WithSandbox("S"), "X"));
        Assert.Equal(0.0, await Value(_tm1, "X")); // base data untouched
        await _tm1.Sandboxes.PublishAsync("S");
        Assert.Equal(5.0, await Value(_tm1, "X"));
    }

    [Tm1Fact]
    public async Task MergesDiscardsLoadsAndUnloads()
    {
        await _tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("S").Build());
        await _tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("T").Build());
        await _tm1.WithSandbox("S").Cells.UpdateAsync(_cube, Cell("X"), 7);
        await _tm1.Sandboxes.MergeAsync("S", "T");
        Assert.Equal(7.0, await Value(_tm1.WithSandbox("T"), "X"));
        await _tm1.Sandboxes.DiscardChangesAsync("S");
        Assert.Equal(0.0, await Value(_tm1.WithSandbox("S"), "X"));
        await _tm1.Sandboxes.UnloadAsync("T");
        await _tm1.Sandboxes.LoadAsync("T");
        Assert.True((await _tm1.Sandboxes.GetAsync("T")).IsLoaded);
        await _tm1.Sandboxes.DeleteAsync("T");
        Assert.DoesNotContain("T", await _tm1.Sandboxes.ListAsync().Select(s => s.Name).ToListAsync());
    }

    [Tm1Fact]
    public async Task UndoesTheWritesOfAChangeSetOnly()
    {
        string? id = null;
        await _tm1.ChangeSets.WithinAsync(
            async (Tm1Session session) =>
            {
                id = session.ChangeSet();
                await Tm1Client.Of(session).Cells.UpdateAsync(_cube, Cell("X"), 5);
            }
        );
        await _tm1.Cells.UpdateAsync(_cube, Cell("Y"), 7);
        Assert.Equal(5.0, await Value(_tm1, "X"));
        await _tm1.ChangeSets.UndoAsync(id!);
        Assert.Equal(0.0, await Value(_tm1, "X")); // in the change set: reverted
        Assert.Equal(7.0, await Value(_tm1, "Y")); // outside it: kept
    }

    [Tm1Fact]
    public async Task BeginsAndEndsAChangeSetByHand()
    {
        var id = await _tm1.ChangeSets.BeginAsync();
        Assert.False(string.IsNullOrWhiteSpace(id));
        await _tm1.WithChangeSet(id).Cells.UpdateAsync(_cube, Cell("Y"), 3);
        await _tm1.ChangeSets.EndAsync(id);
        await _tm1.ChangeSets.UndoAsync(id);
        Assert.Equal(0.0, await Value(_tm1, "Y"));
    }

    private Dictionary<string, string> Cell(string element) => new() { [A] = element, [B] = element };

    private Task<double> Value(Tm1Client client, string element) =>
        LiveModel.NumberAsync(client, $"SELECT {{[{B}].[{B}].[{element}]}} ON 0, {{[{A}].[{A}].[{element}]}} ON 1 FROM [{_cube}]");
}

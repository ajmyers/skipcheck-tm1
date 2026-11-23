using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

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

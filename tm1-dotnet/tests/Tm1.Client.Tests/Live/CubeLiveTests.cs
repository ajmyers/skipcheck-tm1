using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>What the live tests build their cubes from: dimensions, subsets and cells, made through the
/// raw session (as the Java tests make what a later service owns), so these tests need only the cube and
/// data services.</summary>
internal static class LiveModel
{
    public static ODataPath Dim(string name) => ODataPath.Of("Dimensions").Key(name);

    public static ODataPath Subset(string dimension) => Dim(dimension).Nav("Hierarchies").Key(dimension).Nav("Subsets").Key("All");

    public static ODataPath Element(string dimension, string element) => Dim(dimension).Nav("Hierarchies").Key(dimension).Nav("Elements").Key(element);

    public static ElementPatch Element(string name, ElementType type) => ElementPatch.Builder().Name(name).Type(type).Build();

    public static EdgePatch Edge(string parent, string component) => EdgePatch.Builder().ParentName(parent).ComponentName(component).Weight(1.0).Build();

    /// <summary>A dimension of numeric elements.</summary>
    public static Task DimensionAsync(Tm1Client tm1, string name, params string[] elements) =>
        DimensionAsync(tm1, name, elements.Select(e => Element(e, ElementType.Numeric)).ToList(), []);

    public static Task DimensionAsync(Tm1Client tm1, string name, IReadOnlyList<ElementPatch> elements, IReadOnlyList<EdgePatch> edges)
    {
        var hierarchy = HierarchyPatch.Builder().Name(name).Elements(elements);
        if (edges.Count > 0)
        {
            hierarchy.Edges(edges);
        }
        return tm1.Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Dimensions"), DimensionPatch.Builder().Name(name).Hierarchies([hierarchy.Build()]).Build()));
    }

    /// <summary>The public subset <c>All</c> of a dimension's own hierarchy.</summary>
    public static Task SubsetAllAsync(Tm1Client tm1, string dimension) =>
        tm1.Session.ExecuteAsync(Tm1Request.Post(Dim(dimension).Nav("Hierarchies").Key(dimension).Nav("Subsets"),
            SubsetPatch.Builder().Name("All").Expression($"{{TM1SUBSETALL([{dimension}].[{dimension}])}}").Build()));

    public static async Task DeleteDimensionsAsync(Tm1Client tm1, params string[] names)
    {
        foreach (var name in names)
        {
            await Quietly(() => tm1.Session.ExecuteAsync(Tm1Request.Delete(Dim(name))));
        }
    }

    /// <summary>Writes a cell through the raw session's <c>tm1.Update</c>.</summary>
    public static Task WriteAsync(Tm1Client tm1, string cube, IEnumerable<ODataPath> tuple, object value) =>
        tm1.Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of("Cubes").Key(cube).Action("tm1.Update"), new Dictionary<string, object?>
        {
            ["Cells"] = new[] { new Dictionary<string, object?> { ["Tuple@odata.bind"] = tuple.Select(p => p.ToString()).ToList() } },
            ["Value"] = value,
        }));

    /// <summary>The value of the cell an MDX query of one cell selects; 0 for an empty one.</summary>
    public static async Task<double> NumberAsync(Tm1Client tm1, string mdx)
    {
        var value = await ValueAsync(tm1, mdx);
        return value is { ValueKind: System.Text.Json.JsonValueKind.Number } v ? v.GetDouble() : 0;
    }

    public static async Task<System.Text.Json.JsonElement?> ValueAsync(Tm1Client tm1, string mdx) =>
        await tm1.Cellsets.StreamAsync(mdx, ODataQuery.None().Select("Ordinal", "Value"), 10, (_, cells) => cells.Select(c => c.Value)).FirstOrDefaultAsync();

    public static async Task Quietly(Func<Task> work)
    {
        try
        {
            await work();
        }
        catch (Tm1Exception)
        {
            // Gone already
        }
    }
}

/// <summary>CubeService, on a cube of two fresh dimensions, Region and Measure: Java's CubeServiceTm1Test.</summary>
public sealed class CubeServiceLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string Region => _cube + "-region";
    private string Measure => _cube + "-measure";
    private CubeService Cubes => _tm1.Cubes;

    public async Task InitializeAsync()
    {
        await LiveModel.DimensionAsync(_tm1, Region, "North", "South");
        await LiveModel.DimensionAsync(_tm1, Measure, "Amount");
        await Cubes.CreateAsync(CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
    }

    public async Task DisposeAsync()
    {
        await LiveModel.Quietly(() => Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, Region, Measure);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task CreatesReadsListsAndDeletesACube()
    {
        Assert.False(string.IsNullOrWhiteSpace((await Cubes.GetAsync(_cube)).ETag));
        var listed = await Cubes.ListAsync(ODataQuery.None().Select(Cube.Fields.Name).Filter(Filter.StartsWith(Cube.Fields.Name, _cube))).Select(c => c.Name).ToListAsync();
        Assert.Equal([_cube], listed);
        Assert.Equal(["Sandboxes", Region, Measure], await Names(Cubes.DimensionsAsync(_cube, ODataQuery.None().Select(Dimension.Fields.Name))));
        await Cubes.DeleteAsync(_cube);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => Cubes.GetAsync(_cube));
        // Its dimensions stay
        await _tm1.Session.ExecuteAsync(Tm1Request.Get<Dimension>(LiveModel.Dim(Region)));
    }

    [Tm1Fact]
    public async Task SavesRulesEvenIfTheyDontCompileAndChecksTheEtag()
    {
        var etag = (await Cubes.GetAsync(_cube)).ETag!;
        await Cubes.UpdateAsync(_cube, CubePatch.Builder().Rules("SKIPCHECK;\n").Build(), etag);
        Assert.Contains("SKIPCHECK", await Rules());
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => Cubes.UpdateAsync(_cube, CubePatch.Builder().Rules("").Build(), etag));
        await Cubes.UpdateAsync(_cube, CubePatch.Builder().Rules("['Nowhere'] = N: 1 +;\n").Build());
        Assert.Contains("Nowhere", await Rules()); // TM1 keeps rules that don't compile
        await Cubes.UpdateAsync(_cube, CubePatch.Builder().Rules(null).Build());
        Assert.True(string.IsNullOrEmpty(await Rules()));
    }

    [Tm1Fact]
    public async Task ReordersTheStorageOrder()
    {
        Assert.Equal(new[] { Region, Measure }.Order(StringComparer.Ordinal), (await Names(Cubes.DimensionsStorageOrderAsync(_cube))).Order(StringComparer.Ordinal));
        await Cubes.ReorderDimensionsAsync(_cube, [Measure, Region]);
        Assert.Equal([Measure, Region], await Names(Cubes.DimensionsStorageOrderAsync(_cube)));
        Assert.Equal(["Sandboxes", Region, Measure], await Names(Cubes.DimensionsAsync(_cube))); // presentation order unchanged
    }

    [Tm1Fact]
    public async Task LoadsUnloadsLocksAndReserves()
    {
        await Cubes.UnloadAsync(_cube);
        await Cubes.LoadAsync(_cube);
        await Cubes.LockAsync(_cube);
        await Cubes.UnlockAsync(_cube);
        await Cubes.ReserveAsync(_cube);
        await Cubes.ReleaseAsync(_cube);
    }

    private async Task<string?> Rules() => (await Cubes.GetAsync(_cube, ODataQuery.None().Select(Cube.Fields.Rules))).Rules;

    private static async Task<List<string?>> Names(IAsyncEnumerable<Dimension> dimensions) => await dimensions.Select(d => d.Name).ToListAsync();
}

/// <summary>Rules and feeders on a cube with a consolidation: Java's CubeRulesTm1Test.</summary>
public sealed class CubeRulesLiveTests : IAsyncLifetime
{
    private const string RulesText = "SKIPCHECK;\n['Double'] = N: ['Amount'] * 2;\nFEEDERS;\n['Amount'] => ['Double'];\n";

    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string Region => _cube + "-region";
    private string Measure => _cube + "-measure";
    private CubeService Cubes => _tm1.Cubes;

    public async Task InitializeAsync()
    {
        await LiveModel.DimensionAsync(_tm1, Region,
            [LiveModel.Element("Total", ElementType.Consolidated), LiveModel.Element("North", ElementType.Numeric), LiveModel.Element("South", ElementType.Numeric)],
            [LiveModel.Edge("Total", "North"), LiveModel.Edge("Total", "South")]);
        await LiveModel.DimensionAsync(_tm1, Measure, "Amount", "Double");
        await Cubes.CreateAsync(CubePatch.Builder().Name(_cube).Rules(RulesText).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
    }

    public async Task DisposeAsync()
    {
        await LiveModel.Quietly(() => Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, Region, Measure);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task ChecksSavedAndProposedRules()
    {
        Assert.Empty(await Cubes.CheckRulesAsync(_cube).ToListAsync());
        var errors = await Cubes.CheckRulesAsync(_cube, "SKIPCHECK;\n['Double'] = N: ['Amount'] *;\n").ToListAsync();
        Assert.NotEmpty(errors);
        Assert.All(errors, e =>
        {
            Assert.Equal(2, e.LineNumber);
            Assert.False(string.IsNullOrWhiteSpace(e.Message));
        });
        Assert.Equal(RulesText, (await Cubes.GetAsync(_cube)).Rules); // checking doesn't save
    }

    [Tm1Fact]
    public async Task TracesACalculationAndFeeders()
    {
        var calculation = await Cubes.TraceCellCalculationAsync(_cube, Tuple("North", "Double"), ODataQuery.None().Expand("Tuple").Expand("Cube"));
        Assert.Contains(calculation.Statements!, s => s.Contains("['Amount'] * 2", StringComparison.Ordinal));
        Assert.Equal(_cube, calculation.Cube!.Name);
        Assert.Equal(["North", "Double"], calculation.Tuple!.Select(e => e.Name));

        var feeders = await Cubes.TraceFeedersAsync(_cube, Tuple("North", "Amount"), ODataQuery.None().Expand("FedCells/Tuple"));
        Assert.Contains(feeders.FedCells!, c => c.Tuple!.Select(e => e.Name).SequenceEqual(["North", "Double"]));
    }

    [Tm1Fact]
    public async Task ChecksFeedersUnderAConsolidation()
    {
        await LiveModel.WriteAsync(_tm1, _cube, [LiveModel.Element(Region, "North"), LiveModel.Element(Measure, "Amount")], "5");
        var cells = await Cubes.CheckFeedersAsync(_cube, Tuple("Total", "Double"), CheckFeedersSelection.Both, ODataQuery.None().Expand("Tuple")).ToListAsync();
        Assert.Contains(cells, c => c.Tuple!.Select(e => e.Name).SequenceEqual(["North", "Double"]) && c.Fed == true);
    }

    private Dictionary<string, string> Tuple(string region, string measure) => new() { [Region] = region, [Measure] = measure };
}

/// <summary>ViewService, on a cube of Region, Measure and Year: Java's ViewServiceTm1Test.</summary>
public sealed class ViewServiceLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string Region => _cube + "-region";
    private string Measure => _cube + "-measure";
    private string Year => _cube + "-year";
    private ViewService Views => _tm1.Views;

    public async Task InitializeAsync()
    {
        foreach (var (d, elements) in new[] { (Region, new[] { "North", "South" }), (Measure, new[] { "Amount" }), (Year, new[] { "2025", "2026" }) })
        {
            await LiveModel.DimensionAsync(_tm1, d, elements);
            await LiveModel.SubsetAllAsync(_tm1, d);
        }
        await _tm1.Cubes.CreateAsync(CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure), LiveModel.Dim(Year)).Build());
    }

    public async Task DisposeAsync()
    {
        await LiveModel.Quietly(() => _tm1.Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, Region, Measure, Year);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesBothKindsOfView()
    {
        await Views.CreateAsync(_cube, ViewScope.Public, MDXViewPatch.Builder().Name("Mdx").Mdx(Mdx()).Build());
        await Views.CreateAsync(_cube, ViewScope.Public, NativeView("Native"));
        var all = await Views.ListAsync(_cube, ViewScope.Public).ToListAsync();
        Assert.Equal(["Mdx", "Native"], all.Select(v => v.Name).Order());
        Assert.IsType<MDXView>(Assert.Single(all, v => v.Name == "Mdx"));
        Assert.IsType<NativeView>(Assert.Single(all, v => v.Name == "Native"));

        var etag = (await Views.GetAsync(_cube, ViewScope.Public, "Mdx")).ETag!;
        var changed = $"SELECT {{[{Measure}].[{Measure}].[Amount]}} ON 0 FROM [{_cube}]";
        await Views.UpdateAsync(_cube, ViewScope.Public, "Mdx", MDXViewPatch.Builder().Mdx(changed).Build(), etag);
        Assert.Equal(changed, Assert.IsType<MDXView>(await Views.GetAsync(_cube, ViewScope.Public, "Mdx")).Mdx);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => Views.UpdateAsync(_cube, ViewScope.Public, "Mdx", MDXViewPatch.Builder().Mdx(Mdx()).Build(), etag));

        Assert.Equal(_cube, (await Views.CubeAsync(_cube, ViewScope.Public, "Mdx")).Name);
        await Views.DeleteAsync(_cube, ViewScope.Public, "Mdx");
        Assert.Equal(["Native"], await Views.ListAsync(_cube, ViewScope.Public).Select(v => v.Name).ToListAsync());
    }

    [Tm1Fact]
    public async Task KeepsPrivateViewsApart()
    {
        await Views.CreateAsync(_cube, ViewScope.Private, MDXViewPatch.Builder().Name("Mine").Mdx(Mdx()).Build());
        Assert.Equal(["Mine"], await Views.ListAsync(_cube, ViewScope.Private).Select(v => v.Name).ToListAsync());
        Assert.Empty(await Views.ListAsync(_cube, ViewScope.Public).ToListAsync());
    }

    [Tm1Fact]
    public async Task ExpandsANativeViewsAxesThroughItsType()
    {
        await Views.CreateAsync(_cube, ViewScope.Public, NativeView("Native"));
        var read = await Views.GetNativeAsync(_cube, ViewScope.Public, "Native",
            ODataQuery.None().Expand("Columns/Subset").Expand("Rows/Subset").Expand("Titles/Subset").Expand("Titles/Selected"));
        Assert.Equal("All", Assert.Single(read.Columns!).Subset!.Name);
        Assert.Contains(Region, Assert.Single(read.Rows!).Subset!.UniqueName);
        Assert.Equal("2026", Assert.Single(read.Titles!).Selected!.Name);
    }

    [Tm1Fact]
    public async Task ExpandsNativeViewsAxesInAListWithMdxViews()
    {
        await Views.CreateAsync(_cube, ViewScope.Public, NativeView("Native"));
        await Views.CreateAsync(_cube, ViewScope.Public, MDXViewPatch.Builder().Name("Mdx").Mdx(Mdx()).Build());
        var all = await Views.ListAsync(_cube, ViewScope.Public, ODataQuery.None().Expand("tm1.NativeView/Columns/Subset")).ToListAsync();
        Assert.Equal("All", Assert.Single(Assert.Single(all.OfType<NativeView>()).Columns!).Subset!.Name);
        Assert.Single(all.OfType<MDXView>());
    }

    private NativeViewPatch NativeView(string name) => NativeViewPatch.Builder()
        .Name(name)
        .Columns([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(Measure)).Build()])
        .Rows([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(Region)).Build()])
        .Titles([ViewTitlePatch.Builder().BindSubset(LiveModel.Subset(Year)).BindSelected(LiveModel.Element(Year, "2026")).Build()])
        .Build();

    private string Mdx() => $"SELECT {{[{Measure}].[{Measure}].Members}} ON 0, {{[{Region}].[{Region}].Members}} ON 1 FROM [{_cube}]";
}

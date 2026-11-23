using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

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
        await _tm1.Cubes.CreateAsync(
            CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure), LiveModel.Dim(Year)).Build()
        );
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
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() =>
            Views.UpdateAsync(_cube, ViewScope.Public, "Mdx", MDXViewPatch.Builder().Mdx(Mdx()).Build(), etag)
        );

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
        var read = await Views.GetNativeAsync(
            _cube,
            ViewScope.Public,
            "Native",
            ODataQuery.None().Expand("Columns/Subset").Expand("Rows/Subset").Expand("Titles/Subset").Expand("Titles/Selected")
        );
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

    private NativeViewPatch NativeView(string name) =>
        NativeViewPatch
            .Builder()
            .Name(name)
            .Columns([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(Measure)).Build()])
            .Rows([ViewAxisSelectionPatch.Builder().BindSubset(LiveModel.Subset(Region)).Build()])
            .Titles([ViewTitlePatch.Builder().BindSubset(LiveModel.Subset(Year)).BindSelected(LiveModel.Element(Year, "2026")).Build()])
            .Build();

    private string Mdx() => $"SELECT {{[{Measure}].[{Measure}].Members}} ON 0, {{[{Region}].[{Region}].Members}} ON 1 FROM [{_cube}]";
}

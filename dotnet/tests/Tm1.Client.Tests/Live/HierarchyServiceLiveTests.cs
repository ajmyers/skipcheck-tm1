using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>HierarchyService against a real TM1, on a dimension of Total = North + South and East, a
/// second root: a port of the Java client's HierarchyServiceTm1Test.</summary>
public class HierarchyServiceLiveTests
{
    /// <summary>A dimension: Total = North + South, and East, a second root.</summary>
    private static Task<LiveDimension.Made> Region() =>
        LiveDimension.Made.Of(n =>
            LiveDimension.DimensionOf(
                n,
                [LiveDimension.Consolidated("Total"), LiveDimension.Numeric("North"), LiveDimension.Numeric("South"), LiveDimension.Numeric("East")],
                [LiveDimension.Edge("Total", "North"), LiveDimension.Edge("Total", "South")]
            )
        );

    private static HierarchyPatch Structure(string name) =>
        HierarchyPatch
            .Builder()
            .Name(name)
            .Elements([LiveDimension.Consolidated("Total"), LiveDimension.Numeric("North"), LiveDimension.Numeric("South"), LiveDimension.Numeric("East")])
            .Edges([LiveDimension.Edge("Total", "North"), LiveDimension.Edge("Total", "South")])
            .Build();

    private static async Task<List<string>> Roots(Tm1Client tm1, string d) =>
        LiveDimension.Names(await tm1.Hierarchies.RootMembersAsync(d, d, ODataQuery.None().Select(Member.Fields.Name)).ToListAsync());

    [Tm1Fact]
    public async Task CreatesListsReadsAndDeletesAHierarchy()
    {
        await using var made = await Region();
        var (hierarchies, d) = (made.Tm1.Hierarchies, made.Name);
        Assert.Equal("Alt", (await hierarchies.CreateAsync(d, Structure("Alt"))).Name);
        var listed = LiveDimension.Names(await hierarchies.ListAsync(d, ODataQuery.None().Select(Hierarchy.Fields.Name)).ToListAsync());
        Assert.Contains(d, listed);
        Assert.Contains("Alt", listed);
        Assert.Equal(4, (await hierarchies.GetAsync(d, "Alt", ODataQuery.None().Expand(Hierarchy.Fields.Elements))).Elements!.Count);
        Assert.Equal(d, (await hierarchies.DefaultHierarchyAsync(d)).Name);
        Assert.Equal(d, (await hierarchies.DimensionAsync(d, "Alt")).Name);
        await hierarchies.DeleteAsync(d, "Alt");
        Assert.DoesNotContain("Alt", LiveDimension.Names(await hierarchies.ListAsync(d).ToListAsync()));
    }

    [Tm1Fact]
    public async Task RefusesToReplaceElementsThroughAPlainUpdate()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        var e = Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Elements([]).Build());
        });
        Assert.Contains("ReplaceStructureAsync", e.Message);
        var north = ODataPath.Of("Dimensions").Key(d).Nav("Hierarchies").Key(d).Nav("Elements").Key("North");
        Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().BindElements(north).Build());
        });
        Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Edges([LiveDimension.Edge("Total", "East")]).Build(), "W/\"x\"");
        });
    }

    [Tm1Fact]
    public async Task ReplacesTheStructureDeletingWhatItDoesntList()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Hierarchies.ReplaceStructureAsync(
            d,
            d,
            [LiveDimension.Consolidated("Total"), LiveDimension.Numeric("North")],
            [LiveDimension.Edge("Total", "North")]
        );
        var elements = (await tm1.Hierarchies.GetAsync(d, d, ODataQuery.None().Expand(Hierarchy.Fields.Elements))).Elements!;
        Assert.Equal(["North", "Total"], LiveDimension.Names(elements).Order());
    }

    [Tm1Fact]
    public async Task UpdatesOnlyWhenTheEtagIsCurrentAndIgnoresVisible()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        var etag = (await tm1.Hierarchies.GetAsync(d, d)).ETag!;
        await tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Visible(false).Build(), etag);
        Assert.True((await tm1.Hierarchies.GetAsync(d, d)).Visible, "TM1 accepts Visible in a PATCH and ignores it");
        await tm1.Hierarchies.ReplaceStructureAsync(d, d, [LiveDimension.Numeric("North")], []);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Build(), etag));
    }

    [Tm1Fact]
    public async Task ReadsTheAllDefaultAndRootMembers()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Null(await tm1.Hierarchies.AllMemberAsync(d, d)); // no all member with two roots: TM1 sends 204
        Assert.False(string.IsNullOrEmpty((await tm1.Hierarchies.DefaultMemberAsync(d, d))!.Name));
        Assert.Equal(["Total", "East"], await Roots(tm1, d));
        await tm1.Hierarchies.ReplaceStructureAsync(
            d,
            d,
            [LiveDimension.Consolidated("Total"), LiveDimension.Numeric("North")],
            [LiveDimension.Edge("Total", "North")]
        );
        Assert.Equal("Total", (await tm1.Hierarchies.AllMemberAsync(d, d))!.Name); // with one root, the all member is that root
    }

    [Tm1Fact]
    public async Task SavesACopy()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Equal("Copy", (await tm1.Hierarchies.SaveAsAsync(d, d, "Copy")).Name);
        Assert.Equal("Copy", (await tm1.Hierarchies.SaveAsAsync(d, d, "Copy", true, false)).Name);
        Assert.Equal(4, (await tm1.Hierarchies.GetAsync(d, "Copy", ODataQuery.None().Expand(Hierarchy.Fields.Elements))).Elements!.Count);
    }

    [Tm1Fact]
    public async Task PlacesARootBeforeAnotherOrLastWithoutBefore()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Hierarchies.SetElementAsync(d, d, "East", "Total");
        Assert.Equal(["East", "Total"], await Roots(tm1, d));
        await tm1.Hierarchies.SetElementAsync(d, d, "East");
        Assert.Equal(["Total", "East"], await Roots(tm1, d)); // no Before puts it last, though $metadata says first
    }
}

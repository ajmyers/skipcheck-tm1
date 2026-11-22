using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>The dimension services against a real TM1: ports of the Java client's
/// DimensionServiceTm1Test, HierarchyServiceTm1Test, ElementServiceTm1Test,
/// LevelAndMemberServiceTm1Test and SubsetServiceTm1Test.</summary>
public class DimensionLiveTests
{
    internal static ElementPatch Numeric(string name) => ElementPatch.Builder().Name(name).Type(ElementType.Numeric).Build();

    internal static ElementPatch Consolidated(string name) => ElementPatch.Builder().Name(name).Type(ElementType.Consolidated).Build();

    internal static ElementPatch Text(string name) => ElementPatch.Builder().Name(name).Type(ElementType.String).Build();

    internal static EdgePatch Edge(string parent, string component) => EdgePatch.Builder().ParentName(parent).ComponentName(component).Weight(1.0).Build();

    internal static DimensionPatch DimensionOf(string name, IReadOnlyList<ElementPatch> elements, IReadOnlyList<EdgePatch>? edges = null)
    {
        var hierarchy = HierarchyPatch.Builder().Name(name).Elements(elements);
        if (edges is { Count: > 0 })
        {
            hierarchy = hierarchy.Edges(edges);
        }
        return DimensionPatch.Builder().Name(name).Hierarchies([hierarchy.Build()]).Build();
    }

    private static List<string> Names(IEnumerable<Element> items) => items.Select(i => i.Name!).ToList();

    private static List<string> Names(IEnumerable<Member> items) => items.Select(i => i.Name!).ToList();

    private static List<string> Names(IEnumerable<Hierarchy> items) => items.Select(i => i.Name!).ToList();

    private static List<string> Names(IEnumerable<Subset> items) => items.Select(i => i.Name!).ToList();

    /// <summary>A client and a dimension made for one test, deleted afterwards.</summary>
    internal sealed class Made : IAsyncDisposable
    {
        private Made(Tm1Client tm1, string name)
        {
            Tm1 = tm1;
            Name = name;
        }

        public Tm1Client Tm1 { get; }

        public string Name { get; }

        public static async Task<Made> Of(Func<string, DimensionPatch> patch)
        {
            var tm1 = Live.Client();
            var name = Live.Name();
            await tm1.Dimensions.CreateAsync(patch(name));
            return new Made(tm1, name);
        }

        public async ValueTask DisposeAsync()
        {
            try
            {
                await Tm1.Dimensions.DeleteAsync(Name);
            }
            catch (Tm1NotFoundException)
            {
                // The test deleted it
            }
            await Tm1.DisposeAsync();
        }
    }

    // DimensionServiceTm1Test

    [Tm1Fact]
    public async Task CreatesReadsListsAndDeletesADimension()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        Assert.Equal(name, (await tm1.Dimensions.CreateAsync(DimensionOf(name, [Numeric("North"), Numeric("South")]))).Name);
        var read = await tm1.Dimensions.GetAsync(name, ODataQuery.None().Expand(Dimension.Fields.Hierarchies, h => h.Expand(Hierarchy.Fields.Elements)));
        Assert.False(string.IsNullOrEmpty(read.ETag));
        var hierarchy = Assert.Single(read.Hierarchies!);
        Assert.Equal(["North", "South"], Names(hierarchy.Elements!).Order());
        var listed = await tm1.Dimensions.ListAsync(ODataQuery.None().Select(Dimension.Fields.Name).Filter(Filter.StartsWith(Dimension.Fields.Name, name))).ToListAsync();
        Assert.Equal(name, Assert.Single(listed).Name);
        await tm1.Dimensions.DeleteAsync(name);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.Dimensions.GetAsync(name));
    }

    [Tm1Fact]
    public async Task UpdatesADimensionOnlyWhenTheEtagIsCurrent()
    {
        await using var made = await Made.Of(n => DimensionOf(n, [Numeric("North"), Numeric("South")]));
        var (tm1, d) = (made.Tm1, made.Name);
        var etag = (await tm1.Dimensions.GetAsync(d)).ETag!;
        var patch = DimensionPatch.Builder().Name(d).Build();
        await tm1.Dimensions.UpdateAsync(d, patch, etag);
        await tm1.Dimensions.AddAllLeavesHierarchyAsync(d);
        Assert.NotEqual(etag, (await tm1.Dimensions.GetAsync(d)).ETag); // a new hierarchy changes it
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => tm1.Dimensions.UpdateAsync(d, patch, etag));
    }

    [Tm1Fact]
    public async Task IgnoresHierarchiesAndTheAllLeavesNameInAPatch()
    {
        await using var made = await Made.Of(n => DimensionOf(n, [Numeric("North"), Numeric("South")]));
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Dimensions.UpdateAsync(d, DimensionPatch.Builder()
            .AllLeavesHierarchyName("AllLeaves")
            .Hierarchies([HierarchyPatch.Builder().Name(d).Elements([Numeric("North"), Numeric("South"), Numeric("East")]).Build()])
            .Build());
        var read = await tm1.Dimensions.GetAsync(d, ODataQuery.None().Expand(Dimension.Fields.Hierarchies, h => h.Expand(Hierarchy.Fields.Elements)));
        Assert.True(string.IsNullOrEmpty(read.AllLeavesHierarchyName), "TM1 accepts the PATCH and ignores it");
        Assert.Equal(2, Assert.Single(read.Hierarchies!).Elements!.Count);
    }

    [Tm1Fact]
    public async Task SavesACopyAndAddsAnAllLeavesHierarchy()
    {
        await using var made = await Made.Of(n => DimensionOf(n, [Numeric("North"), Numeric("South")]));
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Equal(d + "-copy", (await tm1.Dimensions.SaveAsAsync(d, d + "-copy")).Name);
        Assert.Equal(d + "-copy", (await tm1.Dimensions.SaveAsAsync(d, d + "-copy", true, false)).Name);
        await tm1.Dimensions.DeleteAsync(d + "-copy");
        Assert.Equal("Leaves", (await tm1.Dimensions.AddAllLeavesHierarchyAsync(d)).Name);
        var hierarchies = Names((await tm1.Dimensions.GetAsync(d, ODataQuery.None().Expand(Dimension.Fields.Hierarchies))).Hierarchies!);
        Assert.Contains(d, hierarchies);
        Assert.Contains("Leaves", hierarchies);
    }

    [Tm1Fact]
    public async Task LocksAndReservesADimension()
    {
        await using var made = await Made.Of(n => DimensionOf(n, [Numeric("North")]));
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Dimensions.LockAsync(d);
        await tm1.Dimensions.UnlockAsync(d);
        await tm1.Dimensions.ReserveAsync(d);
        await tm1.Dimensions.ReleaseAsync(d);
    }

    // HierarchyServiceTm1Test

    /// <summary>A dimension: Total = North + South, and East, a second root.</summary>
    private static Task<Made> Region() => Made.Of(n => DimensionOf(n,
        [Consolidated("Total"), Numeric("North"), Numeric("South"), Numeric("East")],
        [Edge("Total", "North"), Edge("Total", "South")]));

    private static HierarchyPatch Structure(string name) => HierarchyPatch.Builder()
        .Name(name)
        .Elements([Consolidated("Total"), Numeric("North"), Numeric("South"), Numeric("East")])
        .Edges([Edge("Total", "North"), Edge("Total", "South")])
        .Build();

    private static async Task<List<string>> Roots(Tm1Client tm1, string d) =>
        Names(await tm1.Hierarchies.RootMembersAsync(d, d, ODataQuery.None().Select(Member.Fields.Name)).ToListAsync());

    [Tm1Fact]
    public async Task CreatesListsReadsAndDeletesAHierarchy()
    {
        await using var made = await Region();
        var (hierarchies, d) = (made.Tm1.Hierarchies, made.Name);
        Assert.Equal("Alt", (await hierarchies.CreateAsync(d, Structure("Alt"))).Name);
        var listed = Names(await hierarchies.ListAsync(d, ODataQuery.None().Select(Hierarchy.Fields.Name)).ToListAsync());
        Assert.Contains(d, listed);
        Assert.Contains("Alt", listed);
        Assert.Equal(4, (await hierarchies.GetAsync(d, "Alt", ODataQuery.None().Expand(Hierarchy.Fields.Elements))).Elements!.Count);
        Assert.Equal(d, (await hierarchies.DefaultHierarchyAsync(d)).Name);
        Assert.Equal(d, (await hierarchies.DimensionAsync(d, "Alt")).Name);
        await hierarchies.DeleteAsync(d, "Alt");
        Assert.DoesNotContain("Alt", Names(await hierarchies.ListAsync(d).ToListAsync()));
    }

    [Tm1Fact]
    public async Task RefusesToReplaceElementsThroughAPlainUpdate()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        var e = Assert.Throws<ArgumentException>(() => { _ = tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Elements([]).Build()); });
        Assert.Contains("ReplaceStructureAsync", e.Message);
        var north = ODataPath.Of("Dimensions").Key(d).Nav("Hierarchies").Key(d).Nav("Elements").Key("North");
        Assert.Throws<ArgumentException>(() => { _ = tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().BindElements(north).Build()); });
        Assert.Throws<ArgumentException>(() => { _ = tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Edges([Edge("Total", "East")]).Build(), "W/\"x\""); });
    }

    [Tm1Fact]
    public async Task ReplacesTheStructureDeletingWhatItDoesntList()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Hierarchies.ReplaceStructureAsync(d, d, [Consolidated("Total"), Numeric("North")], [Edge("Total", "North")]);
        var elements = (await tm1.Hierarchies.GetAsync(d, d, ODataQuery.None().Expand(Hierarchy.Fields.Elements))).Elements!;
        Assert.Equal(["North", "Total"], Names(elements).Order());
    }

    [Tm1Fact]
    public async Task UpdatesAHierarchyOnlyWhenTheEtagIsCurrentAndIgnoresVisible()
    {
        await using var made = await Region();
        var (tm1, d) = (made.Tm1, made.Name);
        var etag = (await tm1.Hierarchies.GetAsync(d, d)).ETag!;
        await tm1.Hierarchies.UpdateAsync(d, d, HierarchyPatch.Builder().Visible(false).Build(), etag);
        Assert.True((await tm1.Hierarchies.GetAsync(d, d)).Visible, "TM1 accepts Visible in a PATCH and ignores it");
        await tm1.Hierarchies.ReplaceStructureAsync(d, d, [Numeric("North")], []);
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
        await tm1.Hierarchies.ReplaceStructureAsync(d, d, [Consolidated("Total"), Numeric("North")], [Edge("Total", "North")]);
        Assert.Equal("Total", (await tm1.Hierarchies.AllMemberAsync(d, d))!.Name); // with one root, the all member is that root
    }

    [Tm1Fact]
    public async Task SavesACopyOfAHierarchy()
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

    // ElementServiceTm1Test

    private static Task<Made> ElementsDimension() => Made.Of(n => DimensionOf(n,
        [Consolidated("Total"), Numeric("North"), Numeric("South"), Numeric("East"), Text("Label")],
        [Edge("Total", "North"), Edge("Total", "South")]));

    private static async Task<List<string>> Components(Tm1Client tm1, string d, string parent) =>
        Names(await tm1.Elements.ComponentsAsync(d, d, parent).ToListAsync());

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesAnElement()
    {
        await using var made = await ElementsDimension();
        var (elements, d) = (made.Tm1.Elements, made.Name);
        Assert.Equal("West", (await elements.CreateAsync(d, d, Numeric("West"))).Name);
        Assert.Equal(new[] { "Total", "North", "South", "East", "Label", "West" }.Order(), Names(await elements.ListAsync(d, d, ODataQuery.None().Select(Element.Fields.Name)).ToListAsync()).Order());
        var etag = (await elements.GetAsync(d, d, "West")).ETag!;
        await elements.UpdateAsync(d, d, "West", ElementPatch.Builder().Type(ElementType.String).Build(), etag);
        Assert.Equal(ElementType.String, (await elements.GetAsync(d, d, "West")).Type);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => elements.UpdateAsync(d, d, "West", ElementPatch.Builder().Type(ElementType.Numeric).Build(), etag));
        await elements.DeleteAsync(d, d, "West");
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => elements.GetAsync(d, d, "West"));
    }

    [Tm1Fact]
    public async Task RefusesNameAndAttributesInAnUpdateAsTm1IgnoresOrRejectsThem()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Contains("renamed", Assert.Throws<ArgumentException>(() => { _ = tm1.Elements.UpdateAsync(d, d, "East", ElementPatch.Builder().Name("Est").Build()); }).Message);
        Assert.Throws<ArgumentException>(() => { _ = tm1.Elements.UpdateAsync(d, d, "East", ElementPatch.Builder().Name("Est").Build(), "W/\"x\""); });
        var east = ODataPath.Of("Dimensions").Key(d).Nav("Hierarchies").Key(d).Nav("Elements").Key("East");
        await tm1.Session.ExecuteAsync(Tm1Request.Patch(east, new Dictionary<string, object?> { ["Name"] = "Est" }));
        Assert.Equal("East", (await tm1.Elements.GetAsync(d, d, "East")).Name); // TM1 ignores the new name
        await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.Session.ExecuteAsync(Tm1Request.Patch(east, new Dictionary<string, object?> { ["Attributes"] = new Dictionary<string, object?> { ["Caption"] = "E" } })));
    }

    [Tm1Fact]
    public async Task NavigatesParentsComponentsEdgesAndTheHierarchy()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Equal(["Total"], Names(await tm1.Elements.ParentsAsync(d, d, "North").ToListAsync()));
        Assert.Equal(["North", "South"], await Components(tm1, d, "Total"));
        Assert.Equal([("Total", "North"), ("Total", "South")], (await tm1.Elements.EdgesAsync(d, d, "Total").ToListAsync()).Select(e => (e.ParentName, e.ComponentName)));
        Assert.Equal(d, (await tm1.Elements.HierarchyAsync(d, d, "North")).Name);
    }

    [Tm1Fact]
    public async Task SetsComponentsLastOrBeforeAnotherWithAWeight()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Elements.SetComponentAsync(d, d, "Total", "East");
        Assert.Equal(["North", "South", "East"], await Components(tm1, d, "Total")); // no Before puts it last
        await tm1.Elements.CreateAsync(d, d, Numeric("West"));
        await tm1.Elements.SetComponentAsync(d, d, "Total", "West", "North", 2.0);
        Assert.Equal(["West", "North", "South", "East"], await Components(tm1, d, "Total"));
        Assert.Equal(2.0, (await tm1.Edges.GetAsync(d, d, "Total", "West")).Weight);
        await tm1.Elements.CreateAsync(d, d, Numeric("Far"));
        await tm1.Elements.SetComponentAsync(d, d, "Total", "Far", -1.0);
        Assert.Equal(-1.0, (await tm1.Edges.GetAsync(d, d, "Total", "Far")).Weight);
    }

    [Tm1Fact]
    public async Task LocksAndReservesAnElement()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Elements.LockAsync(d, d, "North");
        await tm1.Elements.UnlockAsync(d, d, "North");
        await tm1.Elements.ReserveAsync(d, d, "North");
        await tm1.Elements.ReleaseAsync(d, d, "North");
    }

    [Tm1Fact]
    public async Task CreatesReadsReweightsAndDeletesEdges()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        var edges = tm1.Edges;
        Assert.Equal(2, (await edges.ListAsync(d, d).ToListAsync()).Count);
        Assert.Equal(1.0, (await edges.GetAsync(d, d, "Total", "North")).Weight);
        await edges.UpdateAsync(d, d, "Total", "North", EdgePatch.Builder().Weight(3.0).Build());
        Assert.Equal(3.0, (await edges.GetAsync(d, d, "Total", "North")).Weight);
        Assert.Equal("Total", (await edges.ParentAsync(d, d, "Total", "North")).Name);
        Assert.Equal("North", (await edges.ComponentAsync(d, d, "Total", "North")).Name);
        Assert.Equal(d, (await edges.HierarchyAsync(d, d, "Total", "North")).Name);
        Assert.Equal("East", (await edges.CreateAsync(d, d, Edge("Total", "East"))).ComponentName);
        await edges.DeleteAsync(d, d, "Total", "North");
        Assert.Equal(["South", "East"], await Components(tm1, d, "Total"));
        Assert.Empty(await tm1.Elements.ParentsAsync(d, d, "North").ToListAsync()); // still an element, now a root
    }

    // LevelAndMemberServiceTm1Test

    private static Task<Made> World() => Made.Of(n => DimensionOf(n,
        [Consolidated("World"), Consolidated("Europe"), Consolidated("Asia"), Numeric("France"), Numeric("Spain"), Numeric("Japan")],
        [Edge("World", "Europe"), Edge("World", "Asia"), Edge("Europe", "France"), Edge("Europe", "Spain"), Edge("Asia", "Japan")]));

    [Tm1Fact]
    public async Task ListsLevelsAndTheirMembers()
    {
        await using var made = await World();
        var (tm1, d) = (made.Tm1, made.Name);
        var levels = await tm1.Levels.ListAsync(d, d).ToListAsync();
        Assert.Equal([0, 1, 2], levels.Select(l => l.Number!.Value));
        Assert.Equal([1, 2, 3], levels.Select(l => l.Cardinality!.Value));
        Assert.Equal(3, (await tm1.Levels.GetAsync(d, d, 2)).Cardinality);
        Assert.Equal(["France", "Japan", "Spain"], Names(await tm1.Levels.MembersAsync(d, d, 2, ODataQuery.None().Select(Member.Fields.Name)).ToListAsync()).Order());
        Assert.Equal(d, (await tm1.Levels.HierarchyAsync(d, d, 0)).Name);
    }

    [Tm1Fact]
    public async Task NavigatesMembers()
    {
        await using var made = await World();
        var (members, d) = (made.Tm1.Members, made.Name);
        Assert.Equal(6, (await members.ListAsync(d, d).ToListAsync()).Count);
        Assert.Equal($"[{d}].[{d}].[Europe]", (await members.GetAsync(d, d, "Europe")).UniqueName);
        Assert.Equal("Europe", (await members.ParentAsync(d, d, "France"))!.Name);
        Assert.Null(await members.ParentAsync(d, d, "World")); // a root has none
        Assert.Equal(["France", "Spain"], Names(await members.ChildrenAsync(d, d, "Europe").ToListAsync()));
        Assert.Equal(ElementType.Numeric, (await members.ElementAsync(d, d, "Japan")).Type);
        Assert.Equal(2, (await members.LevelAsync(d, d, "Japan")).Number);
        Assert.Equal(d, (await members.HierarchyAsync(d, d, "Japan")).Name);
    }

    // SubsetServiceTm1Test

    private static Task<Made> Abcd() => Made.Of(n => DimensionOf(n, [Numeric("A"), Numeric("B"), Numeric("C"), Numeric("D")]));

    private static ODataPath El(string d, string name) => ODataPath.Of("Dimensions").Key(d).Nav("Hierarchies").Key(d).Nav("Elements").Key(name);

    private static async Task<List<string>> SubsetElements(Tm1Client tm1, string d, SubsetScope scope, string subset) =>
        Names(await tm1.Subsets.ElementsAsync(d, d, scope, subset, ODataQuery.None().Select(Element.Fields.Name)).ToListAsync());

    [Tm1Fact]
    public async Task CreatesStaticAndDynamicSubsetsReadsUpdatesAndDeletesThem()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var (subsets, pub) = (tm1.Subsets, SubsetScope.Public);
        await subsets.CreateAsync(d, d, pub, SubsetPatch.Builder().Name("Static").BindElements(El(d, "A"), El(d, "B")).Build());
        await subsets.CreateAsync(d, d, pub, SubsetPatch.Builder().Name("Dynamic").Expression($"{{TM1SUBSETALL([{d}].[{d}])}}").Build());
        Assert.Equal(["Dynamic", "Static"], Names(await subsets.ListAsync(d, d, pub).ToListAsync()).Order());
        Assert.Equal(["A", "B"], await SubsetElements(tm1, d, pub, "Static"));
        Assert.Equal(["A", "B", "C", "D"], (await SubsetElements(tm1, d, pub, "Dynamic")).Order());
        var etag = (await subsets.GetAsync(d, d, pub, "Dynamic")).ETag!;
        await subsets.UpdateAsync(d, d, pub, "Dynamic", SubsetPatch.Builder().Expression($"{{[{d}].[{d}].[C]}}").Build(), etag);
        Assert.Equal(["C"], await SubsetElements(tm1, d, pub, "Dynamic"));
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => subsets.UpdateAsync(d, d, pub, "Dynamic", SubsetPatch.Builder().Expression("{}").Build(), etag));
        Assert.Equal(d, (await subsets.HierarchyAsync(d, d, pub, "Static")).Name);
        await subsets.DeleteAsync(d, d, pub, "Static");
        Assert.Equal(["Dynamic"], Names(await subsets.ListAsync(d, d, pub).ToListAsync()));
    }

    [Tm1Fact]
    public async Task AppendsAndClearsElementsByNameAndRefusesThemInAnUpdate()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var pub = SubsetScope.Public;
        await tm1.Subsets.CreateAsync(d, d, pub, SubsetPatch.Builder().Name("S").BindElements(El(d, "A")).Build());
        await tm1.Subsets.AddElementsAsync(d, d, pub, "S", ["C", "B"]);
        Assert.Equal(["A", "C", "B"], await SubsetElements(tm1, d, pub, "S")); // appended, in order
        await tm1.Subsets.ClearElementsAsync(d, d, pub, "S");
        Assert.Empty(await SubsetElements(tm1, d, pub, "S"));
        var e = Assert.Throws<ArgumentException>(() => { _ = tm1.Subsets.UpdateAsync(d, d, pub, "S", SubsetPatch.Builder().BindElements(El(d, "D")).Build()); });
        Assert.Contains("AddElementsAsync", e.Message);
    }

    [Tm1Fact]
    public async Task PlacesElementsLastBeforeOrAfterOthers()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var pub = SubsetScope.Public;
        await tm1.Subsets.CreateAsync(d, d, pub, SubsetPatch.Builder().Name("S").BindElements(El(d, "A"), El(d, "B")).Build());
        await tm1.Subsets.SetElementAsync(d, d, pub, "S", "C");
        Assert.Equal(["A", "B", "C"], await SubsetElements(tm1, d, pub, "S")); // no position puts it last
        await tm1.Subsets.SetElementBeforeAsync(d, d, pub, "S", "D", "B");
        Assert.Equal(["A", "D", "B", "C"], await SubsetElements(tm1, d, pub, "S"));
        await tm1.Subsets.SetElementAfterAsync(d, d, pub, "S", "A", "B");
        Assert.Equal(["D", "B", "A", "C"], await SubsetElements(tm1, d, pub, "S")); // moved, not added again
    }

    [Tm1Fact]
    public async Task KeepsPrivateSubsetsApartAndSavesCopies()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Subsets.CreateAsync(d, d, SubsetScope.Private, SubsetPatch.Builder().Name("Mine").BindElements(El(d, "D")).Build());
        Assert.Equal(["Mine"], Names(await tm1.Subsets.ListAsync(d, d, SubsetScope.Private).ToListAsync()));
        Assert.Empty(await tm1.Subsets.ListAsync(d, d, SubsetScope.Public).ToListAsync());
        await tm1.Subsets.CreateAsync(d, d, SubsetScope.Public, SubsetPatch.Builder().Name("All").Expression($"{{TM1SUBSETALL([{d}].[{d}])}}").Build());
        Assert.False(string.IsNullOrEmpty((await tm1.Subsets.SaveAsAsync(d, d, SubsetScope.Public, "All", "Copy")).Expression));
        var frozen = await tm1.Subsets.SaveAsAsync(d, d, SubsetScope.Public, "All", "Frozen", true, true);
        Assert.True(string.IsNullOrEmpty(frozen.Expression), "static");
        Assert.Equal(["A", "B", "C", "D"], (await SubsetElements(tm1, d, SubsetScope.Private, "Frozen")).Order());
    }

    [Tm1Fact]
    public async Task CreatesSessionSubsetsUnderTheServersName()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var defined = await tm1.Subsets.CreateSessionSubsetAsync(d, d, SubsetPatch.Builder().Name("Wanted").Expression($"{{[{d}].[{d}].[B]}}").Build());
        Assert.NotEqual("Wanted", defined.Name); // TM1 ignores the name it's given
        Assert.Equal(["B"], await SubsetElements(tm1, d, SubsetScope.Session, defined.Name!));
        await tm1.Subsets.CreateAsync(d, d, SubsetScope.Public, SubsetPatch.Builder().Name("S").BindElements(El(d, "C"), El(d, "D")).Build());
        var copied = await tm1.Subsets.CreateSessionSubsetFromAsync(d, d, SubsetScope.Public, "S");
        Assert.Equal(["C", "D"], await SubsetElements(tm1, d, SubsetScope.Session, copied.Name!));
        Assert.Empty(await tm1.Subsets.ListAsync(d, d, SubsetScope.Session).ToListAsync()); // TM1 lists no session subsets, though each reads by its name
    }
}

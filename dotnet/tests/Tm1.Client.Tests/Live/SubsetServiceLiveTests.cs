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

/// <summary>SubsetService against a real TM1, on a dimension of A, B, C and D: a port of the Java client's
/// SubsetServiceTm1Test.</summary>
public class SubsetServiceLiveTests
{
    private static Task<LiveDimension.Made> Abcd() =>
        LiveDimension.Made.Of(n =>
            LiveDimension.DimensionOf(n, [LiveDimension.Numeric("A"), LiveDimension.Numeric("B"), LiveDimension.Numeric("C"), LiveDimension.Numeric("D")])
        );

    private static ODataPath El(string d, string name) => ODataPath.Of("Dimensions").Key(d).Nav("Hierarchies").Key(d).Nav("Elements").Key(name);

    private static async Task<List<string>> SubsetElements(Tm1Client tm1, string d, SubsetScope scope, string subset) =>
        LiveDimension.Names(await tm1.Subsets.ElementsAsync(d, d, scope, subset, ODataQuery.None().Select(Element.Fields.Name)).ToListAsync());

    [Tm1Fact]
    public async Task CreatesStaticAndDynamicSubsetsReadsUpdatesAndDeletesThem()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var (subsets, pub) = (tm1.Subsets, SubsetScope.Public);
        await subsets.CreateAsync(d, d, pub, SubsetCreate.Builder().Name("Static").BindElements(El(d, "A"), El(d, "B")).Build());
        await subsets.CreateAsync(d, d, pub, SubsetCreate.Builder().Name("Dynamic").Expression($"{{TM1SUBSETALL([{d}].[{d}])}}").Build());
        Assert.Equal(["Dynamic", "Static"], LiveDimension.Names(await subsets.ListAsync(d, d, pub).ToListAsync()).Order());
        Assert.Equal(["A", "B"], await SubsetElements(tm1, d, pub, "Static"));
        Assert.Equal(["A", "B", "C", "D"], (await SubsetElements(tm1, d, pub, "Dynamic")).Order());
        var etag = (await subsets.GetAsync(d, d, pub, "Dynamic")).ETag!;
        await subsets.UpdateAsync(d, d, pub, "Dynamic", SubsetPatch.Builder().Expression($"{{[{d}].[{d}].[C]}}").Build(), etag);
        Assert.Equal(["C"], await SubsetElements(tm1, d, pub, "Dynamic"));
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() =>
            subsets.UpdateAsync(d, d, pub, "Dynamic", SubsetPatch.Builder().Expression("{}").Build(), etag)
        );
        Assert.Equal(d, (await subsets.HierarchyAsync(d, d, pub, "Static")).Name);
        await subsets.DeleteAsync(d, d, pub, "Static");
        Assert.Equal(["Dynamic"], LiveDimension.Names(await subsets.ListAsync(d, d, pub).ToListAsync()));
    }

    [Tm1Fact]
    public async Task AppendsAndClearsElementsByNameAndRefusesThemInAnUpdate()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var pub = SubsetScope.Public;
        await tm1.Subsets.CreateAsync(d, d, pub, SubsetCreate.Builder().Name("S").BindElements(El(d, "A")).Build());
        await tm1.Subsets.AddElementsAsync(d, d, pub, "S", ["C", "B"]);
        Assert.Equal(["A", "C", "B"], await SubsetElements(tm1, d, pub, "S")); // appended, in order
        await tm1.Subsets.ClearElementsAsync(d, d, pub, "S");
        Assert.Empty(await SubsetElements(tm1, d, pub, "S"));
        var e = Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Subsets.UpdateAsync(d, d, pub, "S", SubsetPatch.Builder().BindElements(El(d, "D")).Build());
        });
        Assert.Contains("AddElementsAsync", e.Message);
    }

    [Tm1Fact]
    public async Task PlacesElementsLastBeforeOrAfterOthers()
    {
        await using var made = await Abcd();
        var (tm1, d) = (made.Tm1, made.Name);
        var pub = SubsetScope.Public;
        await tm1.Subsets.CreateAsync(d, d, pub, SubsetCreate.Builder().Name("S").BindElements(El(d, "A"), El(d, "B")).Build());
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
        await tm1.Subsets.CreateAsync(d, d, SubsetScope.Private, SubsetCreate.Builder().Name("Mine").BindElements(El(d, "D")).Build());
        Assert.Equal(["Mine"], LiveDimension.Names(await tm1.Subsets.ListAsync(d, d, SubsetScope.Private).ToListAsync()));
        Assert.Empty(await tm1.Subsets.ListAsync(d, d, SubsetScope.Public).ToListAsync());
        await tm1.Subsets.CreateAsync(d, d, SubsetScope.Public, SubsetCreate.Builder().Name("All").Expression($"{{TM1SUBSETALL([{d}].[{d}])}}").Build());
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
        var defined = await tm1.Subsets.CreateSessionSubsetAsync(d, d, SubsetCreate.Builder().Name("Wanted").Expression($"{{[{d}].[{d}].[B]}}").Build());
        Assert.NotEqual("Wanted", defined.Name); // TM1 ignores the name it's given
        Assert.Equal(["B"], await SubsetElements(tm1, d, SubsetScope.Session, defined.Name!));
        await tm1.Subsets.CreateAsync(d, d, SubsetScope.Public, SubsetCreate.Builder().Name("S").BindElements(El(d, "C"), El(d, "D")).Build());
        var copied = await tm1.Subsets.CreateSessionSubsetFromAsync(d, d, SubsetScope.Public, "S");
        Assert.Equal(["C", "D"], await SubsetElements(tm1, d, SubsetScope.Session, copied.Name!));
        Assert.Empty(await tm1.Subsets.ListAsync(d, d, SubsetScope.Session).ToListAsync()); // TM1 lists no session subsets, though each reads by its name
    }
}

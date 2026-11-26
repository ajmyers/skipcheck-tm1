// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>DimensionService against a real TM1: a port of the Java client's DimensionServiceTm1Test.</summary>
public class DimensionServiceLiveTests
{
    [Tm1Fact]
    public async Task CreatesReadsListsAndDeletesADimension()
    {
        await using var tm1 = Live.Client();
        var name = Live.Name();
        Assert.Equal(
            name,
            (await tm1.Dimensions.CreateAsync(LiveDimension.DimensionOf(name, [LiveDimension.Numeric("North"), LiveDimension.Numeric("South")]))).Name
        );
        var read = await tm1.Dimensions.GetAsync(name, ODataQuery.None().Expand(Dimension.Fields.Hierarchies, h => h.Expand(Hierarchy.Fields.Elements)));
        Assert.False(string.IsNullOrEmpty(read.ETag));
        var hierarchy = Assert.Single(read.Hierarchies!);
        Assert.Equal(["North", "South"], LiveDimension.Names(hierarchy.Elements!).Order());
        var listed = await tm1
            .Dimensions.ListAsync(ODataQuery.None().Select(Dimension.Fields.Name).Filter(Filter.StartsWith(Dimension.Fields.Name, name)))
            .ToListAsync();
        Assert.Equal(name, Assert.Single(listed).Name);
        await tm1.Dimensions.DeleteAsync(name);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.Dimensions.GetAsync(name));
    }

    [Tm1Fact]
    public async Task UpdatesOnlyWhenTheEtagIsCurrent()
    {
        await using var made = await LiveDimension.Made.Of(n => LiveDimension.DimensionOf(n, [LiveDimension.Numeric("North"), LiveDimension.Numeric("South")]));
        var (tm1, d) = (made.Tm1, made.Name);
        var etag = (await tm1.Dimensions.GetAsync(d)).ETag!;
        // A dimension PATCH applies no scalar property TM1 keeps: an empty one still checks the ETag
        var patch = DimensionPatch.Builder().Build();
        await tm1.Dimensions.UpdateAsync(d, patch, etag);
        await tm1.Dimensions.AddAllLeavesHierarchyAsync(d);
        Assert.NotEqual(etag, (await tm1.Dimensions.GetAsync(d)).ETag); // a new hierarchy changes it
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => tm1.Dimensions.UpdateAsync(d, patch, etag));
    }

    [Tm1Fact]
    public async Task IgnoresHierarchiesAndTheAllLeavesNameInAPatch()
    {
        await using var made = await LiveDimension.Made.Of(n => LiveDimension.DimensionOf(n, [LiveDimension.Numeric("North"), LiveDimension.Numeric("South")]));
        var (tm1, d) = (made.Tm1, made.Name);
        // DimensionPatch offers neither, as TM1 ignores both: sent as they are, to pin it
        var hierarchy = HierarchyCreate
            .Builder()
            .Name(d)
            .Elements([LiveDimension.Numeric("North"), LiveDimension.Numeric("South"), LiveDimension.Numeric("East")])
            .Build();
        await tm1.Session.ExecuteAsync(
            Tm1Request.Patch(
                ODataPath.Of("Dimensions").Key(d),
                new Dictionary<string, object?> { ["AllLeavesHierarchyName"] = "AllLeaves", ["Hierarchies"] = new[] { hierarchy } }
            )
        );
        var read = await tm1.Dimensions.GetAsync(d, ODataQuery.None().Expand(Dimension.Fields.Hierarchies, h => h.Expand(Hierarchy.Fields.Elements)));
        Assert.True(string.IsNullOrEmpty(read.AllLeavesHierarchyName), "TM1 accepts the PATCH and ignores it");
        Assert.Equal(2, Assert.Single(read.Hierarchies!).Elements!.Count);
    }

    [Tm1Fact]
    public async Task SavesACopyAndAddsAnAllLeavesHierarchy()
    {
        await using var made = await LiveDimension.Made.Of(n => LiveDimension.DimensionOf(n, [LiveDimension.Numeric("North"), LiveDimension.Numeric("South")]));
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Equal(d + "-copy", (await tm1.Dimensions.SaveAsAsync(d, d + "-copy")).Name);
        Assert.Equal(d + "-copy", (await tm1.Dimensions.SaveAsAsync(d, d + "-copy", true, false)).Name);
        await tm1.Dimensions.DeleteAsync(d + "-copy");
        Assert.Equal("Leaves", (await tm1.Dimensions.AddAllLeavesHierarchyAsync(d)).Name);
        var hierarchies = LiveDimension.Names((await tm1.Dimensions.GetAsync(d, ODataQuery.None().Expand(Dimension.Fields.Hierarchies))).Hierarchies!);
        Assert.Contains(d, hierarchies);
        Assert.Contains("Leaves", hierarchies);
    }

    [Tm1Fact]
    public async Task LocksAndReserves()
    {
        await using var made = await LiveDimension.Made.Of(n => LiveDimension.DimensionOf(n, [LiveDimension.Numeric("North")]));
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Dimensions.LockAsync(d);
        await tm1.Dimensions.UnlockAsync(d);
        if (!await ServerAssumptions.SupportsAsync(tm1, Tm1Operations.DimensionReserve))
        {
            return; // the server doesn't define it (xUnit 2 can't skip a test once it runs)
        }
        await tm1.Dimensions.ReserveAsync(d);
        await tm1.Dimensions.ReleaseAsync(d);
    }
}

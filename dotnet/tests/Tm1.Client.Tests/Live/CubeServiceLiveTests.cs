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
        await Cubes.CreateAsync(CubeCreate.Builder().Name(_cube).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
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
        var listed = await Cubes
            .ListAsync(ODataQuery.None().Select(Cube.Fields.Name).Filter(Filter.StartsWith(Cube.Fields.Name, _cube)))
            .Select(c => c.Name)
            .ToListAsync();
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
        Assert.Equal(
            new[] { Region, Measure }.Order(StringComparer.Ordinal),
            (await Names(Cubes.DimensionsStorageOrderAsync(_cube))).Order(StringComparer.Ordinal)
        );
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
        if (!await ServerAssumptions.SupportsAsync(_tm1, Tm1Operations.CubeReserve))
        {
            return; // the server doesn't define it (xUnit 2 can't skip a test once it runs)
        }
        await Cubes.ReserveAsync(_cube);
        await Cubes.ReleaseAsync(_cube);
    }

    private async Task<string?> Rules() => (await Cubes.GetAsync(_cube, ODataQuery.None().Select(Cube.Fields.Rules))).Rules;

    private static async Task<List<string?>> Names(IAsyncEnumerable<Dimension> dimensions) => await dimensions.Select(d => d.Name).ToListAsync();
}

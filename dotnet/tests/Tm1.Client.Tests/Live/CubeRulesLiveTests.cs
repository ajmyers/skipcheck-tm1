// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

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
        await LiveModel.DimensionAsync(_tm1, Measure, "Amount", "Double");
        await Cubes.CreateAsync(CubeCreate.Builder().Name(_cube).Rules(RulesText).BindDimensions(LiveModel.Dim(Region), LiveModel.Dim(Measure)).Build());
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

    [Tm1Fact]
    public async Task ListsUnfedCellsOnEveryServer()
    {
        // Unfed is what servers without Selection (before schema 11.8.32) list, so it works on every
        // server: there it's sent without Selection. With a value written, North/Double is a fed
        // cell, which Both lists and Unfed mustn't
        await LiveModel.WriteAsync(_tm1, _cube, [LiveModel.Element(Region, "North"), LiveModel.Element(Measure, "Amount")], "5");
        var cells = await Cubes
            .CheckFeedersAsync(_cube, Tuple("Total", "Double"), CheckFeedersSelection.Unfed, ODataQuery.None().Expand("Tuple"))
            .ToListAsync();
        Assert.All(cells, c => Assert.NotEqual(true, c.Fed));
        Assert.DoesNotContain(cells, c => c.Tuple!.Select(e => e.Name).SequenceEqual(["North", "Double"]));
    }

    private Dictionary<string, string> Tuple(string region, string measure) => new() { [Region] = region, [Measure] = measure };
}

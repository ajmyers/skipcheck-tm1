// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>MDX statements, and model against control objects, on the server's own control objects:
/// Java's MdxAndModelTm1Test.</summary>
public sealed class MdxAndModelLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync() => await _tm1.DisposeAsync();

    [Tm1Fact]
    public async Task RunsAnMdxStatementButNotCreateSet()
    {
        if (await ServerAssumptions.IsV12Async(_tm1))
        {
            // TM1 v12 ExecuteMDXStatement supports CREATE/DROP only, not SELECT
            await Assert.ThrowsAsync<Tm1BadRequestException>(() => _tm1.Cellsets.ExecuteStatementAsync("SELECT {} ON 0 FROM [}ClientGroups]"));
        }
        else
        {
            await _tm1.Cellsets.ExecuteStatementAsync("SELECT {} ON 0 FROM [}ClientGroups]");
        }
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

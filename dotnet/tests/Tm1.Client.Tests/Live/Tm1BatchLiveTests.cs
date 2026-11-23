using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1BatchTm1Test</c>.</summary>
public class Tm1BatchLiveTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(60);

    [Tm1Fact]
    public async Task RunsReadsWritesAndFailuresTogether()
    {
        await using var tm1 = Live.Client();
        var dimension = "batch" + Guid.NewGuid().ToString("N")[..8];
        var process = "tm1-client-" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                ODataPath.Of("Dimensions"),
                new Dictionary<string, object?> { ["Name"] = dimension, ["Hierarchies"] = new[] { new Dictionary<string, object?> { ["Name"] = dimension } } }
            )
        );
        await tm1.Session.ExecuteAsync(Tm1SessionAsyncLiveTests.CreateProcess(process, "x = 1;\n"));
        try
        {
            var batch = tm1.Batch();
            var queued = batch.Client.Session;
            var elements = ODataPath.Of("Dimensions").Key(dimension).Nav("Hierarchies").Key(dimension).Nav("Elements");
            var cube = queued.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("}ClientGroups"), ODataQuery.None().Select("Name")));
            var missing = queued.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("not-a-cube")));
            var north = queued.ExecuteAsync(Tm1Request.Post<JsonElement>(elements, new Dictionary<string, object?> { ["Name"] = "North" }));
            var twice = queued.ExecuteAsync(Tm1Request.Post<JsonElement>(elements, new Dictionary<string, object?> { ["Name"] = "North" }));
            var listed = queued.StreamAsync(Tm1Request.List<JsonElement>(elements)).Select(e => e.GetProperty("Name").GetString()).ToListAsync().AsTask();
            await Runtime.ScriptedTm1.Eventually(() => batch.Size() == 5, "for the list to queue");
            var run = queued.ExecuteAsync(Tm1SessionAsyncLiveTests.Execute(process));
            Assert.Equal(6, batch.Size());
            await batch.RunAsync().WaitAsync(Wait);
            Assert.Equal("}ClientGroups", (await cube).GetProperty("Name").GetString());
            await Assert.ThrowsAsync<Tm1NotFoundException>(() => missing);
            Assert.Equal("North", (await north).GetProperty("Name").GetString());
            await Assert.ThrowsAnyAsync<Tm1Exception>(() => twice); // TM1 carries on past it, in order
            Assert.Equal(["North"], await listed); // sees the element made before it
            Assert.Equal("CompletedSuccessfully", (await run).GetProperty("ProcessExecuteStatusCode").GetString());
        }
        finally
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Dimensions").Key(dimension)));
        }
    }

    [Tm1Fact]
    public async Task TakesAThousandRequests()
    {
        await using var tm1 = Live.Client();
        var batch = tm1.Batch();
        var reads = Enumerable
            .Range(0, 1000)
            .Select(_ =>
                batch.Client.Session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("}ClientGroups"), ODataQuery.None().Select("Name")))
            )
            .ToList();
        await batch.RunAsync(1000).WaitAsync(Wait);
        Assert.Equal(1000, (await Task.WhenAll(reads)).Length);
    }
}

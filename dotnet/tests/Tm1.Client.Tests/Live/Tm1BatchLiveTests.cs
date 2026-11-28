// SPDX-License-Identifier: Apache-2.0

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
            var v12 = await ServerAssumptions.IsV12Async(tm1);
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
            if (v12)
            {
                // TM1 v12 runs batch requests concurrently: one of the duplicate creates succeeds and the other fails,
                // and the list may observe the dimension before or after the create
                var outcomes = new List<Task<JsonElement>> { north, twice };
                var results = new List<string?>();
                var failures = new List<Exception>();
                foreach (var outcome in outcomes)
                {
                    try
                    {
                        results.Add((await outcome).GetProperty("Name").GetString());
                    }
                    catch (Tm1Exception e)
                    {
                        failures.Add(e);
                    }
                }
                Assert.Equal(["North"], results);
                Assert.Single(failures);
                await listed;
                Assert.Equal(
                    ["North"],
                    await tm1.Session.StreamAsync(Tm1Request.List<JsonElement>(elements)).Select(e => e.GetProperty("Name").GetString()).ToListAsync()
                );
            }
            else
            {
                Assert.Equal("North", (await north).GetProperty("Name").GetString());
                await Assert.ThrowsAnyAsync<Tm1Exception>(() => twice); // TM1 carries on past it, in order
                Assert.Equal(["North"], await listed); // sees the element made before it
            }
            Assert.Equal("CompletedSuccessfully", (await run).GetProperty("ProcessExecuteStatusCode").GetString());
        }
        finally
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Dimensions").Key(dimension)));
        }
    }

    [Tm1Fact]
    public async Task RunsRequestsInOrder()
    {
        await using var tm1 = Live.Client();
        if (await ServerAssumptions.IsV12Async(tm1))
        {
            return; // TM1 v12 runs batch requests concurrently (xUnit 2 can't skip a test once it runs)
        }
        var dimension = "batch" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(
            Tm1Request.Post(
                ODataPath.Of("Dimensions"),
                new Dictionary<string, object?> { ["Name"] = dimension, ["Hierarchies"] = new[] { new Dictionary<string, object?> { ["Name"] = dimension } } }
            )
        );
        try
        {
            var batch = tm1.Batch();
            var queued = batch.Client.Session;
            var elements = ODataPath.Of("Dimensions").Key(dimension).Nav("Hierarchies").Key(dimension).Nav("Elements");
            var before = new List<Task<JsonElement>>();
            var after = new List<Task<JsonElement>>();
            var created = new List<Task<JsonElement>>();
            // Ten times over, so that requests run side by side would show
            for (var i = 0; i < 10; i++)
            {
                before.Add(queued.ExecuteAsync(Tm1Request.Get<JsonElement>(elements.Key("e" + i))));
                created.Add(queued.ExecuteAsync(Tm1Request.Post<JsonElement>(elements, new Dictionary<string, object?> { ["Name"] = "e" + i })));
                after.Add(queued.ExecuteAsync(Tm1Request.Get<JsonElement>(elements.Key("e" + i))));
            }
            await batch.RunAsync().WaitAsync(Wait);
            await Task.WhenAll(created);
            for (var i = 0; i < 10; i++)
            {
                await Assert.ThrowsAsync<Tm1NotFoundException>(() => before[i]); // runs before the element is made
                Assert.Equal("e" + i, (await after[i]).GetProperty("Name").GetString()); // sees the element an earlier request made
            }
        }
        finally
        {
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

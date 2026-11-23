// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1SessionAsyncTm1Test</c>: a process run asynchronously, and stopped by cancelling.</summary>
public class Tm1SessionAsyncLiveTests
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(60);

    [Tm1Fact]
    public async Task RunsAProcessAndReadsACollectionThroughTheMonitor()
    {
        await using var tm1 = Live.Client();
        var async = tm1.WithAsync(TimeSpan.FromMilliseconds(200));
        var process = "tm1-client-" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(CreateProcess(process, "x = 1;\n"));
        try
        {
            var result = await async.Session.ExecuteAsync(Execute(process)).WaitAsync(Wait);
            Assert.Equal("CompletedSuccessfully", result.GetProperty("ProcessExecuteStatusCode").GetString());
            Assert.NotEmpty(
                await async.Session.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Cubes"), ODataQuery.None().Select("Name"))).ToListAsync()
            );
        }
        finally
        {
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
        }
    }

    [Tm1Fact]
    public async Task StopsTheWorkOnTm1WhenCancelled()
    {
        await using var tm1 = Live.Client();
        var async = tm1.WithAsync(TimeSpan.FromMilliseconds(200));
        var process = "tm1-client-" + Guid.NewGuid().ToString("N")[..8];
        await tm1.Session.ExecuteAsync(CreateProcess(process, "i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n"));
        try
        {
            using var cancel = new CancellationTokenSource();
            var run = async.Session.ExecuteAsync(Execute(process), cancel.Token);
            Assert.True(await WaitFor(tm1, process, true), "the process runs");
            cancel.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => run);
            Assert.True(await WaitFor(tm1, process, false), "cancelling deleted the monitor, which stopped it");
        }
        finally
        {
            await WaitFor(tm1, process, false);
            await tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Processes").Key(process)));
        }
    }

    internal static Tm1Request<NoContent> CreateProcess(string name, string prolog) =>
        Tm1Request.Post(ODataPath.Of("Processes"), new Dictionary<string, object?> { ["Name"] = name, ["PrologProcedure"] = prolog });

    internal static Tm1Request<JsonElement> Execute(string process) =>
        Tm1Request.Post<JsonElement>(ODataPath.Of("Processes").Key(process).Action("tm1.ExecuteWithReturn"), null);

    /// <summary>Waits up to 30 s for a thread running <paramref name="process"/> to be there, or gone.</summary>
    private static async Task<bool> WaitFor(Tm1Client tm1, string process, bool running)
    {
        for (int i = 0; i < 150; i++)
        {
            var found = await tm1
                .Session.StreamAsync(Tm1Request.List<JsonElement>(ODataPath.Of("Threads")))
                .AnyAsync(t =>
                    t.TryGetProperty("Function", out var f)
                    && f.ValueKind == JsonValueKind.String
                    && f.GetString()!.Contains($"Processes('{process}')", StringComparison.Ordinal)
                );
            if (found == running)
            {
                return true;
            }
            await Task.Delay(200);
        }
        return false;
    }
}

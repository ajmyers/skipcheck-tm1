// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ProcessDebugService against a fake TM1: each method sends what the Java client's does,
/// and a run is finished as Java finishes it.</summary>
public class ProcessDebugServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    private static Dictionary<string, object?> Context(string status) => new() { ["ID"] = "d1", ["Status"] = status };

    [Fact]
    public async Task DebugsWithParametersInOrder()
    {
        _tm1.Respond = _ => Context("Paused");
        var tm1 = _tm1.Client();
        var context = await tm1.ProcessDebugging.DebugAsync(
            "Load",
            new Dictionary<string, object?> { ["pYear"] = 2026, ["pVersion"] = "Actual" },
            ODataQuery.None().Expand("Breakpoints")
        );
        Assert.Equal(("d1", ProcessDebugContextStatus.Paused), (context.Id, context.Status));
        Assert.Equal(("POST", "Processes('Load')/tm1.Debug?$expand=Breakpoints"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"Parameters":[{"Name":"pYear","Value":2026},{"Name":"pVersion","Value":"Actual"}]}""", _tm1.Last.Text);
        await tm1.ProcessDebugging.DebugAsync("Load");
        Assert.Equal("{}", _tm1.Last.Text);
    }

    [Fact]
    public async Task ListsGetsAndSteps()
    {
        _tm1.Respond = _ => FakeTm1.Collection();
        var tm1 = _tm1.Client();
        Assert.Empty(await tm1.ProcessDebugging.ListAsync().ToListAsync());
        Assert.Equal("GET ProcessDebugContexts", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        _tm1.Respond = _ => Context("Paused");
        await tm1.ProcessDebugging.GetAsync("d1", ODataQuery.None().Select("Status"));
        Assert.Equal("GET ProcessDebugContexts('d1')?$select=Status", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        foreach (
            var (call, action) in new (Func<Task<ProcessDebugContext>>, string)[]
            {
                (() => tm1.ProcessDebugging.ResumeAsync("d1"), "Continue"),
                (() => tm1.ProcessDebugging.StepInAsync("d1"), "StepIn"),
                (() => tm1.ProcessDebugging.StepOutAsync("d1"), "StepOut"),
                (() => tm1.ProcessDebugging.StepOverAsync("d1"), "StepOver"),
            }
        )
        {
            Assert.Equal("d1", (await call()).Id);
            Assert.Equal(("POST", $"ProcessDebugContexts('d1')/tm1.{action}", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        }
        await tm1.ProcessDebugging.StepOverAsync("d1", ODataQuery.None().Expand("CurrentBreakpoint"));
        Assert.Equal("ProcessDebugContexts('d1')/tm1.StepOver?$expand=CurrentBreakpoint", _tm1.Last.Target);
    }

    [Fact]
    public async Task Breakpoints()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = 1, Enabled = true });
        var tm1 = _tm1.Client();
        Assert.Equal(1, Assert.Single(await tm1.ProcessDebugging.BreakpointsAsync("d1").ToListAsync()).Id);
        Assert.Equal("ProcessDebugContexts('d1')/Breakpoints", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = 1 };
        var line = ProcessDebugContextLineBreakpointCreate.Builder().Id(1).Enabled(true).ProcessName("Load").LineNumber(3).Build();
        Assert.Equal(1, (await tm1.ProcessDebugging.AddBreakpointAsync("d1", line)).Id);
        Assert.Equal(("POST", "ProcessDebugContexts('d1')/Breakpoints"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal(
            """{"@odata.type":"#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint","ID":1,"Enabled":true,"ProcessName":"Load","LineNumber":3}""",
            _tm1.Last.Text
        );

        _tm1.Respond = _ => null;
        await tm1.ProcessDebugging.UpdateBreakpointAsync("d1", 1, ProcessDebugContextBreakpointPatch.Builder().Enabled(false).Build());
        Assert.Equal(("PATCH", "ProcessDebugContexts('d1')/Breakpoints(1)", """{"Enabled":false}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.ProcessDebugging.RemoveBreakpointAsync("d1", 1);
        Assert.Equal(("DELETE", "ProcessDebugContexts('d1')/Breakpoints(1)"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task ABreakpointWithoutAnIdOfZeroOrMoreIsRefused()
    {
        var tm1 = _tm1.Client();
        await Assert.ThrowsAsync<ArgumentException>(() =>
            tm1.ProcessDebugging.AddBreakpointAsync("d1", ProcessDebugContextLineBreakpointCreate.Builder().LineNumber(3).Build())
        );
        await Assert.ThrowsAsync<ArgumentException>(() =>
            tm1.ProcessDebugging.AddBreakpointAsync("d1", ProcessDebugContextBreakpointCreate.Builder().Id(-1).Build())
        );
        Assert.DoesNotContain(_tm1.Requests, r => r.Target.Contains("Breakpoints", StringComparison.Ordinal));
    }

    [Fact]
    public async Task WhereTheRunIs()
    {
        var tm1 = _tm1.Client();
        // Through the context's expand: TM1 before 11.8.035 refuses a GET of the navigation
        _tm1.Respond = _ => new Dictionary<string, object?>
        {
            ["ID"] = "d1",
            ["CurrentBreakpoint"] = new Dictionary<string, object?> { ["ID"] = 2 },
        };
        Assert.Equal(2, (await tm1.ProcessDebugging.CurrentBreakpointAsync("d1"))!.Id);
        Assert.Equal("ProcessDebugContexts('d1')?$select=ID&$expand=CurrentBreakpoint", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "d1", ["CurrentBreakpoint"] = null };
        Assert.Null(await tm1.ProcessDebugging.CurrentBreakpointAsync("d1", ODataQuery.None().Select("ID")));
        Assert.Equal("ProcessDebugContexts('d1')?$select=ID&$expand=CurrentBreakpoint($select%3DID)", _tm1.Last.Target);
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = "f0" });
        Assert.Single(await tm1.ProcessDebugging.CallStackAsync("d1", ODataQuery.None().Expand("Variables")).ToListAsync());
        Assert.Equal("ProcessDebugContexts('d1')/CallStack?$expand=Variables", _tm1.Last.Target);
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "x", Value = "1" });
        Assert.Single(await tm1.ProcessDebugging.VariablesAsync("d1", "f0").ToListAsync());
        Assert.Equal("ProcessDebugContexts('d1')/CallStack('f0')/Variables", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Load" };
        Assert.Equal("Load", (await tm1.ProcessDebugging.FrameProcessAsync("d1", "f0")).Name);
        Assert.Equal("ProcessDebugContexts('d1')/CallStack('f0')/Process", _tm1.Last.Target);
        Assert.Equal("Load", (await tm1.ProcessDebugging.ProcessAsync("d1")).Name);
        Assert.Equal("ProcessDebugContexts('d1')/Process", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = 42 };
        Assert.Equal(42L, (await tm1.ProcessDebugging.ThreadAsync("d1")).Id);
        Assert.Equal("ProcessDebugContexts('d1')/Thread", _tm1.Last.Target);
    }

    [Fact]
    public async Task FinishRemovesTheBreakpointsItCanAndResumesToTheEnd()
    {
        var resumes = 0;
        _tm1.Respond = r =>
            r.Target switch
            {
                "ProcessDebugContexts('d1')/Breakpoints" => FakeTm1.Collection(new { ID = 1 }, new { ID = -1 }, new { ID = 4 }),
                "ProcessDebugContexts('d1')/tm1.Continue" => Context(++resumes < 3 ? "Paused" : "Complete"),
                _ => null,
            };
        var context = await _tm1.Client().ProcessDebugging.FinishAsync("d1");
        Assert.Equal(ProcessDebugContextStatus.Complete, context.Status);
        Assert.Equal(
            [
                "GET ProcessDebugContexts('d1')/Breakpoints",
                "DELETE ProcessDebugContexts('d1')/Breakpoints(1)",
                "DELETE ProcessDebugContexts('d1')/Breakpoints(4)",
                "POST ProcessDebugContexts('d1')/tm1.Continue",
                "POST ProcessDebugContexts('d1')/tm1.Continue",
                "POST ProcessDebugContexts('d1')/tm1.Continue",
            ],
            _tm1.Requests.Where(r => r.Target.StartsWith("ProcessDebugContexts", StringComparison.Ordinal)).Select(r => $"{r.Method} {r.Target}")
        );
    }

    [Fact]
    public async Task FinishGivesUpAfterAHundredResumes()
    {
        _tm1.Respond = r => r.Target.EndsWith("/Breakpoints", StringComparison.Ordinal) ? FakeTm1.Collection() : Context("Paused");
        var context = await _tm1.Client().ProcessDebugging.FinishAsync("d1");
        Assert.Equal(ProcessDebugContextStatus.Paused, context.Status);
        Assert.Equal(100, _tm1.Requests.Count(r => r.Target.EndsWith("tm1.Continue", StringComparison.Ordinal)));
    }

    [Fact]
    public async Task DebuggingFinishesTheRunAfterTheWork()
    {
        _tm1.Respond = r =>
            r.Target.EndsWith("/Breakpoints", StringComparison.Ordinal)
                ? FakeTm1.Collection()
                : Context(r.Target.EndsWith("tm1.Debug", StringComparison.Ordinal) ? "Paused" : "Complete");
        var tm1 = _tm1.Client();
        var id = await tm1.ProcessDebugging.DebuggingAsync("Load", null, context => Task.FromResult(context.Id));
        Assert.Equal("d1", id);
        Assert.Equal("POST ProcessDebugContexts('d1')/tm1.Continue", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Requests.Clear();
        var items = await tm1.ProcessDebugging.DebuggingAsync("Load", new Dictionary<string, object?>(), context => Yield(context.Id!, 2)).ToListAsync();
        Assert.Equal(["d1", "d1"], items);
        Assert.Equal("POST Processes('Load')/tm1.Debug", $"{_tm1.Requests[0].Method} {_tm1.Requests[0].Target}");
        Assert.Equal("POST ProcessDebugContexts('d1')/tm1.Continue", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task DebuggingFinishesTheRunWhenTheWorkFails()
    {
        _tm1.Respond = r =>
            r.Target.EndsWith("/Breakpoints", StringComparison.Ordinal)
                ? FakeTm1.Collection()
                : Context(r.Target.EndsWith("tm1.Debug", StringComparison.Ordinal) ? "Paused" : "Complete");
        var tm1 = _tm1.Client();
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            tm1.ProcessDebugging.DebuggingAsync("Load", null, (Func<ProcessDebugContext, Task<int>>)(_ => throw new InvalidOperationException("boom")))
        );
        Assert.Equal("POST ProcessDebugContexts('d1')/tm1.Continue", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Requests.Clear();
        await Assert.ThrowsAsync<InvalidOperationException>(async () =>
        {
            await foreach (var _ in tm1.ProcessDebugging.DebuggingAsync("Load", null, _ => Failing())) { }
        });
        Assert.Equal("POST ProcessDebugContexts('d1')/tm1.Continue", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task DebuggingFinishesTheRunWhenItsLeftEarlyAsACancelledFlux()
    {
        // As the Java client's ProcessDebugServiceTest.debuggingFinishesTheRunWhenItsCancelled
        _tm1.Respond = r =>
            r.Target.EndsWith("/Breakpoints", StringComparison.Ordinal)
                ? FakeTm1.Collection()
                : Context(r.Target.EndsWith("tm1.Debug", StringComparison.Ordinal) ? "Paused" : "Complete");
        var tm1 = _tm1.Client();
        var taken = new List<int>();
        await foreach (var i in tm1.ProcessDebugging.DebuggingAsync("Load", null, _ => Forever()))
        {
            taken.Add(i);
            break;
        }
        Assert.Equal([0], taken);
        Assert.Equal("POST ProcessDebugContexts('d1')/tm1.Continue", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    private static async IAsyncEnumerable<int> Forever([EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        for (var i = 0; ; i++)
        {
            await Task.Yield();
            yield return i;
        }
    }

    private static async IAsyncEnumerable<string> Yield(string value, int count, [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        for (var i = 0; i < count; i++)
        {
            await Task.Yield();
            yield return value;
        }
    }

    private static async IAsyncEnumerable<string> Failing()
    {
        await Task.Yield();
        yield return "x";
        throw new InvalidOperationException("boom");
    }
}

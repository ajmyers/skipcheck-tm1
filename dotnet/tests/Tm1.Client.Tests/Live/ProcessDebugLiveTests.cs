// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Debugging a process of three lines (nA = 1; nB = nA + 1; nC = nB + 1) against a real TM1:
/// Java's <c>ProcessDebugTm1Test</c>.</summary>
public sealed class ProcessDebugLiveTests : IAsyncLifetime
{
    private Tm1Client _tm1 = null!;
    private readonly string _process = Live.Name();

    private ProcessDebugService Debug => _tm1.ProcessDebugging;

    public async Task InitializeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        _tm1 = Live.Client();
        await _tm1.Processes.CreateAsync(ProcessCreate.Builder().Name(_process).PrologProcedure("nA = 1;\nnB = nA + 1;\nnC = nB + 1;\n").Build());
    }

    public async Task DisposeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        await _tm1.Processes.DeleteAsync(_process);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task StopsAtABreakpointReadsTheStateAndRunsToTheEnd()
    {
        var id = (await Debug.DebugAsync(_process)).Id!;
        try
        {
            await StopsAndRunsToTheEnd(id);
        }
        finally
        {
            // A run left paused holds the process, and its delete would wait for it
            try
            {
                await Debug.FinishAsync(id);
            }
            catch (Tm1Exception)
            {
                // already finished
            }
        }
    }

    /// <summary>The context once the run has stopped moving. TM1 answers a start, a resume or a step
    /// with the status of that moment, Running or already Paused.</summary>
    private async Task<ProcessDebugContext> Settled(string id)
    {
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(30);
        var context = await Debug.GetAsync(id);
        while (context.Status == ProcessDebugContextStatus.Running && DateTime.UtcNow < deadline)
        {
            await Task.Delay(100);
            context = await Debug.GetAsync(id);
        }
        return context;
    }

    private async Task StopsAndRunsToTheEnd(string id)
    {
        Assert.Equal(ProcessDebugContextStatus.Paused, (await Settled(id)).Status); // paused before the first line
        Assert.Null(await Debug.CurrentBreakpointAsync(id)); // at no breakpoint yet
        await Debug.AddBreakpointAsync(id, Line(1, 3));
        var breakpoint = Assert.IsType<ProcessDebugContextLineBreakpoint>(Assert.Single(await Debug.BreakpointsAsync(id).ToListAsync()));
        Assert.Equal(3, breakpoint.LineNumber);

        var moving = new ProcessDebugContextStatus?[] { ProcessDebugContextStatus.Running, ProcessDebugContextStatus.Paused };
        Assert.Contains((await Debug.ResumeAsync(id)).Status, moving);
        Assert.Equal(ProcessDebugContextStatus.Paused, (await Settled(id)).Status); // at the breakpoint
        Assert.Equal(3, Assert.IsType<ProcessDebugContextLineBreakpoint>(await Debug.CurrentBreakpointAsync(id)).LineNumber);
        var frame = Assert.Single(await Debug.CallStackAsync(id).ToListAsync());
        Assert.Equal((ProcessProcedure.Prolog, 3), (frame.Procedure, frame.LineNumber));
        var variables = await Debug.VariablesAsync(id, frame.Id!).Select(v => v.Name).ToListAsync();
        // TI's own variables, not the process's
        Assert.Contains("NVALUE", variables);
        Assert.Contains("DATASOURCETYPE", variables);
        Assert.DoesNotContain("NA", variables);
        Assert.DoesNotContain("NB", variables);
        Assert.Equal(_process, (await Debug.FrameProcessAsync(id, frame.Id!)).Name);
        Assert.Equal(_process, (await Debug.ProcessAsync(id)).Name);
        Assert.Equal(_process, (await Debug.ThreadAsync(id)).ObjectName);
        Assert.Empty(await Debug.ListAsync().ToListAsync()); // lists none

        // Past the last line: still running, paused with no line to be on, or already complete
        Assert.Contains(
            (await Debug.StepOverAsync(id)).Status,
            new ProcessDebugContextStatus?[] { ProcessDebugContextStatus.Running, ProcessDebugContextStatus.Paused, ProcessDebugContextStatus.Complete }
        );
        var done = await Debug.FinishAsync(id);
        Assert.Equal(ProcessDebugContextStatus.Complete, done.Status);
        Assert.Equal(ProcessExecuteStatusCode.CompletedSuccessfully, done.Result!.ProcessExecuteStatusCode);
        Assert.Equal(ProcessDebugContextStatus.Complete, (await Debug.GetAsync(id)).Status); // still readable, complete
        // TM1 refuses to delete one
        await Assert.ThrowsAsync<Tm1BadRequestException>(() => _tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("ProcessDebugContexts").Key(id))));
    }

    [Tm1Fact]
    public async Task NeedsAChosenBreakpointIdAndUpdatesAndRemovesByIt()
    {
        var breakpoints = await Debug.DebuggingAsync(_process, null, async context =>
        {
            var id = context.Id!;
            await Assert.ThrowsAsync<ArgumentException>(() =>
                Debug.AddBreakpointAsync(id, ProcessDebugContextLineBreakpointCreate.Builder().ProcessName(_process).LineNumber(2).Build())
            );
            await Debug.AddBreakpointAsync(id, Line(1, 2));
            await Debug.AddBreakpointAsync(id, Line(2, 3));
            await Debug.RemoveBreakpointAsync(id, 1);
            await Debug.UpdateBreakpointAsync(id, 2, ProcessDebugContextLineBreakpointPatch.Builder().Enabled(false).Build());
            return await Debug.BreakpointsAsync(id).ToListAsync();
        });
        var left = Assert.Single(breakpoints);
        Assert.Equal((2, false), (left.Id, left.Enabled));
    }

    [Tm1Fact]
    public async Task FinishesTheRunAfterDebuggingEvenWithBreakpointsLeft()
    {
        var id = await Debug.DebuggingAsync(_process, null, async context =>
        {
            await Debug.AddBreakpointAsync(context.Id!, Line(1, 2));
            await Debug.StepInAsync(context.Id!);
            return context.Id!;
        });
        Assert.Equal(ProcessDebugContextStatus.Complete, (await Debug.GetAsync(id)).Status);
    }

    private ProcessDebugContextLineBreakpointCreate Line(int id, int line) =>
        ProcessDebugContextLineBreakpointCreate
            .Builder()
            .Id(id)
            .ProcessName(_process)
            .Procedure(ProcessProcedure.Prolog)
            .LineNumber(line)
            .Enabled(true)
            .Build();
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

#pragma warning disable CS0618 // the deprecated runs, error logs and chore actions are tested too

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>ProcessService against a real TM1, on a process whose numeric parameter picks how it
/// ends: Java's <c>ProcessServiceTm1Test</c>.</summary>
public sealed class ProcessLiveTests : IAsyncLifetime
{
    private const string Prolog = """
        If(pCount = 2);
          ProcessQuit;
        EndIf;
        If(pCount = 3);
          ProcessError;
        EndIf;
        If(pCount = 4);
          CellPutN(1, 'NoSuchCube', 'x');
        EndIf;

        """;

    private Tm1Client _tm1 = null!;
    private readonly string _name = Live.Name();

    public async Task InitializeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        _tm1 = Live.Client();
        await _tm1.Processes.CreateAsync(Definition(_name, Prolog));
    }

    public async Task DisposeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        try
        {
            await _tm1.Processes.DeleteAsync(_name);
        }
        catch (Tm1Exception)
        {
            // Deleted by the test
        }
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesAProcess()
    {
        var read = await _tm1.Processes.GetAsync(_name);
        Assert.Contains("ProcessQuit", read.PrologProcedure);
        Assert.Equal(["pName", "pCount"], read.Parameters!.Select(p => p.Name));
        var listed = await _tm1.Processes.ListAsync(ODataQuery.None().Select(Process.Fields.Name).Filter(Filter.StartsWith(Process.Fields.Name, _name))).Select(p => p.Name).ToListAsync();
        Assert.Equal([_name], listed);

        await _tm1.Processes.UpdateAsync(_name, ProcessPatch.Builder().EpilogProcedure("nX = 1;\n").Build(), read.ETag);
        Assert.Contains("nX = 1;", (await _tm1.Processes.GetAsync(_name)).EpilogProcedure);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => _tm1.Processes.UpdateAsync(_name, ProcessPatch.Builder().EpilogProcedure("").Build(), read.ETag));

        await _tm1.Processes.DeleteAsync(_name);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.Processes.GetAsync(_name));
    }

    [Tm1Fact]
    public async Task CompilesSavedAndUnsavedCode()
    {
        Assert.Empty(await _tm1.Processes.CompileAsync(_name).ToListAsync());
        var error = Assert.Single(await _tm1.Processes.CompileAsync(Definition("unsaved", "nX = ;\n")).ToListAsync());
        Assert.Equal((ProcessProcedure.Prolog, 1), (error.Procedure, error.LineNumber));
        await _tm1.Processes.UpdateAsync(_name, ProcessPatch.Builder().PrologProcedure("nX = ;\n").Build());
        Assert.Single(await _tm1.Processes.CompileAsync(_name).ToListAsync()); // saved, though it doesn't compile
    }

    [Tm1Fact]
    public async Task RunsAndSaysHowItEnded()
    {
        foreach (var count in new[] { 3, 4 })
        {
            // ProcessError, then a runtime error
            var result = await _tm1.Processes.ExecuteWithReturnAsync(_name, Parameters(count), ODataQuery.None().Expand("ErrorLogFile"));
            Assert.Equal(ProcessExecuteStatusCode.Aborted, result.ProcessExecuteStatusCode);
            Assert.StartsWith("tm1processerror_", result.ErrorLogFile!.Filename);
            Assert.EndsWith("_" + _name + ".log", result.ErrorLogFile.Filename);
        }
        Assert.Equal(ProcessExecuteStatusCode.CompletedSuccessfully, await Status(1));
        Assert.Equal(ProcessExecuteStatusCode.QuitCalled, await Status(2));
        Assert.Equal(ProcessExecuteStatusCode.Aborted, await Status(3));
    }

    [Tm1Fact]
    public async Task RunsUnsavedCodeAndTheDeprecatedWays()
    {
        Assert.Equal(ProcessExecuteStatusCode.QuitCalled, (await _tm1.Processes.ExecuteWithReturnAsync(Definition("unsaved", Prolog), Parameters(2))).ProcessExecuteStatusCode);

        await _tm1.Processes.ExecuteWithReturnAsync(_name, Parameters(3));
        await _tm1.Processes.ExecuteAsync(_name, Parameters(1));
        await _tm1.Processes.ExecuteAsync(Definition("unsaved", Prolog), Parameters(1));
        var log = Assert.Single(await _tm1.Processes.ErrorLogsAsync(_name).ToListAsync()); // the aborted run's
        Assert.Contains("ProcessError", await _tm1.Processes.ErrorLogContentAsync(_name, log)); // keyed by its quoted timestamp
    }

    private async Task<ProcessExecuteStatusCode?> Status(int count) =>
        (await _tm1.Processes.ExecuteWithReturnAsync(_name, Parameters(count))).ProcessExecuteStatusCode;

    private static Dictionary<string, object?> Parameters(int count) => new() { ["pName"] = "x", ["pCount"] = count };

    private static ProcessPatch Definition(string name, string prolog) => ProcessPatch.Builder()
        .Name(name)
        .Parameters([
            ProcessParameterPatch.Builder().Name("pName").Value("").Type(ProcessVariableType.String).Build(),
            ProcessParameterPatch.Builder().Name("pCount").Value(0).Type(ProcessVariableType.Numeric).Build(),
        ])
        .PrologProcedure(prolog)
        .Build();
}

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
        await _tm1.Processes.CreateAsync(ProcessPatch.Builder().Name(_process).PrologProcedure("nA = 1;\nnB = nA + 1;\nnC = nB + 1;\n").Build());
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
        var started = await Debug.DebugAsync(_process);
        var id = started.Id!;
        Assert.Equal(ProcessDebugContextStatus.Paused, started.Status); // paused before the first line
        await Debug.AddBreakpointAsync(id, Line(1, 3));
        var breakpoint = Assert.IsType<ProcessDebugContextLineBreakpoint>(Assert.Single(await Debug.BreakpointsAsync(id).ToListAsync()));
        Assert.Equal(3, breakpoint.LineNumber);

        Assert.Equal(ProcessDebugContextStatus.Paused, (await Debug.ResumeAsync(id)).Status);
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

        // TM1 answers before or after the step lands, whichever comes first
        Assert.Contains((await Debug.StepOverAsync(id)).Status, new ProcessDebugContextStatus?[] { ProcessDebugContextStatus.Running, ProcessDebugContextStatus.Paused });
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
            await Assert.ThrowsAsync<ArgumentException>(() => Debug.AddBreakpointAsync(id, ProcessDebugContextLineBreakpointPatch.Builder().ProcessName(_process).LineNumber(2).Build()));
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

    private ProcessDebugContextLineBreakpointPatch Line(int id, int line) => ProcessDebugContextLineBreakpointPatch.Builder()
        .Id(id).ProcessName(_process).Procedure(ProcessProcedure.Prolog).LineNumber(line).Enabled(true).Build();
}

/// <summary>ChoreService against a real TM1, with a process that creates the dimension its pName
/// parameter names: Java's <c>ChoreServiceTm1Test</c>.</summary>
public sealed class ChoreLiveTests : IAsyncLifetime
{
    private Tm1Client _tm1 = null!;
    private readonly string _id = Guid.NewGuid().ToString("N")[..8];

    private string Process => "tm1net_test_" + _id;

    private string Chore => "tm1net_test_chore_" + _id;

    private ChoreService Chores => _tm1.Chores;

    public async Task InitializeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        _tm1 = Live.Client();
        await _tm1.Processes.CreateAsync(ProcessPatch.Builder()
            .Name(Process)
            .Parameters([
                ProcessParameterPatch.Builder().Name("pName").Value("").Type(ProcessVariableType.String).Build(),
                ProcessParameterPatch.Builder().Name("pCount").Value(0).Type(ProcessVariableType.Numeric).Build(),
            ])
            .PrologProcedure("If(DimensionExists(pName) = 0);\n  DimensionCreate(pName);\nEndIf;\n")
            .Build());
        await Chores.CreateAsync(ChorePatch.Builder()
            .Name(Chore)
            .StartTime(DateTimeOffset.Parse("2026-01-01T02:00:00Z", System.Globalization.CultureInfo.InvariantCulture))
            .DstSensitive(false)
            .Active(false)
            .ExecutionMode(ChoreExecutionMode.SingleCommit)
            .Frequency(TimeSpan.FromDays(1))
            .Tasks([TaskFor("marker_" + _id)])
            .Build());
    }

    public async Task DisposeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        await Quietly(() => Chores.DeleteAsync(Chore));
        await _tm1.Processes.DeleteAsync(Process);
        foreach (var d in new[] { "marker_" + _id, "second_" + _id })
        {
            await Quietly(() => _tm1.Dimensions.DeleteAsync(d));
        }
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesAChoreAndItsTasks()
    {
        var read = await Chores.GetAsync(Chore, ODataQuery.None().Expand(Model.Chore.Fields.Tasks, t => t.Expand(ChoreTask.Fields.Process)));
        var first = Assert.Single(read.Tasks!);
        Assert.Equal(0L, first.Step); // TM1 numbers the steps from 0
        Assert.Equal(Process, first.Process!.Name);
        Assert.Equal(Process, (await Chores.TaskProcessAsync(Chore, 0)).Name);
        Assert.Equal(Chore, (await Chores.TaskChoreAsync(Chore, 0)).Name);

        Assert.Equal(1L, (await Chores.AddTaskAsync(Chore, TaskFor("second_" + _id))).Step);
        Assert.Equal([0L, 1L], await Chores.TasksAsync(Chore).Select(t => t.Step!.Value).ToListAsync());
        await Chores.UpdateTaskAsync(Chore, 1, ChoreTaskPatch.Builder().Parameters([Parameter("pName", "third_" + _id), Parameter("pCount", 2)]).Build());
        var tasks = await Chores.TasksAsync(Chore).ToListAsync();
        Assert.Contains(tasks[1].Parameters!, p => p.Value?.ValueKind == System.Text.Json.JsonValueKind.String && p.Value.Value.GetString() == "third_" + _id);
        await Chores.DeleteTaskAsync(Chore, 0);
        Assert.Equal([0L], await Chores.TasksAsync(Chore).Select(t => t.Step!.Value).ToListAsync()); // renumbered from 0

        var etag = (await Chores.GetAsync(Chore)).ETag!;
        Assert.NotEqual(read.ETag, etag); // task changes change the chore's ETag
        await Chores.UpdateAsync(Chore, ChorePatch.Builder().Frequency(TimeSpan.FromHours(6)).Build(), etag);
        Assert.Equal(TimeSpan.FromHours(6), (await Chores.GetAsync(Chore)).Frequency);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() => Chores.UpdateAsync(Chore, ChorePatch.Builder().Frequency(TimeSpan.FromHours(1)).Build(), etag));
    }

    [Tm1Fact]
    public async Task NeedsEveryProcessParameterInATask()
    {
        var task = ChoreTaskPatch.Builder().BindProcess(ODataPath.Of("Processes").Key(Process)).Parameters([Parameter("pName", "x")]).Build();
        await Assert.ThrowsAnyAsync<Tm1Exception>(() => Chores.AddTaskAsync(Chore, task));
    }

    [Tm1Fact]
    public async Task ActivatesByPatchOrTheDeprecatedActions()
    {
        await Chores.UpdateAsync(Chore, ChorePatch.Builder().Active(true).Build());
        Assert.True(await Active()); // by PATCH, as IBM recommends

        await Chores.DeactivateAsync(Chore);
        Assert.False(await Active());
        await Chores.ActivateAsync(Chore);
        Assert.True(await Active());
        // Not while it's active
        var e = await Assert.ThrowsAnyAsync<Tm1Exception>(() => Chores.SetServerLocalStartTimeAsync(Chore, new DateOnly(2027, 3, 4), new TimeOnly(5, 30)));
        Assert.Contains("active chore", e.Message, StringComparison.Ordinal);
        await Chores.DeactivateAsync(Chore);
        await Chores.SetServerLocalStartTimeAsync(Chore, new DateOnly(2027, 3, 4), new TimeOnly(5, 30));
        Assert.Equal(new DateOnly(2027, 3, 4), DateOnly.FromDateTime((await Chores.GetAsync(Chore)).StartTime!.Value.DateTime));
    }

    [Tm1Fact]
    public async Task RunsTheChoreNow()
    {
        await Chores.ExecuteAsync(Chore);
        var dimension = await _tm1.Dimensions.GetAsync("marker_" + _id);
        Assert.Equal("marker_" + _id, dimension.Name);
    }

    private async Task<bool> Active() => (await Chores.GetAsync(Chore)).Active == true;

    private ChoreTaskPatch TaskFor(string marker) => ChoreTaskPatch.Builder()
        .BindProcess(ODataPath.Of("Processes").Key(Process))
        .Parameters([Parameter("pName", marker), Parameter("pCount", 1)])
        .Build();

    private static ChoreTaskParameterPatch Parameter(string name, object value) => ChoreTaskParameterPatch.Builder().Name(name).Value(value).Build();

    private static async Task Quietly(Func<Task> call)
    {
        try
        {
            await call();
        }
        catch (Tm1Exception)
        {
            // Already gone
        }
    }
}

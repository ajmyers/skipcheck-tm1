// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

#pragma warning disable CS0618 // the deprecated runs and error logs are tested too

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>ProcessService against a real TM1, on a process whose numeric parameter picks how it
/// ends: Java's <c>ProcessServiceTm1Test</c>.</summary>
public sealed class ProcessServiceLiveTests : IAsyncLifetime
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
        var listed = await _tm1
            .Processes.ListAsync(ODataQuery.None().Select(Process.Fields.Name).Filter(Filter.StartsWith(Process.Fields.Name, _name)))
            .Select(p => p.Name)
            .ToListAsync();
        Assert.Equal([_name], listed);

        await _tm1.Processes.UpdateAsync(_name, ProcessPatch.Builder().EpilogProcedure("nX = 1;\n").Build(), read.ETag);
        Assert.Contains("nX = 1;", (await _tm1.Processes.GetAsync(_name)).EpilogProcedure);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() =>
            _tm1.Processes.UpdateAsync(_name, ProcessPatch.Builder().EpilogProcedure("").Build(), read.ETag)
        );

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
        var v12 = await ServerAssumptions.IsV12Async(_tm1);
        foreach (var count in new[] { 3, 4 })
        {
            // ProcessError, then a runtime error
            var result = await _tm1.Processes.ExecuteWithReturnAsync(_name, Parameters(count), ODataQuery.None().Expand("ErrorLogFile"));
            Assert.Equal(ProcessExecuteStatusCode.Aborted, result.ProcessExecuteStatusCode);
            if (v12)
            {
                Assert.StartsWith("ProcessLog_", result.ErrorLogFile!.Filename);
                Assert.EndsWith("_" + _name + ".jsonl", result.ErrorLogFile.Filename);
            }
            else
            {
                Assert.StartsWith("tm1processerror_", result.ErrorLogFile!.Filename);
                Assert.EndsWith("_" + _name + ".log", result.ErrorLogFile.Filename);
            }
        }
        Assert.Equal(ProcessExecuteStatusCode.CompletedSuccessfully, await Status(1));
        Assert.Equal(ProcessExecuteStatusCode.QuitCalled, await Status(2));
        Assert.Equal(ProcessExecuteStatusCode.Aborted, await Status(3));
    }

    [Tm1Fact]
    public async Task RunsUnsavedCodeAndTheDeprecatedWays()
    {
        Assert.Equal(
            ProcessExecuteStatusCode.QuitCalled,
            (await _tm1.Processes.ExecuteWithReturnAsync(Definition("unsaved", Prolog), Parameters(2))).ProcessExecuteStatusCode
        );

        await _tm1.Processes.ExecuteWithReturnAsync(_name, Parameters(3));
        await _tm1.Processes.ExecuteAsync(_name, Parameters(1));
        await _tm1.Processes.ExecuteAsync(Definition("unsaved", Prolog), Parameters(1));
        var logs = await _tm1.Processes.ErrorLogsAsync(_name).ToListAsync();
        if (await ServerAssumptions.IsV12Async(_tm1))
        {
            // Deprecated Processes('...')/ErrorLogs is empty on TM1 v12
            Assert.Empty(logs);
        }
        else
        {
            var log = Assert.Single(logs); // the aborted run's
            Assert.Contains("ProcessError", await _tm1.Processes.ErrorLogContentAsync(_name, log)); // keyed by its quoted timestamp
        }
    }

    private async Task<ProcessExecuteStatusCode?> Status(int count) =>
        (await _tm1.Processes.ExecuteWithReturnAsync(_name, Parameters(count))).ProcessExecuteStatusCode;

    private static Dictionary<string, object?> Parameters(int count) => new() { ["pName"] = "x", ["pCount"] = count };

    private static ProcessCreate Definition(string name, string prolog) =>
        ProcessCreate
            .Builder()
            .Name(name)
            .Parameters([ProcessParameterPatch.Builder().Name("pName").Value("").Build(), ProcessParameterPatch.Builder().Name("pCount").Value(0).Build()])
            .PrologProcedure(prolog)
            .Build();
}

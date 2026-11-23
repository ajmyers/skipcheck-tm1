// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;

namespace Examples;

/// <summary>Examples for <c>ProcessDebugService</c>, shown in the guide.</summary>
public static class ProcessDebugServiceExamples
{
    public static IAsyncEnumerable<string> Breakpoint(Tm1Client tm1)
    {
        // @start region = breakpoint
        // Where the run stops at a breakpoint on line 3 of the prolog. DebuggingAsync
        // runs it to the end afterwards.
        var debug = tm1.ProcessDebugging;
        return debug.DebuggingAsync("Load Sales", new Dictionary<string, object?> { ["pVersion"] = "Forecast" }, context => Stack(context.Id!));

        async IAsyncEnumerable<string> Stack(string id)
        {
            var line3 = ProcessDebugContextLineBreakpointPatch
                .Builder()
                .Id(1) // TM1 needs an ID chosen by the caller
                .ProcessName("Load Sales")
                .Procedure(ProcessProcedure.Prolog)
                .LineNumber(3)
                .Enabled(true)
                .Build();
            await debug.AddBreakpointAsync(id, line3);
            await debug.ResumeAsync(id);
            await foreach (var frame in debug.CallStackAsync(id))
            {
                yield return $"{frame.Procedure}, line {frame.LineNumber}";
            }
        }
        // @end
    }

    public static async Task<ProcessDebugContext> Step(Tm1Client tm1)
    {
        // @start region = step
        // Without DebuggingAsync, end the run with FinishAsync: TM1 can't delete it
        var debug = tm1.ProcessDebugging;
        var context = await debug.DebugAsync("Load Sales");
        await debug.StepOverAsync(context.Id!);
        await debug.StepOverAsync(context.Id!);
        return await debug.FinishAsync(context.Id!);
        // @end
    }
}

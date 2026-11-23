using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ProcessService</c>, shown in the guide.</summary>
public static class ProcessServiceExamples
{
    public static async Task<Process> Create(Tm1Client tm1)
    {
        // @start region = create
        // TM1 saves code that doesn't compile, so compile the definition first
        var process = ProcessPatch
            .Builder()
            .Name("Load Sales")
            .Parameters([
                ProcessParameterPatch.Builder().Name("pVersion").Value("Actual").Type(ProcessVariableType.String).Build(),
                ProcessParameterPatch.Builder().Name("pYear").Value(2026).Type(ProcessVariableType.Numeric).Build(),
            ])
            .PrologProcedure("LogOutput('INFO', 'Loading ' | pVersion | ' for ' | NumberToString(pYear));\n")
            .Build();
        var errors = await tm1.Processes.CompileAsync(process).ToListAsync();
        if (errors.Count > 0)
        {
            throw new ArgumentException("The process doesn't compile: " + string.Join("; ", errors.Select(e => e.Message)));
        }
        return await tm1.Processes.CreateAsync(process);
        // @end
    }

    public static async Task<ProcessExecuteResult> Run(Tm1Client tm1)
    {
        // @start region = run
        // Parameters by name, in order; a numeric one takes a number, not text
        var parameters = new Dictionary<string, object?> { ["pVersion"] = "Forecast", ["pYear"] = 2026 };
        var result = await tm1.Processes.ExecuteWithReturnAsync("Load Sales", parameters, ODataQuery.None().Expand(ProcessExecuteResult.Fields.ErrorLogFile));
        // A run that ended with errors names the log it wrote
        if (result.ErrorLogFile is { } log)
        {
            throw new InvalidOperationException($"{result.ProcessExecuteStatusCode}: see {log.Filename}");
        }
        return result;
        // @end
    }

    public static async Task<ProcessExecuteResult> RunUnsaved(Tm1Client tm1)
    {
        // @start region = run-unsaved
        // A one-off run of code that isn't saved as a process
        var cleanup = ProcessPatch.Builder().Name("Clear Sales Staging").PrologProcedure("CubeClearData('Sales Staging');\n").Build();
        return await tm1.Processes.ExecuteWithReturnAsync(cleanup);
        // @end
    }
}

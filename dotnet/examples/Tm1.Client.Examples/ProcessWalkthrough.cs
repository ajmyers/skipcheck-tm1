// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>The walkthrough "Run a process and read its result" (api-site/content/walkthroughs/tm1-client), on
/// SData's SalesCube. The live tests run its steps and record what they print (WalkthroughExamplesLiveTests).</summary>
public static class ProcessWalkthrough
{
    public static async Task Main()
    {
        var tm1 = ConnectWalkthrough.Connect();
        try
        {
            await Create(tm1);
            await Run(tm1);
            await Check(tm1);
            await Fail(tm1);
            await Delete(tm1);
        }
        finally
        {
            await tm1.CloseAsync();
        }
    }

    public static async Task Create(Tm1Client tm1)
    {
        // @start region = create
        // A process that sets a month's budget to its actual units plus 10%
        var process = ProcessCreate
            .Builder()
            .Name("Budget From Actual")
            .Parameters([ProcessParameterPatch.Builder().Name("pMonth").Value("Jan").Build()])
            .PrologProcedure(
                """
                nActual = CellGetN('SalesCube', 'Actual', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);
                CellPutN(Round(nActual * 1.1), 'SalesCube', 'Budget', 'France', 'S Series 1.8 L Sedan', 'Units', pMonth);
                """
            )
            .Build();
        // TM1 saves a process even when its code has errors, so compile it first
        var errors = await tm1.Processes.CompileAsync(process).ToListAsync();
        if (errors.Count > 0)
        {
            throw new ArgumentException("The process doesn't compile: " + string.Join("; ", errors.Select(e => e.Message)));
        }
        await tm1.Processes.CreateAsync(process);
        // @end
    }

    public static async Task Run(Tm1Client tm1)
    {
        // @start region = run
        // Parameters by name; TM1 waits until the process ends to answer
        var result = await tm1.Processes.ExecuteWithReturnAsync("Budget From Actual", new Dictionary<string, object?> { ["pMonth"] = "Feb" });
        // The status as TM1 names it in its JSON
        Console.WriteLine($"Status: {result.ProcessExecuteStatusCode}");
        // @end
    }

    public static async Task Check(Tm1Client tm1)
    {
        // @start region = check
        var mdx =
            "SELECT {[actvsbud].[actvsbud].[Actual], [actvsbud].[actvsbud].[Budget]} ON COLUMNS FROM [SalesCube]"
            + " WHERE ([region].[region].[France], [model].[model].[S Series 1.8 L Sedan],"
            + " [account1].[account1].[Units], [month].[month].[Feb])";
        var cellset = await tm1.Cellsets.ExecuteAsync(mdx, ODataQuery.None().Expand(Cellset.Fields.Cells, cells => cells.Select(CellsetCell.Fields.Value)));
        Console.WriteLine($"Actual: {cellset.Cells![0].Value}");
        Console.WriteLine($"Budget: {cellset.Cells[1].Value}");
        await tm1.Cellsets.DeleteAsync(cellset.Id!);
        // @end
    }

    public static async Task Fail(Tm1Client tm1)
    {
        // @start region = fail
        // A month that isn't in the month dimension: TM1 runs the process, which fails
        var result = await tm1.Processes.ExecuteWithReturnAsync(
            "Budget From Actual",
            new Dictionary<string, object?> { ["pMonth"] = "Febr" },
            ODataQuery.None().Expand(ProcessExecuteResult.Fields.ErrorLogFile)
        );
        Console.WriteLine($"Status: {result.ProcessExecuteStatusCode}");
        // A run with errors writes a log, which the result names
        if (result.ErrorLogFile is { } log)
        {
            Console.WriteLine(await tm1.Logs.ErrorLogContentAsync(log.Filename!));
        }
        // @end
    }

    public static async Task Delete(Tm1Client tm1)
    {
        // @start region = delete
        await tm1.Processes.DeleteAsync("Budget From Actual");
        // @end
    }
}

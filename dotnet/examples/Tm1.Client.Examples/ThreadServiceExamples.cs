using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ThreadService</c>, shown in the guide.</summary>
public static class ThreadServiceExamples
{
    public static IAsyncEnumerable<string> Busy(Tm1Client tm1)
    {
        // @start region = busy
        // What the server is doing now: every thread that isn't idle
        return tm1
            .Threads.ListAsync(
                ODataQuery
                    .None()
                    .Select(Thread.Fields.Id, Thread.Fields.Name, Thread.Fields.Function, Thread.Fields.ElapsedTime)
                    .Filter(Thread.Fields.State.Ne("Idle"))
            )
            .Select(thread => $"{thread.Name}: {thread.Function} for {thread.ElapsedTime}");
        // @end
    }

    public static async Task CancelProcess(Tm1Client tm1)
    {
        // @start region = cancel-process
        // A process run through REST shows as its request, not by its name
        var running = ODataQuery
            .None()
            .Select(Thread.Fields.Id)
            .Filter(Filter.Contains(Thread.Fields.Function, "Processes('Sales.Load')/tm1.ExecuteWithReturn"));
        await foreach (var thread in tm1.Threads.ListAsync(running))
        {
            await tm1.Threads.CancelAsync(thread.Id!.Value);
        }
        // @end
    }
}

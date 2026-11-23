using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>Tm1Client</c>, shown in the guide.</summary>
public static class ClientExamples
{
    public static async Task Connect()
    {
        // @start region = connect
        // Disposing the client closes its session on TM1
        await using var tm1 = Tm1Client.Builder().BaseUrl("http://localhost:8010/api/v1/").BasicAuth("admin", "apple").Build();

        await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)))
        {
            Console.WriteLine(cube.Name);
        }
        // @end
    }

    public static async Task<ProcessExecuteResult> Async(Tm1Client tm1)
    {
        // @start region = async
        // TM1 runs it in the background and the client polls for the result, so no
        // request stays open. Cancelling the call stops the process on TM1.
        return await tm1.WithAsync(TimeSpan.FromSeconds(2)).Processes.ExecuteWithReturnAsync("Rebuild Forecast");
        // @end
    }
}

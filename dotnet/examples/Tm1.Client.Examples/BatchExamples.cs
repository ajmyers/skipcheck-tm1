// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>Tm1Batch</c>, shown in the guide.</summary>
public static class BatchExamples
{
    public static async Task<(Cube Sales, Dimension Region, List<Process> Processes)> Read(Tm1Client tm1)
    {
        // @start region = read
        // Each call is queued as it's made, and has its result once the batch has run.
        // A list is queued when its enumeration starts, so start it before running.
        var batch = tm1.Batch();
        var sales = batch.Client.Cubes.GetAsync("Sales");
        var region = batch.Client.Dimensions.GetAsync("Region");
        var processes = batch.Client.Processes.ListAsync(ODataQuery.None().Select(Process.Fields.Name)).ToListAsync().AsTask();
        await batch.RunAsync();
        return (await sales, await region, await processes);
        // @end
    }

    public static async Task<string[]> Many(Tm1Client tm1, IReadOnlyList<string> cities)
    {
        // @start region = many
        // One request per element, 200 to a $batch; one element's failure is its own
        var batch = tm1.Batch();
        async Task<string> Create(string city)
        {
            try
            {
                var created = await batch.Client.Elements.CreateAsync("City", "City", ElementPatch.Builder().Name(city).Build());
                return created.Name!;
            }
            catch (Tm1Exception e)
            {
                return $"{city} failed: {e.Message}";
            }
        }
        var results = cities.Select(Create).ToList(); // ToList makes the calls now, which queues them
        await batch.RunAsync(200);
        return await Task.WhenAll(results);
        // @end
    }
}

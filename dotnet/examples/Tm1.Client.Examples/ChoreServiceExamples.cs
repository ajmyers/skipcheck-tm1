using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ChoreService</c>, shown in the guide.</summary>
public static class ChoreServiceExamples
{
    public static async Task<Chore> Create(Tm1Client tm1)
    {
        // @start region = create
        // A daily chore at 02:00 UTC, inactive until it's switched on. A task gives
        // every parameter of its process a value.
        var loadActuals = ChoreTaskPatch
            .Builder()
            .BindProcess(ODataPath.Of("Processes").Key("Load Sales"))
            .Parameters([
                ChoreTaskParameterPatch.Builder().Name("pVersion").Value("Actual").Build(),
                ChoreTaskParameterPatch.Builder().Name("pYear").Value(2026).Build(),
            ])
            .Build();
        return await tm1.Chores.CreateAsync(
            ChorePatch
                .Builder()
                .Name("Nightly Load")
                .StartTime(new DateTimeOffset(2026, 1, 1, 2, 0, 0, TimeSpan.Zero))
                .DstSensitive(false)
                .Frequency(TimeSpan.FromDays(1))
                .ExecutionMode(ChoreExecutionMode.SingleCommit)
                .Active(false)
                .Tasks([loadActuals])
                .Build()
        );
        // @end
    }

    public static async Task<long?> AddTask(Tm1Client tm1)
    {
        // @start region = add-task
        // A task goes at the end; TM1 numbers the steps from 0
        var loadForecast = ChoreTaskPatch
            .Builder()
            .BindProcess(ODataPath.Of("Processes").Key("Load Sales"))
            .Parameters([
                ChoreTaskParameterPatch.Builder().Name("pVersion").Value("Forecast").Build(),
                ChoreTaskParameterPatch.Builder().Name("pYear").Value(2026).Build(),
            ])
            .Build();
        var task = await tm1.Chores.AddTaskAsync("Nightly Load", loadForecast);
        return task.Step;
        // @end
    }

    public static async Task Activate(Tm1Client tm1)
    {
        // @start region = activate
        // Scheduling a chore is a patch of Active
        await tm1.Chores.UpdateAsync("Nightly Load", ChorePatch.Builder().Active(true).Build());
        // @end
    }
}

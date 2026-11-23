using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

#pragma warning disable CS0618 // the deprecated chore actions are tested too

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>ChoreService against a real TM1, with a process that creates the dimension its pName
/// parameter names: Java's <c>ChoreServiceTm1Test</c>.</summary>
public sealed class ChoreServiceLiveTests : IAsyncLifetime
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
        await _tm1.Processes.CreateAsync(
            ProcessPatch
                .Builder()
                .Name(Process)
                .Parameters([
                    ProcessParameterPatch.Builder().Name("pName").Value("").Type(ProcessVariableType.String).Build(),
                    ProcessParameterPatch.Builder().Name("pCount").Value(0).Type(ProcessVariableType.Numeric).Build(),
                ])
                .PrologProcedure("If(DimensionExists(pName) = 0);\n  DimensionCreate(pName);\nEndIf;\n")
                .Build()
        );
        await Chores.CreateAsync(
            ChorePatch
                .Builder()
                .Name(Chore)
                .StartTime(DateTimeOffset.Parse("2026-01-01T02:00:00Z", System.Globalization.CultureInfo.InvariantCulture))
                .DstSensitive(false)
                .Active(false)
                .ExecutionMode(ChoreExecutionMode.SingleCommit)
                .Frequency(TimeSpan.FromDays(1))
                .Tasks([TaskFor("marker_" + _id)])
                .Build()
        );
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
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() =>
            Chores.UpdateAsync(Chore, ChorePatch.Builder().Frequency(TimeSpan.FromHours(1)).Build(), etag)
        );
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

    private ChoreTaskPatch TaskFor(string marker) =>
        ChoreTaskPatch.Builder().BindProcess(ODataPath.Of("Processes").Key(Process)).Parameters([Parameter("pName", marker), Parameter("pCount", 1)]).Build();

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

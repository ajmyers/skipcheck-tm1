using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Chores: scheduled runs of processes, as ordered tasks. <c>/Chores</c>, their tasks, and
/// running them now.
/// <para>A task binds a process (<c>ChoreTaskPatch.PatchBuilder.BindProcess</c>) and gives each of its
/// parameters a value; its <c>Step</c>, its place in the chore, is TM1's to number.</para>
/// <para>Create a daily chore with one task (a daily chore at 02:00 UTC, inactive until it's switched
/// on; a task gives every parameter of its process a value):</para>
/// <code>
/// var loadActuals = ChoreTaskPatch.Builder()
///     .BindProcess(ODataPath.Of("Processes").Key("Load Sales"))
///     .Parameters([ChoreTaskParameterPatch.Builder().Name("pVersion").Value("Actual").Build(),
///         ChoreTaskParameterPatch.Builder().Name("pYear").Value(2026).Build()])
///     .Build();
/// await tm1.Chores.CreateAsync(ChorePatch.Builder()
///     .Name("Nightly Load")
///     .StartTime(DateTimeOffset.Parse("2026-01-01T02:00:00Z"))
///     .DstSensitive(false)
///     .Frequency(TimeSpan.FromDays(1))
///     .ExecutionMode(ChoreExecutionMode.SingleCommit)
///     .Active(false)
///     .Tasks([loadActuals])
///     .Build());
/// </code>
/// <para>Add a task at the end, and get the step TM1 gave it (TM1 numbers the steps from 0):</para>
/// <code>var step = (await tm1.Chores.AddTaskAsync("Nightly Load", loadForecast)).Step;</code>
/// <para>Schedule the chore, by patching <c>Active</c>:</para>
/// <code>await tm1.Chores.UpdateAsync("Nightly Load", ChorePatch.Builder().Active(true).Build());</code>
/// </summary>
public sealed class ChoreService : Tm1Service
{
    private static readonly ODataPath Chores = ODataPath.Of("Chores");

    public ChoreService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>GET Chores</c>, streamed.</summary>
    [Tm1Endpoint("EntitySet Chores")]
    public IAsyncEnumerable<Chore> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Chore>(Chores, query), cancellationToken);

    /// <summary><c>GET Chores('name')</c>.</summary>
    [Tm1Endpoint("EntitySet Chores")]
    public Task<Chore> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Chore>(ChorePath(name), query), cancellationToken);

    /// <summary><c>POST Chores</c>: creates a chore, with its tasks if the patch has them.</summary>
    [Tm1Endpoint("EntitySet Chores")]
    public Task<Chore> CreateAsync(ChorePatch chore, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Chore>(Chores, Require(chore, nameof(chore))), cancellationToken);

    /// <summary><c>PATCH Chores('name')</c>: changes the properties the patch sets, <c>Active</c>
    /// included, which IBM recommends over the deprecated <c>ActivateAsync</c>. Adding, changing or
    /// removing a task changes the chore's ETag too.</summary>
    [Tm1Endpoint("EntitySet Chores")]
    public Task UpdateAsync(string name, ChorePatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(ChorePath(name), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>PATCH Chores('name')</c> with <c>If-Match</c>: only if the chore hasn't changed
    /// since it was read with <paramref name="etag"/>, else a <c>Tm1PreconditionFailedException</c>.</summary>
    [Tm1Endpoint("EntitySet Chores")]
    public Task UpdateAsync(string name, ChorePatch patch, string etag, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(ChorePath(name), Require(patch, nameof(patch))).IfMatch(Require(etag, nameof(etag))), cancellationToken);

    /// <summary><c>DELETE Chores('name')</c>; its processes stay.</summary>
    [Tm1Endpoint("EntitySet Chores")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(ChorePath(name)), cancellationToken);

    /// <summary><c>GET Chores('name')/Tasks</c>, streamed, in step order.</summary>
    [Tm1Endpoint("Navigation Chore/Tasks")]
    public IAsyncEnumerable<ChoreTask> TasksAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ChoreTask>(ChorePath(name).Nav("Tasks"), query), cancellationToken);

    /// <summary><c>POST Chores('name')/Tasks</c>: adds a task at the end. TM1 (11.8) needs a value for
    /// every parameter of the task's process.</summary>
    [Tm1Endpoint("Navigation Chore/Tasks")]
    public Task<ChoreTask> AddTaskAsync(string name, ChoreTaskPatch task, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<ChoreTask>(ChorePath(name).Nav("Tasks"), Require(task, nameof(task))), cancellationToken);

    /// <summary><c>PATCH Chores('name')/Tasks(step)</c>: changes a task, such as its parameter values.</summary>
    [Tm1Endpoint("Navigation Chore/Tasks")]
    public Task UpdateTaskAsync(string name, long step, ChoreTaskPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(ChorePath(name).Nav("Tasks").Key(step), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>DELETE Chores('name')/Tasks(step)</c>. TM1 (11.8) renumbers the steps after it.</summary>
    [Tm1Endpoint("Navigation Chore/Tasks")]
    public Task DeleteTaskAsync(string name, long step, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(ChorePath(name).Nav("Tasks").Key(step)), cancellationToken);

    /// <summary><c>GET Chores('name')/Tasks(step)/Process</c>.</summary>
    [Tm1Endpoint("Navigation ChoreTask/Process")]
    public Task<Process> TaskProcessAsync(string name, long step, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Process>(ChorePath(name).Nav("Tasks").Key(step).Nav("Process"), query), cancellationToken);

    /// <summary><c>GET Chores('name')/Tasks(step)/Chore</c>.</summary>
    [Tm1Endpoint("Navigation ChoreTask/Chore")]
    public Task<Chore> TaskChoreAsync(string name, long step, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Chore>(ChorePath(name).Nav("Tasks").Key(step).Nav("Chore"), query), cancellationToken);

    /// <summary><c>tm1.Execute</c>: runs the chore now, whether or not it's active, and completes when
    /// its tasks have run. Needs schema 10.2.2.2.</summary>
    [Tm1Endpoint("Action Chore/Execute")]
    public Task ExecuteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ChoreExecute, name, new()), cancellationToken);

    /// <summary><c>tm1.Activate</c>: schedules the chore. Deprecated in TM1 schema 11.8.3: patch
    /// <c>Active</c> with <c>UpdateAsync</c>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.8.3: patch Active with UpdateAsync.")]
    [Tm1Endpoint("Action Chore/Activate")]
    public Task ActivateAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ChoreActivate, name, new()), cancellationToken);

    /// <summary><c>tm1.Deactivate</c>: unschedules the chore. Deprecated in TM1 schema 11.8.3: patch
    /// <c>Active</c> with <c>UpdateAsync</c>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.8.3: patch Active with UpdateAsync.")]
    [Tm1Endpoint("Action Chore/Deactivate")]
    public Task DeactivateAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ChoreDeactivate, name, new()), cancellationToken);

    /// <summary><c>tm1.SetServerLocalStartTime</c>: sets the chore's start in the server's time zone.
    /// TM1 (11.8) refuses it while the chore is active ("Can't duplicate active chore", code 7):
    /// deactivate it first. Deprecated in TM1 schema 11.8.3: patch <c>StartTime</c> with
    /// <c>UpdateAsync</c>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.8.3: patch StartTime with UpdateAsync.")]
    [Tm1Endpoint("Action Chore/SetServerLocalStartTime")]
    public Task SetServerLocalStartTimeAsync(string name, DateOnly date, TimeOnly time, CancellationToken cancellationToken = default)
    {
        var body = new Dictionary<string, object?> { ["StartDate"] = ProcessFormats.Date(date), ["StartTime"] = ProcessFormats.Time(time) };
        return Session.ExecuteAsync(Action(Tm1Operations.ChoreSetServerLocalStartTime, name, body), cancellationToken);
    }

    private static ODataPath ChorePath(string name) => Chores.Key(Require(name, nameof(name)));

    private static Tm1Request<NoContent> Action(Tm1Operation operation, string name, Dictionary<string, object?> body) =>
        Tm1Request.Post(ChorePath(name).Action(operation.Name), body).Requires(operation.Capability());
}

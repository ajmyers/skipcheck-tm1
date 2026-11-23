using System;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Change sets: groups of cell writes that can be undone together.
/// <para>A write joins a change set through TM1's <c>!ChangeSet</c> query option, which a session view
/// from <see cref="Tm1Session.WithChangeSet"/> adds to every request (<c>$metadata</c> doesn't say how;
/// TM1 11.8 does it this way). <see cref="WithinAsync{R}(Func{Tm1Session, Task{R}}, CancellationToken)"/>
/// does it all: begins a change set, gives the work a session that records into it, and ends it
/// however the work finishes.</para>
/// <para>Make some writes in a change set, keeping its ID:</para>
/// <code>
/// var id = await tm1.ChangeSets.WithinAsync(async session =>
/// {
///     await Tm1Client.Of(session).Cells.UpdateAsync("Sales", cell, 42);
///     return session.ChangeSet()!;
/// });
/// </code>
/// <para>Undo them later:</para>
/// <code>await tm1.ChangeSets.UndoAsync(id);</code>
/// </summary>
public sealed class ChangeSetService : Tm1Service
{
    public ChangeSetService(Tm1Session session)
        : base(session) { }

    /// <summary><c>BeginChangeSet</c>: starts a change set, and returns its ID, for
    /// <see cref="Tm1Session.WithChangeSet"/>. Needs schema 10.2.2.6.</summary>
    [Tm1Endpoint("ActionImport BeginChangeSet")]
    public async Task<string> BeginAsync(CancellationToken cancellationToken = default)
    {
        var result = await Session
            .ExecuteAsync(Request(Tm1Operations.BeginChangeSet, new Dictionary<string, object?>()), cancellationToken)
            .ConfigureAwait(false);
        // A primitive result comes wrapped, as {"value": "3"}
        return Value(result).ToString();
    }

    /// <summary><c>EndChangeSet</c>: stops recording into a change set; it can still be undone. Returns
    /// TM1's return code. Needs schema 10.2.2.6.</summary>
    [Tm1Endpoint("ActionImport EndChangeSet")]
    public async Task<long> EndAsync(string id, CancellationToken cancellationToken = default)
    {
        var result = await Session
            .ExecuteAsync(Request(Tm1Operations.EndChangeSet, new Dictionary<string, object?> { ["ChangeSetID"] = Require(id, nameof(id)) }), cancellationToken)
            .ConfigureAwait(false);
        var value = Value(result);
        return value.ValueKind == JsonValueKind.Number ? value.GetInt64()
            : long.TryParse(value.ToString(), out var n) ? n
            : 0;
    }

    /// <summary><c>UndoChangeSet</c>: reverts every write recorded in a change set. Needs schema 10.2.2.6.</summary>
    [Tm1Endpoint("ActionImport UndoChangeSet")]
    public Task UndoAsync(string id, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.UndoChangeSet;
        return Session.ExecuteAsync(
            Tm1Request
                .Post(ODataPath.Of(operation.ImportName!), new Dictionary<string, object?> { ["ChangeSetID"] = Require(id, nameof(id)) })
                .Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary>Runs <paramref name="work"/> in a new change set: begins it, gives the work a view of the
    /// session that records into it (<c>session.ChangeSet()</c> has the ID, for a later
    /// <see cref="UndoAsync"/>), and ends it when the work completes, fails or is cancelled.</summary>
    [Tm1Endpoint("ActionImport BeginChangeSet")]
    [Tm1Endpoint("ActionImport EndChangeSet")]
    public async Task<R> WithinAsync<R>(Func<Tm1Session, Task<R>> work, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(work);
        var id = await BeginAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            return await work(Session.WithChangeSet(id)).ConfigureAwait(false);
        }
        finally
        {
            await EndAsync(id, CancellationToken.None).ConfigureAwait(false);
        }
    }

    /// <summary><see cref="WithinAsync{R}(Func{Tm1Session, Task{R}}, CancellationToken)"/> for work that gives nothing.</summary>
    [Tm1Endpoint("ActionImport BeginChangeSet")]
    [Tm1Endpoint("ActionImport EndChangeSet")]
    public Task WithinAsync(Func<Tm1Session, Task> work, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(work);
        return WithinAsync<bool>(
            async s =>
            {
                await work(s).ConfigureAwait(false);
                return true;
            },
            cancellationToken
        );
    }

    /// <summary>Java's <c>within</c> for work that streams (a <c>Flux</c>): the change set ends when the
    /// enumeration ends, however it ends.</summary>
    [Tm1Endpoint("ActionImport BeginChangeSet")]
    [Tm1Endpoint("ActionImport EndChangeSet")]
    public async IAsyncEnumerable<R> WithinAsync<R>(
        Func<Tm1Session, IAsyncEnumerable<R>> work,
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    )
    {
        ArgumentNullException.ThrowIfNull(work);
        var id = await BeginAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await foreach (var item in work(Session.WithChangeSet(id)).WithCancellation(cancellationToken).ConfigureAwait(false))
            {
                yield return item;
            }
        }
        finally
        {
            await EndAsync(id, CancellationToken.None).ConfigureAwait(false);
        }
    }

    private static JsonElement Value(JsonElement result) => result.ValueKind == JsonValueKind.Object && result.TryGetProperty("value", out var v) ? v : result;

    private static Tm1Request<JsonElement> Request(Tm1Operation operation, Dictionary<string, object?> body) =>
        Tm1Request.Post<JsonElement>(ODataPath.Of(operation.ImportName!), body).Requires(operation.Capability());
}

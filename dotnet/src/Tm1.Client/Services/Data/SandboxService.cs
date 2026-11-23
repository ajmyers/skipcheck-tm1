// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Sandboxes: private copies of the data a user writes to before publishing. They belong to
/// the user the session is for.
/// <para>Reading and writing in a sandbox go through a session view: <c>Tm1Session.WithSandbox</c> or
/// <c>Tm1Client.WithSandbox</c>, which add TM1's <c>!sandbox</c> option to every request. This service
/// manages the sandboxes themselves.</para>
/// <para>Create a sandbox, write to it, and publish the changes to base data:</para>
/// <code>
/// await tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("What if").Build());
/// await tm1.WithSandbox("What if").Cells.UpdateAsync("Sales", cell, 42);
/// await tm1.Sandboxes.PublishAsync("What if");
/// </code>
/// </summary>
public sealed class SandboxService : Tm1Service
{
    private static readonly ODataPath Sandboxes = ODataPath.Of("Sandboxes");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public SandboxService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Sandboxes</c>, streamed: the session user's sandboxes.</summary>
    [Tm1Endpoint("EntitySet Sandboxes")]
    public IAsyncEnumerable<Sandbox> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Sandbox>(Sandboxes, query), cancellationToken);

    /// <summary><c>GET Sandboxes('name')</c>: the sandbox, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Sandboxes")]
    public Task<Sandbox> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Sandbox>(SandboxPath(name), query), cancellationToken);

    /// <summary><c>POST Sandboxes</c>: creates an empty sandbox; it needs a name.</summary>
    [Tm1Endpoint("EntitySet Sandboxes")]
    public Task<Sandbox> CreateAsync(SandboxPatch sandbox, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Sandbox>(Sandboxes, Require(sandbox, nameof(sandbox))), cancellationToken);

    /// <summary><c>PATCH Sandboxes('name')</c>: changes the properties the patch sets, such as
    /// <c>IncludeInSandboxDimension</c>.</summary>
    [Tm1Endpoint("EntitySet Sandboxes")]
    public Task UpdateAsync(string name, SandboxPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(SandboxPath(name), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>DELETE Sandboxes('name')</c>, with its unpublished changes.</summary>
    [Tm1Endpoint("EntitySet Sandboxes")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(SandboxPath(name)), cancellationToken);

    /// <summary><c>tm1.Load</c>: loads the sandbox into memory.</summary>
    [Tm1Endpoint("Action Sandbox/Load")]
    public Task LoadAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.SandboxLoad, name, new()), cancellationToken);

    /// <summary><c>tm1.Unload</c>: frees the sandbox's memory, keeping its changes.</summary>
    [Tm1Endpoint("Action Sandbox/Unload")]
    public Task UnloadAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.SandboxUnload, name, new()), cancellationToken);

    /// <summary><c>tm1.DiscardChanges</c>: throws away the sandbox's changes, keeping the sandbox.</summary>
    [Tm1Endpoint("Action Sandbox/DiscardChanges")]
    public Task DiscardChangesAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.SandboxDiscardChanges, name, new()), cancellationToken);

    /// <summary><c>tm1.Publish</c>: writes the sandbox's changes to base data.</summary>
    [Tm1Endpoint("Action Sandbox/Publish")]
    public Task PublishAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.SandboxPublish, name, new()), cancellationToken);

    /// <summary><c>tm1.Publish</c> with options: what to do with cells changed in base data since the
    /// sandbox's changes, whether to wait for a lock rather than fail, and whether to empty the sandbox
    /// once published.</summary>
    [Tm1Endpoint("Action Sandbox/Publish")]
    public Task PublishAsync(string name, ConflictResolution conflicts, bool allowQueueing, bool cleanAfter, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.SandboxPublish, name, Options(conflicts, allowQueueing, cleanAfter)), cancellationToken);

    /// <summary><c>tm1.Merge</c>: copies the sandbox's changes into another sandbox, <paramref name="target"/>.</summary>
    [Tm1Endpoint("Action Sandbox/Merge")]
    public Task MergeAsync(string name, string target, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.SandboxMerge, name, new() { ["Target@odata.bind"] = SandboxPath(target).ToString() }), cancellationToken);

    /// <summary><c>tm1.Merge</c> with options: what to do with cells the target changed too, whether to
    /// wait for a lock rather than fail, and whether to empty this sandbox once merged.</summary>
    [Tm1Endpoint("Action Sandbox/Merge")]
    public Task MergeAsync(
        string name,
        string target,
        ConflictResolution conflicts,
        bool allowQueueing,
        bool cleanAfter,
        CancellationToken cancellationToken = default
    )
    {
        var body = new Dictionary<string, object?> { ["Target@odata.bind"] = SandboxPath(target).ToString() };
        foreach (var (k, v) in Options(conflicts, allowQueueing, cleanAfter))
        {
            body[k] = v;
        }
        return Session.ExecuteAsync(Action(Tm1Operations.SandboxMerge, name, body), cancellationToken);
    }

    private static Dictionary<string, object?> Options(ConflictResolution conflicts, bool allowQueueing, bool cleanAfter) =>
        new()
        {
            ["ConflictResolution"] = conflicts,
            ["AllowQueueing"] = allowQueueing,
            ["CleanAfter"] = cleanAfter,
        };

    private static ODataPath SandboxPath(string name) => Sandboxes.Key(Require(name, nameof(name)));

    private static Tm1Request<NoContent> Action(Tm1Operation operation, string name, Dictionary<string, object?> body) =>
        Tm1Request.Post(SandboxPath(name).Action(operation.Name), body).Requires(operation.Capability());
}

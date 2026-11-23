using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;
using ServerSession = Skipcheck.Tm1.Client.Model.Session;
using ServerThread = Skipcheck.Tm1.Client.Model.Thread;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Server threads: what the server is doing right now (<c>/Threads</c>), and cancelling an
/// operation, which is how to stop a running process: cancelling a client's request doesn't.
/// <para>See what the server is busy with:</para>
/// <code>
/// await foreach (var t in tm1.Threads.ListAsync(ODataQuery.None().Filter(Filter.Ne(Thread.Fields.State.Ne("Idle")))))
/// {
///     Console.WriteLine($"{t.Id} {t.Function} {t.ElapsedTime}");
/// }
/// </code>
/// <para>Stop a running process by cancelling its thread, found by its request:</para>
/// <code>
/// await foreach (var t in tm1.Threads.ListAsync(ODataQuery.None().Filter(Filter.Contains(Thread.Fields.Function, "Processes('Rebuild Forecast')"))))
/// {
///     await tm1.Threads.CancelAsync(t.Id!.Value);
/// }
/// </code>
/// </summary>
public sealed class ThreadService : Tm1Service
{
    private static readonly ODataPath Threads = ODataPath.Of("Threads");

    public ThreadService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Threads</c>, streamed. A process run through REST shows as its request:
    /// <c>Function</c> is <c>POST /api/v1/Processes('name')/tm1.ExecuteWithReturn</c>, and TM1 (11.8)
    /// leaves <c>ObjectName</c> empty.</summary>
    [Tm1Endpoint("EntitySet Threads")]
    public IAsyncEnumerable<ServerThread> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ServerThread>(Threads, query), cancellationToken);

    /// <summary><c>GET Threads(id)</c>, or a <c>Tm1NotFoundException</c> if it's gone.</summary>
    [Tm1Endpoint("EntitySet Threads")]
    public Task<ServerThread> GetAsync(long id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ServerThread>(Threads.Key(id), query), cancellationToken);

    /// <summary><c>GET Threads(id)/Session</c>.</summary>
    [Tm1Endpoint("Navigation Thread/Session")]
    public Task<ServerSession> SessionAsync(long id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ServerSession>(Threads.Key(id).Nav("Session"), query), cancellationToken);

    /// <summary><c>tm1.CancelOperation</c>: asks the thread to stop what it's doing, such as a running
    /// process. TM1 (11.8) then fails the process's <c>ExecuteWithReturn</c> request with 500,
    /// <c>TM1UserException: Cancel</c> (code 174), rather than reporting it <c>Aborted</c>. Completes
    /// when the request is made; the thread stops soon after. Needs schema 10.2.2.1.</summary>
    [Tm1Endpoint("Action Thread/CancelOperation")]
    public Task CancelAsync(long id, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ThreadCancelOperation;
        return Session.ExecuteAsync(
            Tm1Request.Post(Threads.Key(id).Action(operation.Name), new Dictionary<string, object?>()).Requires(operation.Capability()),
            cancellationToken
        );
    }
}

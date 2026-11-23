using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;
using ServerSession = AjMyers.Tm1.Client.Model.Session;
using ServerThread = AjMyers.Tm1.Client.Model.Thread;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Server sessions: every session logged in to the server (<c>/Sessions</c>), this one's
/// (<c>ActiveSession</c>), their users and threads, and closing another session.
/// <para>To close this client's own session, dispose the <c>Tm1Session</c> or <c>Tm1Client</c>: it
/// sends <c>ActiveSession/tm1.Close</c>, and knows not to log in again. A closed session stays listed
/// in <c>Sessions</c> for a few seconds (5 to 9 in TM1 11.8).</para>
/// <para>List who's logged in, expanding each session's user:</para>
/// <code>
/// await foreach (var s in tm1.Sessions.ListAsync(ODataQuery.None().Expand("User")))
/// {
///     Console.WriteLine($"{s.Id} {s.User?.Name} {s.Context}");
/// }
/// </code>
/// <para>Close other clients' sessions by their label, sparing this one:</para>
/// <code>
/// var mine = (await tm1.Sessions.ActiveSessionAsync()).Id;
/// await foreach (var s in tm1.Sessions.ListAsync())
/// {
///     if (s.Context == "nightly-loader" &amp;&amp; s.Id != mine) { await tm1.Sessions.CloseAsync(s.Id!.Value); }
/// }
/// </code>
/// </summary>
public sealed class SessionService : Tm1Service
{
    private static readonly ODataPath Sessions = ODataPath.Of("Sessions");

    public SessionService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Sessions</c>, streamed: every session on the server, for an admin. Expands
    /// <c>User</c> and <c>Threads</c>.</summary>
    [Tm1Endpoint("EntitySet Sessions")]
    public IAsyncEnumerable<ServerSession> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ServerSession>(Sessions, query), cancellationToken);

    /// <summary><c>GET Sessions(id)</c>, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Sessions")]
    public Task<ServerSession> GetAsync(long id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ServerSession>(Sessions.Key(id), query), cancellationToken);

    /// <summary><c>GET ActiveSession</c>: this client's own session on the server.</summary>
    [Tm1Endpoint("Singleton ActiveSession")]
    public Task<ServerSession> ActiveSessionAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ServerSession>(ODataPath.Of("ActiveSession"), query), cancellationToken);

    /// <summary><c>GET Sessions(id)/User</c>.</summary>
    [Tm1Endpoint("Navigation Session/User")]
    public Task<User> UserAsync(long id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<User>(Sessions.Key(id).Nav("User"), query), cancellationToken);

    /// <summary><c>GET Sessions(id)/Threads</c>, streamed: what the session is running now.</summary>
    [Tm1Endpoint("Navigation Session/Threads")]
    public IAsyncEnumerable<ServerThread> ThreadsAsync(long id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ServerThread>(Sessions.Key(id).Nav("Threads"), query), cancellationToken);

    /// <summary><c>tm1.Close</c>: ends another session; its client must log in again. Needs schema 10.2.2.5.</summary>
    [Tm1Endpoint("Action Session/Close")]
    public Task CloseAsync(long id, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.SessionClose;
        return Session.ExecuteAsync(
            Tm1Request.Post(Sessions.Key(id).Action(operation.Name), new Dictionary<string, object?>()).Requires(operation.Capability()),
            cancellationToken
        );
    }
}

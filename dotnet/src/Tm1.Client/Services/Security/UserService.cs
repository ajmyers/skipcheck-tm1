// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;
using ServerSession = Skipcheck.Tm1.Client.Model.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Users: <c>/Users</c>, the session's own user, their groups, and disconnecting them.
/// <para>Group membership is set whole, with <see cref="SetGroupsAsync"/>: binding <c>Groups</c> in a
/// user PATCH replaces them. TM1 (11.8) has no safe way to leave one group: a
/// <c>DELETE Users('u')/Groups('g')/$ref</c>, which OData means as "remove the link", <em>deletes the
/// group</em>, for everyone. This service never sends it.</para>
/// <para>Create a user in some groups, binding them by path:</para>
/// <code>
/// var groups = ODataPath.Of("Groups");
/// await tm1.Users.CreateAsync(UserPatch.Builder().Name("jsmith").Password("changeme")
///     .BindGroups(groups.Key("Finance"), groups.Key("Planners")).Build());
/// </code>
/// <para>Take a user out of one group by setting the rest:</para>
/// <code>
/// var rest = await tm1.Users.GroupsAsync("jsmith").Select(g => g.Name!).Where(n => n != "Planners").ToListAsync();
/// await tm1.Users.SetGroupsAsync("jsmith", rest);
/// </code>
/// <para>Lock a user out: disable them, then close their sessions.</para>
/// <code>
/// await tm1.Users.UpdateAsync("jsmith", UserPatch.Builder().Enabled(false).Build());
/// await tm1.Users.DisconnectAsync("jsmith");
/// </code>
/// </summary>
public sealed class UserService : Tm1Service
{
    private static readonly ODataPath Users = ODataPath.Of("Users");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public UserService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Users</c>, streamed.</summary>
    [Tm1Endpoint("EntitySet Users")]
    public IAsyncEnumerable<User> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<User>(Users, query), cancellationToken);

    /// <summary><c>GET Users('name')</c>. User names match ignoring case and spaces. A
    /// <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Users")]
    public Task<User> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<User>(UserPath(name), query), cancellationToken);

    /// <summary><c>GET ActiveUser</c>: the user this session is for.</summary>
    [Tm1Endpoint("Singleton ActiveUser")]
    public Task<User> ActiveUserAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<User>(ODataPath.Of("ActiveUser"), query), cancellationToken);

    /// <summary><c>POST Users</c>: creates a user, in the groups the patch binds
    /// (<c>UserPatch.PatchBuilder.BindGroups</c>). It needs a name, and a password for Basic security.</summary>
    [Tm1Endpoint("EntitySet Users")]
    public Task<User> CreateAsync(UserPatch user, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<User>(Users, Require(user, nameof(user))), cancellationToken);

    /// <summary><c>PATCH Users('name')</c>: changes the properties the patch sets, such as
    /// <c>Password</c> or <c>Enabled</c>. TM1 (11.8) sends no ETag for a user, so there's no
    /// <c>If-Match</c> form. A patch that sets or binds <c>Groups</c>, which replaces them, is refused
    /// with an <see cref="ArgumentException"/>: use <see cref="SetGroupsAsync"/>.</summary>
    [Tm1Endpoint("EntitySet Users")]
    public Task UpdateAsync(string name, UserPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(UserPath(name), WithoutGroups(Require(patch, nameof(patch)))), cancellationToken);

    /// <summary><c>PATCH Users('name')</c> binding <c>Groups</c>: makes the user's groups exactly
    /// <paramref name="groups"/>, leaving any not listed.</summary>
    [Tm1Endpoint("EntitySet Users")]
    [Tm1Endpoint("Navigation User/Groups")]
    public Task SetGroupsAsync(string name, IReadOnlyList<string> groups, CancellationToken cancellationToken = default)
    {
        var bindings = Require(groups, nameof(groups)).Select(g => ODataPath.Of("Groups").Key(Require(g, "group")).ToString()).ToList();
        var body = new Dictionary<string, object?> { ["Groups@odata.bind"] = bindings };
        return Session.ExecuteAsync(Tm1Request.Patch(UserPath(name), body), cancellationToken);
    }

    /// <summary><c>DELETE Users('name')</c>.</summary>
    [Tm1Endpoint("EntitySet Users")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(UserPath(name)), cancellationToken);

    /// <summary><c>GET Users('name')/Groups</c>, streamed.</summary>
    [Tm1Endpoint("Navigation User/Groups")]
    public IAsyncEnumerable<Group> GroupsAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Group>(UserPath(name).Nav("Groups"), query), cancellationToken);

    /// <summary><c>GET Users('name')/Sessions</c>, streamed: the user's sessions on the server.</summary>
    [Tm1Endpoint("Navigation User/Sessions")]
    public IAsyncEnumerable<ServerSession> SessionsAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ServerSession>(UserPath(name).Nav("Sessions"), query), cancellationToken);

    /// <summary><c>tm1.Disconnect</c>: closes every session the user has. Needs schema 10.2.2.6.</summary>
    [Tm1Endpoint("Action User/Disconnect")]
    public Task DisconnectAsync(string name, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.UserDisconnect;
        return Session.ExecuteAsync(
            Tm1Request.Post(UserPath(name).Action(operation.Name), new Dictionary<string, object?>()).Requires(operation.Capability()),
            cancellationToken
        );
    }

    private static UserPatch WithoutGroups(UserPatch patch)
    {
        if (patch.Values.Any(v => v.Key == "Groups" || v.Key.StartsWith("Groups@", StringComparison.Ordinal)))
        {
            throw new ArgumentException("A user PATCH with Groups replaces them: use SetGroupsAsync", nameof(patch));
        }
        return patch;
    }

    private static ODataPath UserPath(string name) => Users.Key(Require(name, nameof(name)));
}

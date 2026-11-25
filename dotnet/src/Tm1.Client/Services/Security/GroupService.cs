// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Security groups: <c>/Groups</c> and their users. A user's membership is set from the
/// user's side, with <see cref="UserService.SetGroupsAsync"/>.
/// <para>List a group's members:</para>
/// <code>await foreach (var user in tm1.Groups.UsersAsync("Finance")) { Console.WriteLine(user.Name); }</code>
/// <para>Create a group and add a user to it, from the user's side:</para>
/// <code>
/// await tm1.Groups.CreateAsync(GroupCreate.Builder().Name("Planners").Build());
/// var groups = await tm1.Users.GroupsAsync("jsmith").Select(g => g.Name!).ToListAsync();
/// await tm1.Users.SetGroupsAsync("jsmith", [.. groups, "Planners"]);
/// </code>
/// </summary>
public sealed class GroupService : Tm1Service
{
    private static readonly ODataPath Groups = ODataPath.Of("Groups");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public GroupService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Groups</c>, streamed.</summary>
    [Tm1Endpoint("EntitySet Groups")]
    public IAsyncEnumerable<Group> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Group>(Groups, query), cancellationToken);

    /// <summary><c>GET Groups('name')</c>, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Groups")]
    public Task<Group> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Group>(GroupPath(name), query), cancellationToken);

    /// <summary><c>POST Groups</c>: creates a group, with the users the create binds
    /// (<c>GroupCreate.CreateBuilder.BindUsers</c>) or nests (new users, created with it). It needs a name.
    /// Afterwards, a user's membership is set from the user's side, <see cref="UserService.SetGroupsAsync"/>:
    /// a group PATCH would replace its users.</summary>
    [Tm1Endpoint("EntitySet Groups")]
    public Task<Group> CreateAsync(GroupCreate group, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Group>(Groups, Require(group, nameof(group))), cancellationToken);

    /// <summary><c>DELETE Groups('name')</c>: deletes the group; its users stay, without it.</summary>
    [Tm1Endpoint("EntitySet Groups")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(GroupPath(name)), cancellationToken);

    /// <summary><c>GET Groups('name')/Users</c>, streamed.</summary>
    [Tm1Endpoint("Navigation Group/Users")]
    public IAsyncEnumerable<User> UsersAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<User>(GroupPath(name).Nav("Users"), query), cancellationToken);

    private static ODataPath GroupPath(string name) => Groups.Key(Require(name, nameof(name)));
}

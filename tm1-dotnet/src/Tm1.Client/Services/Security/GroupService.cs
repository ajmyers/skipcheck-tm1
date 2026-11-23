using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Security groups: <c>/Groups</c> and their users. A user's membership is set from the
/// user's side, with <see cref="UserService.SetGroupsAsync"/>.
/// <para>List a group's members:</para>
/// <code>await foreach (var user in tm1.Groups.UsersAsync("Finance")) { Console.WriteLine(user.Name); }</code>
/// <para>Create a group and add a user to it, from the user's side:</para>
/// <code>
/// await tm1.Groups.CreateAsync("Planners");
/// var groups = await tm1.Users.GroupsAsync("jsmith").Select(g => g.Name!).ToListAsync();
/// await tm1.Users.SetGroupsAsync("jsmith", [.. groups, "Planners"]);
/// </code>
/// </summary>
public sealed class GroupService : Tm1Service
{
    private static readonly ODataPath Groups = ODataPath.Of("Groups");

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

    /// <summary><c>POST Groups</c>: creates an empty group.</summary>
    [Tm1Endpoint("EntitySet Groups")]
    public Task<Group> CreateAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Group>(Groups, new Dictionary<string, object?> { ["Name"] = Require(name, nameof(name)) }), cancellationToken);

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

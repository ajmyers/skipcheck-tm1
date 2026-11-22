using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Members: <c>/Dimensions('d')/Hierarchies('h')/Members</c>, a hierarchy's elements as MDX
/// sees them, each with one parent and its level. Read only.</summary>
public sealed class MemberService : Tm1Service
{
    public MemberService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>GET …/Members</c>: the hierarchy's members, streamed.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Members")]
    public IAsyncEnumerable<Member> ListAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Member>(MembersPath(dimension, hierarchy), query), cancellationToken);

    /// <summary><c>GET …/Members('m')</c>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Members")]
    public Task<Member> GetAsync(string dimension, string hierarchy, string member, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Member>(MemberPath(dimension, hierarchy, member), query), cancellationToken);

    /// <summary><c>GET …/Members('m')/Parent</c>: the member's parent, or null for a root (TM1 sends no content).</summary>
    [Tm1Endpoint("Navigation Member/Parent")]
    public Task<Member?> ParentAsync(string dimension, string hierarchy, string member, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Member?>(MemberPath(dimension, hierarchy, member).Nav("Parent"), query), cancellationToken);

    /// <summary><c>GET …/Members('m')/Children</c>, streamed.</summary>
    [Tm1Endpoint("Navigation Member/Children")]
    public IAsyncEnumerable<Member> ChildrenAsync(string dimension, string hierarchy, string member, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Member>(MemberPath(dimension, hierarchy, member).Nav("Children"), query), cancellationToken);

    /// <summary><c>GET …/Members('m')/Element</c>: the element the member stands for.</summary>
    [Tm1Endpoint("Navigation Member/Element")]
    public Task<Element> ElementAsync(string dimension, string hierarchy, string member, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Element>(MemberPath(dimension, hierarchy, member).Nav("Element"), query), cancellationToken);

    /// <summary><c>GET …/Members('m')/Level</c>.</summary>
    [Tm1Endpoint("Navigation Member/Level")]
    public Task<Level> LevelAsync(string dimension, string hierarchy, string member, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Level>(MemberPath(dimension, hierarchy, member).Nav("Level"), query), cancellationToken);

    /// <summary><c>GET …/Members('m')/Hierarchy</c>.</summary>
    [Tm1Endpoint("Navigation Member/Hierarchy")]
    public Task<Hierarchy> HierarchyAsync(string dimension, string hierarchy, string member, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(MemberPath(dimension, hierarchy, member).Nav("Hierarchy"), query), cancellationToken);

    private static ODataPath MembersPath(string dimension, string hierarchy) => HierarchyService.HierarchyPath(dimension, hierarchy).Nav("Members");

    private static ODataPath MemberPath(string dimension, string hierarchy, string member) => MembersPath(dimension, hierarchy).Key(Require(member, nameof(member)));
}

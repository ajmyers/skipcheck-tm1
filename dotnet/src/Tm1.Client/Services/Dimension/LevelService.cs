// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Levels: <c>/Dimensions('d')/Hierarchies('h')/Levels</c>, keyed by number (0 is the
/// leaves), and the members at each.</summary>
public sealed class LevelService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public LevelService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET …/Levels</c>: the hierarchy's levels, streamed.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Levels")]
    public IAsyncEnumerable<Level> ListAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Level>(LevelsPath(dimension, hierarchy), query), cancellationToken);

    /// <summary><c>GET …/Levels(n)</c>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Levels")]
    public Task<Level> GetAsync(string dimension, string hierarchy, int number, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Level>(LevelsPath(dimension, hierarchy).Key(number), query), cancellationToken);

    /// <summary><c>GET …/Levels(n)/Members</c>: the members at the level, streamed.</summary>
    [Tm1Endpoint("Navigation Level/Members")]
    public IAsyncEnumerable<Member> MembersAsync(
        string dimension,
        string hierarchy,
        int number,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Member>(LevelsPath(dimension, hierarchy).Key(number).Nav("Members"), query), cancellationToken);

    /// <summary><c>GET …/Levels(n)/Hierarchy</c>: the hierarchy the level belongs to.</summary>
    [Tm1Endpoint("Navigation Level/Hierarchy")]
    public Task<Hierarchy> HierarchyAsync(
        string dimension,
        string hierarchy,
        int number,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(LevelsPath(dimension, hierarchy).Key(number).Nav("Hierarchy"), query), cancellationToken);

    private static ODataPath LevelsPath(string dimension, string hierarchy) => HierarchyService.HierarchyPath(dimension, hierarchy).Nav("Levels");
}

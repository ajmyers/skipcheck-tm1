// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Edges: <c>/Dimensions('d')/Hierarchies('h')/Edges</c>, the parent-child links of a
/// hierarchy, each keyed by its parent's and its component's names.
/// <para>Add an element under a consolidation, with its weight:</para>
/// <code>
/// await tm1.Edges.CreateAsync("Region", "Region", EdgeCreate.Builder().ParentName("Total").ComponentName("West").Weight(1.0).Build());
/// </code>
/// </summary>
public sealed class EdgeService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public EdgeService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Dimensions('d')/Hierarchies('h')/Edges</c>: the hierarchy's edges, streamed.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Edges")]
    public IAsyncEnumerable<Edge> ListAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Edge>(EdgesPath(dimension, hierarchy), query), cancellationToken);

    /// <summary><c>GET …/Edges(ParentName='p',ComponentName='c')</c>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Edges")]
    public Task<Edge> GetAsync(
        string dimension,
        string hierarchy,
        string parent,
        string component,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Edge>(EdgePath(dimension, hierarchy, parent, component), query), cancellationToken);

    /// <summary><c>POST …/Edges</c>: links a component under a parent, with a weight; returns the edge.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Edges")]
    public Task<Edge> CreateAsync(string dimension, string hierarchy, EdgeCreate edge, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Edge>(EdgesPath(dimension, hierarchy), Require(edge, nameof(edge))), cancellationToken);

    /// <summary><c>PATCH …/Edges(ParentName='p',ComponentName='c')</c>: changes the edge, typically its weight.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Edges")]
    public Task UpdateAsync(
        string dimension,
        string hierarchy,
        string parent,
        string component,
        EdgePatch patch,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Patch(EdgePath(dimension, hierarchy, parent, component), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>DELETE …/Edges(ParentName='p',ComponentName='c')</c>: unlinks the component; the
    /// element stays in the hierarchy.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Edges")]
    public Task DeleteAsync(string dimension, string hierarchy, string parent, string component, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(EdgePath(dimension, hierarchy, parent, component)), cancellationToken);

    /// <summary><c>GET …/Edges(…)/Parent</c>: the edge's parent element.</summary>
    [Tm1Endpoint("Navigation Edge/Parent")]
    public Task<Element> ParentAsync(
        string dimension,
        string hierarchy,
        string parent,
        string component,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Element>(EdgePath(dimension, hierarchy, parent, component).Nav("Parent"), query), cancellationToken);

    /// <summary><c>GET …/Edges(…)/Component</c>: the edge's component element.</summary>
    [Tm1Endpoint("Navigation Edge/Component")]
    public Task<Element> ComponentAsync(
        string dimension,
        string hierarchy,
        string parent,
        string component,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Element>(EdgePath(dimension, hierarchy, parent, component).Nav("Component"), query), cancellationToken);

    /// <summary><c>GET …/Edges(…)/Hierarchy</c>: the hierarchy the edge belongs to.</summary>
    [Tm1Endpoint("Navigation Edge/Hierarchy")]
    public Task<Hierarchy> HierarchyAsync(
        string dimension,
        string hierarchy,
        string parent,
        string component,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(EdgePath(dimension, hierarchy, parent, component).Nav("Hierarchy"), query), cancellationToken);

    private static ODataPath EdgesPath(string dimension, string hierarchy) => HierarchyService.HierarchyPath(dimension, hierarchy).Nav("Edges");

    private static ODataPath EdgePath(string dimension, string hierarchy, string parent, string component) =>
        EdgesPath(dimension, hierarchy)
            .Key(
                new Dictionary<string, object?> { ["ParentName"] = Require(parent, nameof(parent)), ["ComponentName"] = Require(component, nameof(component)) }
            );
}

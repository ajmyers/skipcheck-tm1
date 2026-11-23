using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Elements: <c>/Dimensions('d')/Hierarchies('h')/Elements</c>, their parents, components and
/// edges, and the operations bound to an element. Attribute values are cells of
/// <c>}ElementAttributes_&lt;dimension&gt;</c>, not properties of the element.
/// <para>Add an element, and put it under a consolidation:</para>
/// <code>
/// await tm1.Elements.CreateAsync("Region", "Region", ElementPatch.Builder().Name("West").Type(ElementType.Numeric).Build());
/// await tm1.Elements.SetComponentAsync("Region", "Region", "Total", "West", 1.0);
/// </code>
/// </summary>
public sealed class ElementService : Tm1Service
{
    public ElementService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET …/Elements</c>: the hierarchy's elements, streamed.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Elements")]
    public IAsyncEnumerable<Element> ListAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Element>(ElementsPath(dimension, hierarchy), query), cancellationToken);

    /// <summary><c>GET …/Elements('e')</c>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Elements")]
    public Task<Element> GetAsync(
        string dimension,
        string hierarchy,
        string element,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Element>(ElementPath(dimension, hierarchy, element), query), cancellationToken);

    /// <summary><c>POST …/Elements</c>: adds an element, with its components if the patch has them;
    /// returns the element as created.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Elements")]
    public Task<Element> CreateAsync(string dimension, string hierarchy, ElementPatch element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Element>(ElementsPath(dimension, hierarchy), Require(element, nameof(element))), cancellationToken);

    /// <summary><c>PATCH …/Elements('e')</c>: changes the properties the patch sets, typically <c>Type</c>.
    /// A patch with <c>Name</c> (TM1 ignores it: elements can't be renamed) or <c>Attributes</c> (TM1
    /// rejects it) is refused with an <see cref="ArgumentException"/>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Elements")]
    public Task UpdateAsync(string dimension, string hierarchy, string element, ElementPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(ElementPath(dimension, hierarchy, element), Checked(patch)), cancellationToken);

    /// <summary><c>PATCH …/Elements('e')</c> with <c>If-Match</c>: as <see cref="UpdateAsync(string, string, string, ElementPatch, CancellationToken)"/>,
    /// unless the element changed since it was read with <paramref name="etag"/>; a
    /// <c>Tm1PreconditionFailedException</c> if it had.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Elements")]
    public Task UpdateAsync(
        string dimension,
        string hierarchy,
        string element,
        ElementPatch patch,
        string etag,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Tm1Request.Patch(ElementPath(dimension, hierarchy, element), Checked(patch)).IfMatch(Require(etag, nameof(etag))),
            cancellationToken
        );

    /// <summary><c>DELETE …/Elements('e')</c>: removes the element, its edges, and its cells' data.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Elements")]
    public Task DeleteAsync(string dimension, string hierarchy, string element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(ElementPath(dimension, hierarchy, element)), cancellationToken);

    /// <summary><c>GET …/Elements('e')/Parents</c>: the consolidations the element is under, streamed.</summary>
    [Tm1Endpoint("Navigation Element/Parents")]
    public IAsyncEnumerable<Element> ParentsAsync(
        string dimension,
        string hierarchy,
        string element,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Element>(ElementPath(dimension, hierarchy, element).Nav("Parents"), query), cancellationToken);

    /// <summary><c>GET …/Elements('e')/Components</c>: the elements directly under it, streamed.</summary>
    [Tm1Endpoint("Navigation Element/Components")]
    public IAsyncEnumerable<Element> ComponentsAsync(
        string dimension,
        string hierarchy,
        string element,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Element>(ElementPath(dimension, hierarchy, element).Nav("Components"), query), cancellationToken);

    /// <summary><c>GET …/Elements('e')/Edges</c>: the edges to its components, streamed.</summary>
    [Tm1Endpoint("Navigation Element/Edges")]
    public IAsyncEnumerable<Edge> EdgesAsync(
        string dimension,
        string hierarchy,
        string element,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Edge>(ElementPath(dimension, hierarchy, element).Nav("Edges"), query), cancellationToken);

    /// <summary><c>GET …/Elements('e')/Hierarchy</c>: the hierarchy the element belongs to.</summary>
    [Tm1Endpoint("Navigation Element/Hierarchy")]
    public Task<Hierarchy> HierarchyAsync(
        string dimension,
        string hierarchy,
        string element,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(ElementPath(dimension, hierarchy, element).Nav("Hierarchy"), query), cancellationToken);

    /// <summary><c>tm1.SetComponent</c>: puts <paramref name="child"/> under <paramref name="parent"/>, with
    /// TM1's default weight, last among its components.</summary>
    [Tm1Endpoint("Action Element/SetComponent")]
    public Task SetComponentAsync(string dimension, string hierarchy, string parent, string child, CancellationToken cancellationToken = default) =>
        SendSetComponent(dimension, hierarchy, parent, child, null, null, cancellationToken);

    /// <summary><c>tm1.SetComponent</c>: puts <paramref name="child"/> under <paramref name="parent"/> with a weight.</summary>
    [Tm1Endpoint("Action Element/SetComponent")]
    public Task SetComponentAsync(
        string dimension,
        string hierarchy,
        string parent,
        string child,
        double weight,
        CancellationToken cancellationToken = default
    ) => SendSetComponent(dimension, hierarchy, parent, child, null, weight, cancellationToken);

    /// <summary><c>tm1.SetComponent</c>: puts <paramref name="child"/> under <paramref name="parent"/> with a
    /// weight, just before the component <paramref name="before"/>.</summary>
    [Tm1Endpoint("Action Element/SetComponent")]
    public Task SetComponentAsync(
        string dimension,
        string hierarchy,
        string parent,
        string child,
        string before,
        double weight,
        CancellationToken cancellationToken = default
    ) => SendSetComponent(dimension, hierarchy, parent, child, Require(before, nameof(before)), weight, cancellationToken);

    /// <summary><c>tm1.Lock</c>.</summary>
    [Tm1Endpoint("Action Element/Lock")]
    public Task LockAsync(string dimension, string hierarchy, string element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ElementLock, dimension, hierarchy, element, new Dictionary<string, object?>()), cancellationToken);

    /// <summary><c>tm1.Unlock</c>.</summary>
    [Tm1Endpoint("Action Element/Unlock")]
    public Task UnlockAsync(string dimension, string hierarchy, string element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ElementUnlock, dimension, hierarchy, element, new Dictionary<string, object?>()), cancellationToken);

    /// <summary><c>tm1.Reserve</c>: no one else can change the element until it's released.</summary>
    [Tm1Endpoint("Action Element/Reserve")]
    public Task ReserveAsync(string dimension, string hierarchy, string element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ElementReserve, dimension, hierarchy, element, new Dictionary<string, object?>()), cancellationToken);

    /// <summary><c>tm1.Release</c>.</summary>
    [Tm1Endpoint("Action Element/Release")]
    public Task ReleaseAsync(string dimension, string hierarchy, string element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.ElementRelease, dimension, hierarchy, element, new Dictionary<string, object?>()), cancellationToken);

    private Task SendSetComponent(
        string dimension,
        string hierarchy,
        string parent,
        string child,
        string? before,
        double? weight,
        CancellationToken cancellationToken
    )
    {
        var body = new Dictionary<string, object?> { ["Element@odata.bind"] = ElementPath(dimension, hierarchy, child).ToString() };
        if (before is not null)
        {
            body["Before@odata.bind"] = ElementPath(dimension, hierarchy, before).ToString();
        }
        if (weight is not null)
        {
            body["Weight"] = weight.Value;
        }
        return Session.ExecuteAsync(Action(Tm1Operations.ElementSetComponent, dimension, hierarchy, parent, body), cancellationToken);
    }

    private static ElementPatch Checked(ElementPatch patch)
    {
        foreach (var v in Require(patch, nameof(patch)).Values)
        {
            if (v.Key == "Name")
            {
                throw new ArgumentException("TM1 ignores Name in an element PATCH: elements can't be renamed");
            }
            if (v.Key == "Attributes")
            {
                throw new ArgumentException("TM1 rejects Attributes in an element PATCH: attribute values are cells of }ElementAttributes_<dimension>");
            }
        }
        return patch;
    }

    private static ODataPath ElementsPath(string dimension, string hierarchy) => HierarchyService.HierarchyPath(dimension, hierarchy).Nav("Elements");

    private static ODataPath ElementPath(string dimension, string hierarchy, string element) =>
        ElementsPath(dimension, hierarchy).Key(Require(element, nameof(element)));

    private static Tm1Request<NoContent> Action(Tm1Operation operation, string dimension, string hierarchy, string element, Dictionary<string, object?> body) =>
        Tm1Request.Post(ElementPath(dimension, hierarchy, element).Action(operation.Name), body).Requires(operation.Capability());
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Subsets: a hierarchy's <c>Subsets</c>, <c>PrivateSubsets</c> or <c>SessionSubsets</c>, by
/// <see cref="SubsetScope"/>, and the operations bound to a subset.
/// <para>A static subset, with its elements:</para>
/// <code>
/// await tm1.Subsets.CreateAsync("Region", "Region", SubsetScope.Public, SubsetPatch.Builder().Name("West Coast").Build());
/// await tm1.Subsets.AddElementsAsync("Region", "Region", SubsetScope.Public, "West Coast", ["California", "Oregon"]);
/// </code>
/// <para>A dynamic subset, from MDX:</para>
/// <code>
/// await tm1.Subsets.CreateAsync("Region", "Region", SubsetScope.Public,
///     SubsetPatch.Builder().Name("Leaves").Expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region])}, 0)}").Build());
/// </code>
/// </summary>
public sealed class SubsetService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public SubsetService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET …/Subsets</c> (or <c>PrivateSubsets</c>, <c>SessionSubsets</c>), streamed.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public IAsyncEnumerable<Subset> ListAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Subset>(SubsetsPath(dimension, hierarchy, scope), query), cancellationToken);

    /// <summary><c>GET …/Subsets('s')</c>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public Task<Subset> GetAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Subset>(SubsetPath(dimension, hierarchy, scope, subset), query), cancellationToken);

    /// <summary><c>POST …/Subsets</c>: creates a subset, static (with bound <c>Elements</c>) or dynamic
    /// (with an <c>Expression</c>); returns the subset as created.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public Task<Subset> CreateAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        SubsetCreate subset,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Post<Subset>(SubsetsPath(dimension, hierarchy, scope), Require(subset, nameof(subset))), cancellationToken);

    /// <summary><c>PATCH …/Subsets('s')</c>: changes the properties the patch sets, such as
    /// <c>Expression</c>. A patch with <c>Elements</c> is refused (<see cref="ArgumentException"/>): TM1
    /// appends them, so use <see cref="AddElementsAsync"/>, or <see cref="ClearElementsAsync"/> first.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public Task UpdateAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        SubsetPatch patch,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Patch(SubsetPath(dimension, hierarchy, scope, subset), WithoutElements(patch)), cancellationToken);

    /// <summary><c>PATCH …/Subsets('s')</c> with <c>If-Match</c>: only if the subset hasn't changed since it
    /// was read with <paramref name="etag"/>; a <c>Tm1PreconditionFailedException</c> if it had.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public Task UpdateAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        SubsetPatch patch,
        string etag,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Tm1Request.Patch(SubsetPath(dimension, hierarchy, scope, subset), WithoutElements(patch)).IfMatch(Require(etag, nameof(etag))),
            cancellationToken
        );

    /// <summary><c>PATCH …/Subsets('s')</c> with <c>Elements@odata.bind</c>: appends elements to a static subset.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public Task AddElementsAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        IReadOnlyList<string> elements,
        CancellationToken cancellationToken = default
    )
    {
        var bindings = Require(elements, nameof(elements)).Select(e => ElementPath(dimension, hierarchy, e).ToString()).ToList();
        return Session.ExecuteAsync(
            Tm1Request.Patch(SubsetPath(dimension, hierarchy, scope, subset), new Dictionary<string, object?> { ["Elements@odata.bind"] = bindings }),
            cancellationToken
        );
    }

    /// <summary><c>DELETE …/Subsets('s')/Elements/$ref</c>: empties a static subset.</summary>
    [Tm1Endpoint("Navigation Subset/Elements")]
    public Task ClearElementsAsync(string dimension, string hierarchy, SubsetScope scope, string subset, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(SubsetPath(dimension, hierarchy, scope, subset).Nav("Elements").Ref()), cancellationToken);

    /// <summary><c>DELETE …/Subsets('s')</c>.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Subsets")]
    [Tm1Endpoint("Navigation Hierarchy/PrivateSubsets")]
    [Tm1Endpoint("Navigation Hierarchy/SessionSubsets")]
    public Task DeleteAsync(string dimension, string hierarchy, SubsetScope scope, string subset, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(SubsetPath(dimension, hierarchy, scope, subset)), cancellationToken);

    /// <summary><c>GET …/Subsets('s')/Elements</c>: the subset's elements, a dynamic subset's evaluated, streamed.</summary>
    [Tm1Endpoint("Navigation Subset/Elements")]
    public IAsyncEnumerable<Element> ElementsAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Element>(SubsetPath(dimension, hierarchy, scope, subset).Nav("Elements"), query), cancellationToken);

    /// <summary><c>GET …/Subsets('s')/Hierarchy</c>.</summary>
    [Tm1Endpoint("Navigation Subset/Hierarchy")]
    public Task<Hierarchy> HierarchyAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(SubsetPath(dimension, hierarchy, scope, subset).Nav("Hierarchy"), query), cancellationToken);

    /// <summary><c>tm1.SaveAs</c>: copies the subset under a new name.</summary>
    [Tm1Endpoint("Action Subset/SaveAs")]
    public Task<Subset> SaveAsAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        string newName,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<Subset>(Tm1Operations.SubsetSaveAs, SubsetPath(dimension, hierarchy, scope, subset), new Dictionary<string, object?> { ["Name"] = newName }),
            cancellationToken
        );

    /// <summary><c>tm1.SaveAs</c>: copies the subset under a new name, private if <paramref name="makePrivate"/>,
    /// and static (its current elements, not its expression) if <paramref name="makeStatic"/>.</summary>
    [Tm1Endpoint("Action Subset/SaveAs")]
    public Task<Subset> SaveAsAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        string newName,
        bool makePrivate,
        bool makeStatic,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<Subset>(
                Tm1Operations.SubsetSaveAs,
                SubsetPath(dimension, hierarchy, scope, subset),
                new Dictionary<string, object?>
                {
                    ["Name"] = newName,
                    ["MakePrivate"] = makePrivate,
                    ["MakeStatic"] = makeStatic,
                }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.SetElement</c>: adds an element to a static subset.</summary>
    [Tm1Endpoint("Action Subset/SetElement")]
    public Task SetElementAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        string element,
        CancellationToken cancellationToken = default
    ) => SendSetElement(dimension, hierarchy, scope, subset, element, null, null, cancellationToken);

    /// <summary><c>tm1.SetElement</c> with <c>Before</c>: adds or moves an element to just before another.</summary>
    [Tm1Endpoint("Action Subset/SetElement")]
    public Task SetElementBeforeAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        string element,
        string before,
        CancellationToken cancellationToken = default
    ) => SendSetElement(dimension, hierarchy, scope, subset, element, "Before@odata.bind", Require(before, nameof(before)), cancellationToken);

    /// <summary><c>tm1.SetElement</c> with <c>After</c>: adds or moves an element to just after another.</summary>
    [Tm1Endpoint("Action Subset/SetElement")]
    public Task SetElementAfterAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        string element,
        string after,
        CancellationToken cancellationToken = default
    ) => SendSetElement(dimension, hierarchy, scope, subset, element, "After@odata.bind", Require(after, nameof(after)), cancellationToken);

    /// <summary><c>tm1.CreateSessionSubset</c>: a subset for this session only, from a definition.</summary>
    [Tm1Endpoint("Action Hierarchy/CreateSessionSubset")]
    public Task<Subset> CreateSessionSubsetAsync(string dimension, string hierarchy, SubsetCreate definition, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Action<Subset>(
                Tm1Operations.HierarchyCreateSessionSubset,
                HierarchyService.HierarchyPath(dimension, hierarchy),
                new Dictionary<string, object?> { ["Subset"] = Require(definition, nameof(definition)) }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.CreateSessionSubset</c>: a subset for this session only, copied from an existing one.</summary>
    [Tm1Endpoint("Action Hierarchy/CreateSessionSubset")]
    public Task<Subset> CreateSessionSubsetFromAsync(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<Subset>(
                Tm1Operations.HierarchyCreateSessionSubset,
                HierarchyService.HierarchyPath(dimension, hierarchy),
                new Dictionary<string, object?> { ["Subset@odata.bind"] = SubsetPath(dimension, hierarchy, scope, subset).ToString() }
            ),
            cancellationToken
        );

    private Task SendSetElement(
        string dimension,
        string hierarchy,
        SubsetScope scope,
        string subset,
        string element,
        string? position,
        string? other,
        CancellationToken cancellationToken
    )
    {
        var body = new Dictionary<string, object?> { ["Element@odata.bind"] = ElementPath(dimension, hierarchy, element).ToString() };
        if (position is not null && other is not null)
        {
            body[position] = ElementPath(dimension, hierarchy, other).ToString();
        }
        var operation = Tm1Operations.SubsetSetElement;
        return Session.ExecuteAsync(
            Tm1Request.Post(SubsetPath(dimension, hierarchy, scope, subset).Action(operation.Name), body).Requires(operation.Capability()),
            cancellationToken
        );
    }

    private static SubsetPatch WithoutElements(SubsetPatch patch)
    {
        if (Require(patch, nameof(patch)).Values.Any(v => v.Key == "Elements" || v.Key.StartsWith("Elements@", StringComparison.Ordinal)))
        {
            throw new ArgumentException("A subset PATCH with Elements appends to them: use AddElementsAsync, or ClearElementsAsync first");
        }
        return patch;
    }

    private static ODataPath SubsetsPath(string dimension, string hierarchy, SubsetScope scope) =>
        HierarchyService.HierarchyPath(dimension, hierarchy).Nav(scope.Navigation());

    private static ODataPath SubsetPath(string dimension, string hierarchy, SubsetScope scope, string subset) =>
        SubsetsPath(dimension, hierarchy, scope).Key(Require(subset, nameof(subset)));

    private static ODataPath ElementPath(string dimension, string hierarchy, string element) => HierarchyService.ElementPath(dimension, hierarchy, element);

    private static Tm1Request<T> Action<T>(Tm1Operation operation, ODataPath target, Dictionary<string, object?> body) =>
        Tm1Request.Post<T>(target.Action(operation.Name), body).Requires(operation.Capability());
}

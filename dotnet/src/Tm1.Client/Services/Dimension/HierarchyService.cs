using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Hierarchies: <c>/Dimensions('d')/Hierarchies</c>, a dimension's default hierarchy, and the
/// operations bound to a hierarchy. Elements, edges, members, levels and subsets have services of
/// their own.
/// <para>Add a hierarchy to an existing dimension, with its elements and edges:</para>
/// <code>
/// await tm1.Hierarchies.CreateAsync("Region", HierarchyPatch.Builder()
///     .Name("By Manager")
///     .Elements([ElementPatch.Builder().Name("All").Type(ElementType.Consolidated).Build(),
///         ElementPatch.Builder().Name("North").Type(ElementType.Numeric).Build()])
///     .Edges([EdgePatch.Builder().ParentName("All").ComponentName("North").Weight(1.0).Build()])
///     .Build());
/// </code>
/// <para>Replace a hierarchy's structure: TM1 deletes the elements the new structure doesn't list:</para>
/// <code>
/// await tm1.Hierarchies.ReplaceStructureAsync("Region", "Region", elements, edges);
/// </code>
/// </summary>
public sealed class HierarchyService : Tm1Service
{
    public HierarchyService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Dimensions('d')/Hierarchies</c>: the dimension's hierarchies, streamed.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public IAsyncEnumerable<Hierarchy> ListAsync(string dimension, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Hierarchy>(HierarchiesPath(dimension), query), cancellationToken);

    /// <summary><c>GET Dimensions('d')/Hierarchies('h')</c>.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public Task<Hierarchy> GetAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(HierarchyPath(dimension, hierarchy), query), cancellationToken);

    /// <summary><c>POST Dimensions('d')/Hierarchies</c>: creates a hierarchy, with its elements and edges
    /// if the patch has them. It needs a name; returns the hierarchy as created.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public Task<Hierarchy> CreateAsync(string dimension, HierarchyPatch hierarchy, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Hierarchy>(HierarchiesPath(dimension), Require(hierarchy, nameof(hierarchy))), cancellationToken);

    /// <summary><c>PATCH Dimensions('d')/Hierarchies('h')</c>: changes the properties the patch sets.
    /// TM1 (11.8) ignores <c>Visible</c>. A patch with <c>Elements</c> or <c>Edges</c> is refused
    /// (<see cref="ArgumentException"/>): it would replace them, so use <see cref="ReplaceStructureAsync"/>.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public Task UpdateAsync(string dimension, string hierarchy, HierarchyPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(HierarchyPath(dimension, hierarchy), WithoutStructure(patch)), cancellationToken);

    /// <summary><c>PATCH Dimensions('d')/Hierarchies('h')</c> with <c>If-Match</c>: changes the properties
    /// the patch sets, unless the hierarchy changed since it was read with <paramref name="etag"/>
    /// (<c>Hierarchy.ETag</c>); a <c>Tm1PreconditionFailedException</c> if it had changed. A patch with
    /// <c>Elements</c> or <c>Edges</c> is refused: use <see cref="ReplaceStructureAsync"/>.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public Task UpdateAsync(string dimension, string hierarchy, HierarchyPatch patch, string etag, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Tm1Request.Patch(HierarchyPath(dimension, hierarchy), WithoutStructure(patch)).IfMatch(Require(etag, nameof(etag))),
            cancellationToken
        );

    /// <summary><c>PATCH Dimensions('d')/Hierarchies('h')</c> with <c>Elements</c> and <c>Edges</c>:
    /// replaces the hierarchy's structure. TM1 (11.8) deletes the elements the new structure doesn't
    /// list, and the edges with them; data in cells of a deleted element is lost.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public Task ReplaceStructureAsync(
        string dimension,
        string hierarchy,
        IReadOnlyList<ElementPatch> elements,
        IReadOnlyList<EdgePatch> edges,
        CancellationToken cancellationToken = default
    )
    {
        var patch = HierarchyPatch.Builder().Elements(Require(elements, nameof(elements))).Edges(Require(edges, nameof(edges))).Build();
        return Session.ExecuteAsync(Tm1Request.Patch(HierarchyPath(dimension, hierarchy), patch), cancellationToken);
    }

    /// <summary><c>DELETE Dimensions('d')/Hierarchies('h')</c>.</summary>
    [Tm1Endpoint("Navigation Dimension/Hierarchies")]
    public Task DeleteAsync(string dimension, string hierarchy, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(HierarchyPath(dimension, hierarchy)), cancellationToken);

    /// <summary><c>GET Dimensions('d')/DefaultHierarchy</c>: the hierarchy named after the dimension.</summary>
    [Tm1Endpoint("Navigation Dimension/DefaultHierarchy")]
    public Task<Hierarchy> DefaultHierarchyAsync(string dimension, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Hierarchy>(DimensionPath(dimension).Nav("DefaultHierarchy"), query), cancellationToken);

    /// <summary><c>GET Dimensions('d')/Hierarchies('h')/Dimension</c>: the dimension the hierarchy belongs to.</summary>
    [Tm1Endpoint("Navigation Hierarchy/Dimension")]
    public Task<Dimension> DimensionAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Dimension>(HierarchyPath(dimension, hierarchy).Nav("Dimension"), query), cancellationToken);

    /// <summary><c>GET Dimensions('d')/Hierarchies('h')/AllMember</c>: the member that stands for the whole
    /// hierarchy. TM1 (11.8) has one only when the hierarchy has a single root, which is then that root;
    /// with several roots it sends no content, and this returns null.</summary>
    [Tm1Endpoint("Navigation Hierarchy/AllMember")]
    public Task<Member?> AllMemberAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Member?>(HierarchyPath(dimension, hierarchy).Nav("AllMember"), query), cancellationToken);

    /// <summary><c>GET Dimensions('d')/Hierarchies('h')/DefaultMember</c>: the member MDX uses when a
    /// query names none of the hierarchy's.</summary>
    [Tm1Endpoint("Navigation Hierarchy/DefaultMember")]
    public Task<Member?> DefaultMemberAsync(string dimension, string hierarchy, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Member?>(HierarchyPath(dimension, hierarchy).Nav("DefaultMember"), query), cancellationToken);

    /// <summary><c>tm1.RootMembers()</c>: the hierarchy's top-level members, streamed.</summary>
    [Tm1Endpoint("Function Hierarchy/RootMembers")]
    public IAsyncEnumerable<Member> RootMembersAsync(
        string dimension,
        string hierarchy,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.HierarchyRootMembers;
        return Session.StreamAsync(
            Tm1Request.List<Member>(HierarchyPath(dimension, hierarchy).Function(operation.Name), query).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>tm1.SaveAs</c>: copies a hierarchy under a new name in the same dimension, failing if
    /// one exists.</summary>
    [Tm1Endpoint("Action Hierarchy/SaveAs")]
    public Task<Hierarchy> SaveAsAsync(string dimension, string hierarchy, string newName, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Action<Hierarchy>(Tm1Operations.HierarchySaveAs, dimension, hierarchy, new Dictionary<string, object?> { ["Name"] = newName }),
            cancellationToken
        );

    /// <summary><c>tm1.SaveAs</c>: copies a hierarchy under a new name. <paramref name="overwrite"/>
    /// replaces a hierarchy that already has it; <paramref name="keepExistingAttributes"/> keeps the
    /// attributes of what's replaced.</summary>
    [Tm1Endpoint("Action Hierarchy/SaveAs")]
    public Task<Hierarchy> SaveAsAsync(
        string dimension,
        string hierarchy,
        string newName,
        bool overwrite,
        bool keepExistingAttributes,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<Hierarchy>(
                Tm1Operations.HierarchySaveAs,
                dimension,
                hierarchy,
                new Dictionary<string, object?>
                {
                    ["Name"] = newName,
                    ["Overwrite"] = overwrite,
                    ["KeepExistingAttributes"] = keepExistingAttributes,
                }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.SetElement</c> with no <c>Before</c>: makes an element a root of the hierarchy,
    /// placed <em>last</em> among the roots. (<c>$metadata</c> says first; TM1 11.8 puts it last.)</summary>
    [Tm1Endpoint("Action Hierarchy/SetElement")]
    public Task SetElementAsync(string dimension, string hierarchy, string element, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            VoidAction(
                Tm1Operations.HierarchySetElement,
                dimension,
                hierarchy,
                new Dictionary<string, object?> { ["Element@odata.bind"] = ElementPath(dimension, hierarchy, element).ToString() }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.SetElement</c>: makes an element a root of the hierarchy, placed just before another root.</summary>
    [Tm1Endpoint("Action Hierarchy/SetElement")]
    public Task SetElementAsync(string dimension, string hierarchy, string element, string before, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            VoidAction(
                Tm1Operations.HierarchySetElement,
                dimension,
                hierarchy,
                new Dictionary<string, object?>
                {
                    ["Element@odata.bind"] = ElementPath(dimension, hierarchy, element).ToString(),
                    ["Before@odata.bind"] = ElementPath(dimension, hierarchy, before).ToString(),
                }
            ),
            cancellationToken
        );

    private static HierarchyPatch WithoutStructure(HierarchyPatch patch)
    {
        // Bindings to the collections (Elements@odata.bind) are structure changes too
        if (
            Require(patch, nameof(patch))
                .Values.Any(v =>
                    v.Key is "Elements" or "Edges"
                    || v.Key.StartsWith("Elements@", StringComparison.Ordinal)
                    || v.Key.StartsWith("Edges@", StringComparison.Ordinal)
                )
        )
        {
            throw new ArgumentException("A hierarchy PATCH with Elements or Edges replaces them, deleting what it doesn't list: use ReplaceStructureAsync");
        }
        return patch;
    }

    internal static ODataPath DimensionPath(string dimension) => ODataPath.Of("Dimensions").Key(Require(dimension, nameof(dimension)));

    private static ODataPath HierarchiesPath(string dimension) => DimensionPath(dimension).Nav("Hierarchies");

    internal static ODataPath HierarchyPath(string dimension, string hierarchy) => HierarchiesPath(dimension).Key(Require(hierarchy, nameof(hierarchy)));

    internal static ODataPath ElementPath(string dimension, string hierarchy, string element) =>
        HierarchyPath(dimension, hierarchy).Nav("Elements").Key(Require(element, nameof(element)));

    private static Tm1Request<T> Action<T>(Tm1Operation operation, string dimension, string hierarchy, Dictionary<string, object?> body) =>
        Tm1Request.Post<T>(HierarchyPath(dimension, hierarchy).Action(operation.Name), body).Requires(operation.Capability());

    private static Tm1Request<NoContent> VoidAction(Tm1Operation operation, string dimension, string hierarchy, Dictionary<string, object?> body) =>
        Tm1Request.Post(HierarchyPath(dimension, hierarchy).Action(operation.Name), body).Requires(operation.Capability());
}

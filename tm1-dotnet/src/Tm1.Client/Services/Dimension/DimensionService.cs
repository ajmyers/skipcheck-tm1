using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Dimensions: <c>/Dimensions</c> and the operations bound to a dimension. A dimension's
/// hierarchies, elements and subsets have services of their own.
/// <para>List dimensions with a query: here, the names of the model dimensions, in name order:</para>
/// <code>
/// tm1.Dimensions.ModelDimensionsAsync(ODataQuery.None().Select(Dimension.Fields.Name).OrderBy(Dimension.Fields.Name))
///     .Select(d => d.Name);
/// </code>
/// <para>Create a dimension with its hierarchy, elements and edges in one request (Region, with one
/// hierarchy of the same name: Total = North + South):</para>
/// <code>
/// var total = ElementPatch.Builder().Name("Total").Type(ElementType.Consolidated).Build();
/// var north = ElementPatch.Builder().Name("North").Type(ElementType.Numeric).Build();
/// var south = ElementPatch.Builder().Name("South").Type(ElementType.Numeric).Build();
/// await tm1.Dimensions.CreateAsync(DimensionPatch.Builder()
///     .Name("Region")
///     .Hierarchies([HierarchyPatch.Builder()
///         .Name("Region")
///         .Elements([total, north, south])
///         .Edges([EdgePatch.Builder().ParentName("Total").ComponentName("North").Weight(1.0).Build(),
///             EdgePatch.Builder().ParentName("Total").ComponentName("South").Weight(1.0).Build()])
///         .Build()])
///     .Build());
/// </code>
/// <para>Read a dimension with its hierarchies' names expanded in the same request:</para>
/// <code>
/// var region = await tm1.Dimensions.GetAsync("Region", ODataQuery.None().Expand(Dimension.Fields.Hierarchies, h => h.Select(Hierarchy.Fields.Name)));
/// </code>
/// </summary>
public sealed class DimensionService : Tm1Service
{
    private static readonly ODataPath Dimensions = ODataPath.Of("Dimensions");

    public DimensionService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Dimensions</c>: every dimension, control dimensions included, streamed.</summary>
    [Tm1Endpoint("EntitySet Dimensions")]
    public IAsyncEnumerable<Dimension> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Dimension>(Dimensions, query), cancellationToken);

    /// <summary><c>ModelDimensions()</c>: the dimensions that aren't control dimensions (whose names start with <c>}</c>), streamed.</summary>
    [Tm1Endpoint("FunctionImport ModelDimensions")]
    public IAsyncEnumerable<Dimension> ModelDimensionsAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Dimension>(ODataPath.FunctionImport("ModelDimensions"), query), cancellationToken);

    /// <summary><c>ControlDimensions()</c>: the control dimensions, such as <c>}Clients</c>, streamed.</summary>
    [Tm1Endpoint("FunctionImport ControlDimensions")]
    public IAsyncEnumerable<Dimension> ControlDimensionsAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Dimension>(ODataPath.FunctionImport("ControlDimensions"), query), cancellationToken);

    /// <summary><c>GET Dimensions('name')</c>: the dimension, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Dimensions")]
    public Task<Dimension> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Dimension>(DimensionPath(name), query), cancellationToken);

    /// <summary><c>POST Dimensions</c>: creates a dimension, with its hierarchies, elements and edges if
    /// the patch has them (a deep insert). It needs a name; returns the dimension as created.</summary>
    [Tm1Endpoint("EntitySet Dimensions")]
    public Task<Dimension> CreateAsync(DimensionPatch dimension, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Dimension>(Dimensions, Require(dimension, nameof(dimension))), cancellationToken);

    /// <summary><c>PATCH Dimensions('name')</c>: changes the properties the patch sets.
    /// TM1 (11.8) accepts <c>Hierarchies</c> and <c>AllLeavesHierarchyName</c> in a dimension PATCH and
    /// ignores them: change hierarchies through their own service, and add an all-leaves hierarchy
    /// with <c>AddAllLeavesHierarchyAsync</c>.</summary>
    [Tm1Endpoint("EntitySet Dimensions")]
    public Task UpdateAsync(string name, DimensionPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(DimensionPath(name), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>PATCH Dimensions('name')</c> with <c>If-Match</c>: changes the properties the patch
    /// sets, unless the dimension changed since it was read with <paramref name="etag"/>
    /// (<c>Dimension.ETag</c>); a <c>Tm1PreconditionFailedException</c> if it had changed.</summary>
    [Tm1Endpoint("EntitySet Dimensions")]
    public Task UpdateAsync(string name, DimensionPatch patch, string etag, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(DimensionPath(name), Require(patch, nameof(patch))).IfMatch(Require(etag, nameof(etag))), cancellationToken);

    /// <summary><c>DELETE Dimensions('name')</c>.</summary>
    [Tm1Endpoint("EntitySet Dimensions")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(DimensionPath(name)), cancellationToken);

    /// <summary><c>tm1.SaveAs</c>: copies a dimension under a new name, failing if one exists.</summary>
    [Tm1Endpoint("Action Dimension/SaveAs")]
    public Task<Dimension> SaveAsAsync(string name, string newName, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action<Dimension>(Tm1Operations.DimensionSaveAs, name, new Dictionary<string, object?> { ["Name"] = newName }), cancellationToken);

    /// <summary><c>tm1.SaveAs</c>: copies a dimension under a new name. <paramref name="overwrite"/>
    /// replaces a dimension that already has the new name; when overwriting,
    /// <paramref name="keepExistingAttributes"/> keeps the attributes of what's replaced, and when not,
    /// true creates no attributes for the copy.</summary>
    [Tm1Endpoint("Action Dimension/SaveAs")]
    public Task<Dimension> SaveAsAsync(
        string name,
        string newName,
        bool overwrite,
        bool keepExistingAttributes,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<Dimension>(
                Tm1Operations.DimensionSaveAs,
                name,
                new Dictionary<string, object?>
                {
                    ["Name"] = newName,
                    ["Overwrite"] = overwrite,
                    ["KeepExistingAttributes"] = keepExistingAttributes,
                }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.AddAllLeavesHierarchy</c>: adds a hierarchy of all the dimension's leaves, named
    /// <c>Leaves</c> and not visible, which makes it a multi-hierarchy dimension; returns the new hierarchy.</summary>
    [Tm1Endpoint("Action Dimension/AddAllLeavesHierarchy")]
    public Task<Hierarchy> AddAllLeavesHierarchyAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action<Hierarchy>(Tm1Operations.DimensionAddAllLeavesHierarchy, name, new Dictionary<string, object?>()), cancellationToken);

    /// <summary><c>tm1.AddAllLeavesHierarchy</c>: adds a hierarchy of all the dimension's leaves, named
    /// <paramref name="hierarchyName"/>, which makes it a multi-hierarchy dimension.</summary>
    [Tm1Endpoint("Action Dimension/AddAllLeavesHierarchy")]
    public Task<Hierarchy> AddAllLeavesHierarchyAsync(string name, string hierarchyName, bool visible, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Action<Hierarchy>(
                Tm1Operations.DimensionAddAllLeavesHierarchy,
                name,
                new Dictionary<string, object?> { ["AllLeavesHierarchyName"] = hierarchyName, ["Visible"] = visible }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.Lock</c>: stops everyone else changing the dimension until it's unlocked.</summary>
    [Tm1Endpoint("Action Dimension/Lock")]
    public Task LockAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.DimensionLock, name), cancellationToken);

    /// <summary><c>tm1.Unlock</c>.</summary>
    [Tm1Endpoint("Action Dimension/Unlock")]
    public Task UnlockAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.DimensionUnlock, name), cancellationToken);

    /// <summary><c>tm1.Reserve</c>: stops everyone else changing the dimension until it's released. Needs schema 11.8.34.</summary>
    [Tm1Endpoint("Action Dimension/Reserve")]
    public Task ReserveAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.DimensionReserve, name), cancellationToken);

    /// <summary><c>tm1.Release</c>. Needs schema 11.8.34.</summary>
    [Tm1Endpoint("Action Dimension/Release")]
    public Task ReleaseAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.DimensionRelease, name), cancellationToken);

    private static ODataPath DimensionPath(string name) => Dimensions.Key(Require(name, nameof(name)));

    private static Tm1Request<NoContent> Action(Tm1Operation operation, string name) =>
        Tm1Request.Post(DimensionPath(name).Action(operation.Name), new Dictionary<string, object?>()).Requires(operation.Capability());

    private static Tm1Request<T> Action<T>(Tm1Operation operation, string name, Dictionary<string, object?> body) =>
        Tm1Request.Post<T>(DimensionPath(name).Action(operation.Name), body).Requires(operation.Capability());
}

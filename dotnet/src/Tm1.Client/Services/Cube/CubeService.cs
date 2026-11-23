using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Cubes: <c>/Cubes</c>, a cube's dimensions, and the operations bound to a cube. Views
/// have a service of their own; cell data is read and written through cellsets.
/// <para>List cubes with a query: here, the model cubes whose names start with "Sales":</para>
/// <code>
/// tm1.Cubes.ModelCubesAsync(ODataQuery.None()
///     .Select(Cube.Fields.Name)
///     .Filter(Filter.StartsWith(Cube.Fields.Name, "Sales"))
///     .OrderBy(Cube.Fields.Name))
/// </code>
/// <para>Create a cube over existing dimensions, bound in order:</para>
/// <code>
/// var dimensions = ODataPath.Of("Dimensions");
/// await tm1.Cubes.CreateAsync(CubePatch.Builder().Name("Sales").BindDimensions(dimensions.Key("Region"), dimensions.Key("Month")).Build());
/// </code>
/// <para>Change a cube's rules, checking them first (TM1 saves rules even when they don't compile):</para>
/// <code>
/// var rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
/// var errors = await tm1.Cubes.CheckRulesAsync("Sales", rules).ToListAsync();
/// if (errors.Count == 0)
/// {
///     await tm1.Cubes.UpdateAsync("Sales", CubePatch.Builder().Rules(rules).Build());
/// }
/// </code>
/// </summary>
public sealed class CubeService : Tm1Service
{
    private static readonly ODataPath Cubes = ODataPath.Of("Cubes");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public CubeService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Cubes</c>: every cube, control cubes included, streamed.</summary>
    [Tm1Endpoint("EntitySet Cubes")]
    public IAsyncEnumerable<Cube> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Cube>(Cubes, query), cancellationToken);

    /// <summary><c>ModelCubes()</c>: the cubes that aren't control cubes (whose names start with <c>}</c>), streamed.</summary>
    [Tm1Endpoint("FunctionImport ModelCubes")]
    public IAsyncEnumerable<Cube> ModelCubesAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Cube>(ODataPath.FunctionImport("ModelCubes"), query), cancellationToken);

    /// <summary><c>ControlCubes()</c>: the control cubes, such as <c>}ClientGroups</c>, streamed.</summary>
    [Tm1Endpoint("FunctionImport ControlCubes")]
    public IAsyncEnumerable<Cube> ControlCubesAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Cube>(ODataPath.FunctionImport("ControlCubes"), query), cancellationToken);

    /// <summary><c>GET Cubes('name')</c>: the cube, or a <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("EntitySet Cubes")]
    public Task<Cube> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Cube>(CubePath(name), query), cancellationToken);

    /// <summary><c>POST Cubes</c>: creates a cube over existing dimensions, bound in order with
    /// <c>CubePatch.PatchBuilder.BindDimensions</c>, and optionally its rules. It needs a name and at
    /// least two dimensions; returns the cube as created.</summary>
    [Tm1Endpoint("EntitySet Cubes")]
    public Task<Cube> CreateAsync(CubePatch cube, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Cube>(Cubes, Require(cube, nameof(cube))), cancellationToken);

    /// <summary><c>PATCH Cubes('name')</c>: changes the properties the patch sets, typically <c>Rules</c>
    /// or <c>DrillthroughRules</c>; null clears them. With <paramref name="etag"/> (<c>Cube.ETag</c>,
    /// from a read), sent as <c>If-Match</c>: only if the cube hasn't changed since, else a
    /// <c>Tm1PreconditionFailedException</c>. TM1 (11.8) saves rules even when they don't compile:
    /// check them first with <see cref="CheckRulesAsync"/>.</summary>
    [Tm1Endpoint("EntitySet Cubes")]
    public async Task UpdateAsync(string name, CubePatch patch, string? etag = null, CancellationToken cancellationToken = default)
    {
        var request = Tm1Request.Patch(CubePath(name), Require(patch, nameof(patch)));
        await Session.ExecuteAsync(etag is null ? request : request.IfMatch(etag), cancellationToken).ConfigureAwait(false);
    }

    /// <summary><c>DELETE Cubes('name')</c>.</summary>
    [Tm1Endpoint("EntitySet Cubes")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(CubePath(name)), cancellationToken);

    /// <summary><c>GET Cubes('name')/Dimensions</c>: the cube's dimensions, in the cube's order. TM1 (11.8)
    /// lists the virtual <c>Sandboxes</c> dimension first.</summary>
    [Tm1Endpoint("Navigation Cube/Dimensions")]
    public IAsyncEnumerable<Dimension> DimensionsAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Dimension>(CubePath(name).Nav("Dimensions"), query), cancellationToken);

    /// <summary><c>tm1.DimensionsStorageOrder()</c>: the dimensions in the order TM1 stores the cube's cells.</summary>
    [Tm1Endpoint("Function Cube/DimensionsStorageOrder")]
    public IAsyncEnumerable<Dimension> DimensionsStorageOrderAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.CubeDimensionsStorageOrder;
        return Session.StreamAsync(
            Tm1Request.List<Dimension>(CubePath(name).Function(operation.Name), query).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>tm1.ReorderDimensions</c>: changes the order TM1 stores the cube's cells in, and returns
    /// the change in memory use, as a percentage.</summary>
    [Tm1Endpoint("Action Cube/ReorderDimensions")]
    public async Task<double> ReorderDimensionsAsync(string name, IReadOnlyList<string> dimensions, CancellationToken cancellationToken = default)
    {
        var bindings = dimensions.Select(d => ODataPath.Of("Dimensions").Key(d).ToString()).ToList();
        var result = await Session
            .ExecuteAsync(
                Action<JsonElement>(Tm1Operations.CubeReorderDimensions, name, new Dictionary<string, object?> { ["Dimensions@odata.bind"] = bindings }),
                cancellationToken
            )
            .ConfigureAwait(false);
        // A primitive result comes wrapped, as {"@odata.context": ..., "value": 12.5}
        return result.ValueKind == JsonValueKind.Object && result.TryGetProperty("value", out var v) ? v.GetDouble() : 0.0;
    }

    /// <summary><c>tm1.Load</c>: loads the cube into memory.</summary>
    [Tm1Endpoint("Action Cube/Load")]
    public Task LoadAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.CubeLoad, name), cancellationToken);

    /// <summary><c>tm1.Unload</c>: unloads the cube from memory.</summary>
    [Tm1Endpoint("Action Cube/Unload")]
    public Task UnloadAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.CubeUnload, name), cancellationToken);

    /// <summary><c>tm1.Lock</c>.</summary>
    [Tm1Endpoint("Action Cube/Lock")]
    public Task LockAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.CubeLock, name), cancellationToken);

    /// <summary><c>tm1.Unlock</c>.</summary>
    [Tm1Endpoint("Action Cube/Unlock")]
    public Task UnlockAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.CubeUnlock, name), cancellationToken);

    /// <summary><c>tm1.Reserve</c>: no one else can change the cube until it's released.</summary>
    [Tm1Endpoint("Action Cube/Reserve")]
    public Task ReserveAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.CubeReserve, name), cancellationToken);

    /// <summary><c>tm1.Release</c>.</summary>
    [Tm1Endpoint("Action Cube/Release")]
    public Task ReleaseAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Action(Tm1Operations.CubeRelease, name), cancellationToken);

    /// <summary><c>tm1.CheckRules</c>: the syntax errors in the cube's rules, or in <paramref name="rules"/>
    /// if given (checked as if they were the cube's, without saving them); none if they compile.</summary>
    [Tm1Endpoint("Action Cube/CheckRules")]
    public IAsyncEnumerable<RuleSyntaxError> CheckRulesAsync(string name, string? rules = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.CubeCheckRules;
        var body = rules is null ? new Dictionary<string, object?>() : new Dictionary<string, object?> { ["Rules"] = rules };
        return Session.StreamAsync(
            Tm1Request.PostList<RuleSyntaxError>(CubePath(name).Action(operation.Name), body).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>tm1.TraceCellCalculation</c>: how a cell's value is calculated, component by
    /// component; <paramref name="tuple"/> maps each dimension to an element.</summary>
    [Tm1Endpoint("Action Cube/TraceCellCalculation")]
    [Tm1Endpoint("Navigation CalculationComponent/Cube")]
    [Tm1Endpoint("Navigation CalculationComponent/Tuple")]
    public Task<CalculationComponent> TraceCellCalculationAsync(
        string name,
        IReadOnlyDictionary<string, string> tuple,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<CalculationComponent>(
                    Tm1Operations.CubeTraceCellCalculation,
                    name,
                    new Dictionary<string, object?> { ["Tuple@odata.bind"] = Bindings(tuple) }
                )
                .WithQuery(query),
            cancellationToken
        );

    /// <summary><c>tm1.TraceFeeders</c>: what a cell feeds. TM1 (11.8) needs a path to expand into a
    /// complex property (<c>FedCells/Tuple</c>); nested options (<c>FedCells($expand=Tuple)</c>) get 400.</summary>
    [Tm1Endpoint("Action Cube/TraceFeeders")]
    [Tm1Endpoint("Navigation FedCellDescriptor/Cube")]
    [Tm1Endpoint("Navigation FedCellDescriptor/Tuple")]
    public Task<FeederTrace> TraceFeedersAsync(
        string name,
        IReadOnlyDictionary<string, string> tuple,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) =>
        Session.ExecuteAsync(
            Action<FeederTrace>(Tm1Operations.CubeTraceFeeders, name, new Dictionary<string, object?> { ["Tuple@odata.bind"] = Bindings(tuple) })
                .WithQuery(query),
            cancellationToken
        );

    /// <summary><c>tm1.CheckFeeders</c>: the cells under a consolidated cell that aren't fed though they
    /// have values, chosen by <paramref name="selection"/>.</summary>
    [Tm1Endpoint("Action Cube/CheckFeeders")]
    [Tm1Endpoint("Navigation FedCellDescriptor/Cube")]
    [Tm1Endpoint("Navigation FedCellDescriptor/Tuple")]
    public IAsyncEnumerable<FedCellDescriptor> CheckFeedersAsync(
        string name,
        IReadOnlyDictionary<string, string> tuple,
        CheckFeedersSelection selection,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.CubeCheckFeeders;
        var body = new Dictionary<string, object?> { ["Tuple@odata.bind"] = Bindings(tuple), ["Selection"] = selection };
        return Session.StreamAsync(
            Tm1Request.PostList<FedCellDescriptor>(CubePath(name).Action(operation.Name), body).WithQuery(query).Requires(operation.Capability()),
            cancellationToken
        );
    }

    private static List<string> Bindings(IReadOnlyDictionary<string, string> tuple) =>
        tuple.Select(e => ODataPath.Of("Dimensions").Key(e.Key).Nav("Hierarchies").Key(e.Key).Nav("Elements").Key(e.Value).ToString()).ToList();

    private static ODataPath CubePath(string name) => Cubes.Key(Require(name, nameof(name)));

    private static Tm1Request<NoContent> Action(Tm1Operation operation, string name) =>
        Tm1Request.Post(CubePath(name).Action(operation.Name), new Dictionary<string, object?>()).Requires(operation.Capability());

    private static Tm1Request<T> Action<T>(Tm1Operation operation, string name, Dictionary<string, object?> body) =>
        Tm1Request.Post<T>(CubePath(name).Action(operation.Name), body).Requires(operation.Capability());
}

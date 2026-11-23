using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Cell writes to a cube, by coordinates. Cells are given as <see cref="Tuples.Of"/> maps of
/// dimension to element; reading is through cellsets (<see cref="CellsetService"/>), and writing
/// into one by ordinal is there too.
/// <para>Values are numbers for numeric cells and text for string cells; null clears a cell. TM1
/// (11.8) refuses a write to a consolidated cell (400, <c>CubeCellWriteStatusElementIsConsolidated</c>):
/// it doesn't spread; write the leaves.</para>
/// <para><c>tm1.Update</c> takes several cells, but TM1 (11.8) writes only the first, so
/// <see cref="UpdateAsync"/> takes one; <see cref="UpdateCellsAsync(string, IReadOnlyList{CubeUpdatePatch}, CancellationToken)"/>
/// writes many in one request.</para>
/// <para>Write one cell, given an element for every dimension of the cube, in order:</para>
/// <code>
/// await tm1.Cells.UpdateAsync("Sales", new Dictionary&lt;string, string&gt; { ["Region"] = "North", ["Month"] = "Jan" }, 42);
/// </code>
/// <para>Write many cells in one request:</para>
/// <code>
/// await tm1.Cells.UpdateCellsAsync("Sales", [CubeUpdatePatch.Builder().BindTuple(Tuples.Of(cell)).Value(42).Build()]);
/// </code>
/// </summary>
public sealed class CellService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public CellService(Tm1Session session)
        : base(session) { }

    /// <summary><c>tm1.Update</c>: writes one value to a cell; <paramref name="tuple"/> is dimension to
    /// element, as for <see cref="Tuples.Of"/>; null clears the cell.</summary>
    [Tm1Endpoint("Action Cube/Update")]
    [Tm1Endpoint("Navigation CellDescriptor/Tuple")]
    public Task UpdateAsync(string cube, IReadOnlyDictionary<string, string> tuple, object? value, CancellationToken cancellationToken = default)
    {
        var body = new Dictionary<string, object?>
        {
            ["Cells"] = new List<object> { new Dictionary<string, object?> { ["Tuple@odata.bind"] = Tuples.Bindings(Tuples.Of(tuple)) } },
            ["Value"] = value,
        };
        return Session.ExecuteAsync(Action(Tm1Operations.CubeUpdate, cube, body), cancellationToken);
    }

    /// <summary><c>tm1.UpdateCells</c>: writes many cells, each its own value, in one request. Needs schema
    /// 11.0.0. TM1 (11.8) requires a <c>Tuple</c> and a <c>Value</c> in every update (400 otherwise). The
    /// patch can also bind a <c>ReferenceCube</c> and <c>ReferenceCell</c>; on a leaf they didn't change
    /// what's written, and they don't copy that cell's value.</summary>
    [Tm1Endpoint("Action Cube/UpdateCells")]
    [Tm1Endpoint("Navigation CubeUpdate/Tuple")]
    [Tm1Endpoint("Navigation CubeUpdate/ReferenceCube")]
    [Tm1Endpoint("Navigation CubeUpdate/ReferenceCell")]
    public Task UpdateCellsAsync(string cube, IReadOnlyList<CubeUpdatePatch> updates, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Action(Tm1Operations.CubeUpdateCells, cube, new Dictionary<string, object?> { ["Updates"] = Require(updates, nameof(updates)).ToList() }),
            cancellationToken
        );

    /// <summary><c>tm1.UpdateCells</c> with an order: <c>ByLevel</c> writes leaves before the consolidations
    /// above them, so a spread to a consolidation isn't overwritten. Needs schema 11.0.0.</summary>
    [Tm1Endpoint("Action Cube/UpdateCells")]
    [Tm1Endpoint("Navigation CubeUpdate/Tuple")]
    [Tm1Endpoint("Navigation CubeUpdate/ReferenceCube")]
    [Tm1Endpoint("Navigation CubeUpdate/ReferenceCell")]
    public Task UpdateCellsAsync(string cube, IReadOnlyList<CubeUpdatePatch> updates, UpdateOrder order, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            Action(
                Tm1Operations.CubeUpdateCells,
                cube,
                new Dictionary<string, object?> { ["Updates"] = Require(updates, nameof(updates)).ToList(), ["Order"] = order }
            ),
            cancellationToken
        );

    private static Tm1Request<NoContent> Action(Tm1Operation operation, string cube, Dictionary<string, object?> body) =>
        Tm1Request.Post(ODataPath.Of("Cubes").Key(Require(cube, nameof(cube))).Action(operation.Name), body).Requires(operation.Capability());
}

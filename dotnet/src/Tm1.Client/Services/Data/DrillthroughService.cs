// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Drillthrough: from a cell to the detail behind it, through a drillthrough process
/// (<code>}Drill_…</code>). A cube drillthrough returns a cellset, of a view the process returns; a
/// relational one returns rows from an ODBC source, directly or kept as a <c>Rowset</c>.
/// <para>A cell's drillthroughs come from the cube's <c>DrillthroughRules</c>, which name the process;
/// <see cref="ScriptsAsync"/> lists them for a cell of a cellset. TM1 (11.8) passes such a process the
/// cube's name and the cell's element on each of its dimensions, so the process must declare that many
/// parameters (the cube's name, then one per dimension, in order); otherwise running the cell's
/// drillthrough fails with "process parameter count error" (code 248). Run directly
/// (<see cref="ExecuteProcessAsync"/>), it takes the parameters given. The returned cellsets and rowsets
/// stay on the server until deleted, like any cellset.</para>
/// <para><c>$metadata</c> declares a relational row (<c>DrillthroughRow</c>) with no properties and not
/// open, so relational rows come as <see cref="JsonElement"/>. A rowset's rows (<c>RowsetRow</c>) are
/// open, and keep their columns.</para>
/// <para>Drill from a cell of a query to the detail behind it:</para>
/// <code>
/// var scripts = await tm1.Drillthrough.ScriptsAsync(cellset.Id!, 0).ToListAsync();
/// var detail = await tm1.Drillthrough.ExecuteAsync(cellset.Id!, 0, scripts[0].Name!);
/// </code>
/// <para>Run a drillthrough process directly, with its parameters:</para>
/// <code>
/// var detail = await tm1.Drillthrough.ExecuteProcessAsync("}Drill_Sales",
///     [ProcessParameterPatch.Builder().Name("pRegion").Value("North").Build()]);
/// </code>
/// <para>Read the rows of a relational drillthrough as a rowset:</para>
/// <code>
/// var rowset = await tm1.Drillthrough.ExecuteRelationalProcessWithRowsetAsync("}Drill_Orders", []);
/// await foreach (var row in tm1.Drillthrough.RowsAsync(rowset.Id!, ODataQuery.None().Top(100))) { … }
/// await tm1.Drillthrough.DeleteRowsetAsync(rowset.Id!);
/// </code>
/// </summary>
public sealed class DrillthroughService : Tm1Service
{
    private static readonly ODataPath Rowsets = ODataPath.Of("Rowsets");

    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public DrillthroughService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Cellsets('id')/Cells(n)/DrillthroughScripts</c>: the drillthroughs a cell has,
    /// streamed, each a <c>CubeDrillthrough</c> or <c>RelationalDrillthrough</c>.</summary>
    [Tm1Endpoint("Navigation CellsetCell/DrillthroughScripts")]
    public IAsyncEnumerable<Drillthrough> ScriptsAsync(string cellset, long ordinal, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Drillthrough>(ScriptsPath(cellset, ordinal), query), cancellationToken);

    /// <summary><c>tm1.Execute</c> on a cell's cube drillthrough: the cellset of the view its process
    /// returns, left on the server until deleted. Needs schema 10.2.2.6. The process must take the cube's
    /// name and one parameter per dimension (see the class description).</summary>
    [Tm1Endpoint("Action CubeDrillthrough/Execute")]
    public Task<Cellset> ExecuteAsync(
        string cellset,
        long ordinal,
        string drillthrough,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.CubeDrillthroughExecute;
        return Session.ExecuteAsync(
            Tm1Request
                .Post<Cellset>(ScriptPath(cellset, ordinal, drillthrough).Action(operation.Name), new Dictionary<string, object?>())
                .WithQuery(query)
                .Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>tm1.Execute</c> on a cell's relational drillthrough: its rows, as JSON, streamed. Needs
    /// schema 10.2.2.6.</summary>
    [Tm1Endpoint("Action RelationalDrillthrough/Execute")]
    public IAsyncEnumerable<JsonElement> ExecuteRelationalAsync(
        string cellset,
        long ordinal,
        string drillthrough,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.RelationalDrillthroughExecute;
        return Session.StreamAsync(
            Tm1Request
                .PostList<JsonElement>(ScriptPath(cellset, ordinal, drillthrough).Action(operation.Name), new Dictionary<string, object?>())
                .Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>tm1.ExecuteWithRowset</c> on a cell's relational drillthrough: its rows, kept as a
    /// rowset to read with <see cref="RowsAsync"/>. Needs schema 11.6.0.</summary>
    [Tm1Endpoint("Action RelationalDrillthrough/ExecuteWithRowset")]
    public Task<Rowset> ExecuteRelationalWithRowsetAsync(string cellset, long ordinal, string drillthrough, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.RelationalDrillthroughExecuteWithRowset;
        return Session.ExecuteAsync(
            Tm1Request
                .Post<Rowset>(ScriptPath(cellset, ordinal, drillthrough).Action(operation.Name), new Dictionary<string, object?>())
                .Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>ExecuteCubeDrillthrough</c>: runs a cube drillthrough process directly, with its
    /// parameters, and returns the cellset of the view it returns, left on the server until deleted.
    /// Needs schema 11.2.0.</summary>
    /// <param name="process">The drillthrough process's name, such as <code>}Drill_Sales</code>.</param>
    /// <param name="parameters">The process's parameters, by name and value.</param>
    /// <param name="query">What to expand of the cellset.</param>
    /// <param name="cancellationToken">Cancels the request.</param>
    [Tm1Endpoint("ActionImport ExecuteCubeDrillthrough")]
    public Task<Cellset> ExecuteProcessAsync(
        string process,
        IReadOnlyList<ProcessParameterPatch> parameters,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.ExecuteCubeDrillthrough;
        return Session.ExecuteAsync(
            Tm1Request.Post<Cellset>(Root(operation), ProcessBody(process, parameters)).WithQuery(query).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>ExecuteRelationalDrillthrough</c>: runs a relational drillthrough process directly:
    /// its rows, as JSON, streamed. Needs schema 11.2.0.</summary>
    [Tm1Endpoint("ActionImport ExecuteRelationalDrillthrough")]
    public IAsyncEnumerable<JsonElement> ExecuteRelationalProcessAsync(
        string process,
        IReadOnlyList<ProcessParameterPatch> parameters,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.ExecuteRelationalDrillthrough;
        return Session.StreamAsync(
            Tm1Request.PostList<JsonElement>(Root(operation), ProcessBody(process, parameters)).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>ExecuteRelationalDrillthroughWithRowset</c>: runs a relational drillthrough process
    /// directly, keeping its rows as a rowset. Needs schema 11.6.0.</summary>
    [Tm1Endpoint("ActionImport ExecuteRelationalDrillthroughWithRowset")]
    public Task<Rowset> ExecuteRelationalProcessWithRowsetAsync(
        string process,
        IReadOnlyList<ProcessParameterPatch> parameters,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.ExecuteRelationalDrillthroughWithRowset;
        return Session.ExecuteAsync(
            Tm1Request.Post<Rowset>(Root(operation), ProcessBody(process, parameters)).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>GET Rowsets('id')</c>.</summary>
    [Tm1Endpoint("EntitySet Rowsets")]
    public Task<Rowset> RowsetAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Rowset>(Rowsets.Key(Require(id, nameof(id))), query), cancellationToken);

    /// <summary><c>GET Rowsets('id')/Rows</c>, streamed: each row keeps its columns.</summary>
    /// <param name="id">The rowset's ID.</param>
    /// <param name="query">What to select, filter, and page (<c>$top</c>, <c>$skip</c>).</param>
    /// <param name="cancellationToken">Cancels the enumeration.</param>
    [Tm1Endpoint("Navigation Rowset/Rows")]
    public IAsyncEnumerable<RowsetRow> RowsAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<RowsetRow>(Rowsets.Key(Require(id, nameof(id))).Nav("Rows"), query), cancellationToken);

    /// <summary><c>DELETE Rowsets('id')</c>: frees the rowset on the server.</summary>
    [Tm1Endpoint("EntitySet Rowsets")]
    public Task DeleteRowsetAsync(string id, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(Rowsets.Key(Require(id, nameof(id)))), cancellationToken);

    private static Dictionary<string, object?> ProcessBody(string process, IReadOnlyList<ProcessParameterPatch> parameters)
    {
        var body = new Dictionary<string, object?>
        {
            ["DrillthroughProcess@odata.bind"] = ODataPath.Of("Processes").Key(Require(process, nameof(process))).ToString(),
        };
        if (Require(parameters, nameof(parameters)).Count > 0)
        {
            body["Parameters"] = parameters.ToList();
        }
        return body;
    }

    private static ODataPath Root(Tm1Operation operation) => ODataPath.Of(operation.Import);

    private static ODataPath ScriptsPath(string cellset, long ordinal) =>
        ODataPath.Of("Cellsets").Key(Require(cellset, nameof(cellset))).Nav("Cells").Key(ordinal).Nav("DrillthroughScripts");

    private static ODataPath ScriptPath(string cellset, long ordinal, string drillthrough) =>
        ScriptsPath(cellset, ordinal).Key(Require(drillthrough, nameof(drillthrough)));
}

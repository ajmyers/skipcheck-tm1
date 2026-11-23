using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Cellsets: the server-side results of an MDX query or a view, with their axes and cells.
/// <para>A cellset lives on the server until it's deleted, or the session ends; one that isn't deleted
/// holds the server's memory. The 1:1 methods (<see cref="ExecuteAsync"/>, <see cref="CellsAsync"/>,
/// <see cref="PartitionAsync"/>, <see cref="DeleteAsync"/>) leave that to the caller.
/// <see cref="StreamAsync{R}"/> does it all: runs the query, pages the cells in order, and deletes the
/// cellset however the enumeration ends.</para>
/// <para>Read the cells of an MDX query, placing each by its ordinal on the axes:</para>
/// <code>
/// await foreach (var cell in tm1.Cellsets.StreamAsync(
///     "SELECT {[Month].[Jan],[Month].[Feb]} ON 0, {[Region].[North],[Region].[South]} ON 1 FROM [Sales]",
///     ODataQuery.None().Select("Ordinal", "Value"), 500, (cellset, cells) => cells))
/// {
///     Console.WriteLine($"{cell.Ordinal}: {cell.Value}");
/// }
/// </code>
/// <para>Write into the cells of a query by ordinal, deleting its cellset after:</para>
/// <code>
/// var cellset = await tm1.Cellsets.ExecuteAsync(mdx);
/// try
/// {
///     await tm1.Cellsets.UpdateAsync(cellset.Id!, 0, 42);
/// }
/// finally
/// {
///     await tm1.Cellsets.DeleteAsync(cellset.Id!);
/// }
/// </code>
/// <para>Read the values of a saved view:</para>
/// <code>
/// var cellset = await tm1.Cellsets.ExecuteViewAsync("Sales", ViewScope.Public, "Default",
///     ODataQuery.None().Expand("Cells", c => c.Select("Value")));
/// await tm1.Cellsets.DeleteAsync(cellset.Id!);
/// </code>
/// </summary>
public sealed class CellsetService : Tm1Service
{
    private static readonly ODataPath Cellsets = ODataPath.Of("Cellsets");

    /// <summary>How many pages <see cref="StreamAsync{R}"/> fetches at once, beyond the first.</summary>
    private const int PagesInFlight = 2;

    public CellsetService(Tm1Session session)
        : base(session) { }

    /// <summary><c>ExecuteMDX</c>: runs an MDX query, leaving its cellset on the server until
    /// <see cref="DeleteAsync"/>. Expand <c>Axes</c> and <c>Cells</c> (with <c>$top</c> and
    /// <c>$select</c>) to get them in the same round trip.</summary>
    [Tm1Endpoint("ActionImport ExecuteMDX")]
    public Task<Cellset> ExecuteAsync(string mdx, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ExecuteMdx;
        var request = Tm1Request
            .Post<Cellset>(ODataPath.Of(operation.ImportName!), new Dictionary<string, object?> { ["MDX"] = Require(mdx, nameof(mdx)) })
            .WithQuery(query)
            .Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }

    /// <summary><c>ExecuteMDXStatement</c>: runs an MDX statement for its effect, keeping no cellset.
    /// Needs schema 11.8.20. TM1 (11.8) runs a <c>SELECT</c> and returns nothing, and rejects
    /// <c>CREATE SET</c> as a syntax error (code 501).</summary>
    [Tm1Endpoint("ActionImport ExecuteMDXStatement")]
    public Task ExecuteStatementAsync(string mdx, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ExecuteMdxStatement;
        var request = Tm1Request
            .Post(ODataPath.Of(operation.ImportName!), new Dictionary<string, object?> { ["MDX"] = Require(mdx, nameof(mdx)) })
            .Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }

    /// <summary><c>tm1.Execute</c> on a native or MDX view: its cellset, left on the server until
    /// <see cref="DeleteAsync"/>.</summary>
    [Tm1Endpoint("Action NativeView/Execute")]
    [Tm1Endpoint("Action MDXView/Execute")]
    public Task<Cellset> ExecuteViewAsync(string cube, ViewScope scope, string view, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var path = ODataPath
            .Of("Cubes")
            .Key(Require(cube, nameof(cube)))
            .Nav(scope.Navigation())
            .Key(Require(view, nameof(view)))
            .Action(Tm1Operations.NativeViewExecute.Name);
        return Session.ExecuteAsync(Tm1Request.Post<Cellset>(path, new Dictionary<string, object?>()).WithQuery(query), cancellationToken);
    }

    /// <summary><c>GET Cellsets</c>, streamed. TM1 (11.8) lists none, though each cellset reads by its
    /// ID with <see cref="GetAsync"/>: keep the IDs of the cellsets you create.</summary>
    [Tm1Endpoint("EntitySet Cellsets")]
    public IAsyncEnumerable<Cellset> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Cellset>(Cellsets, query), cancellationToken);

    /// <summary><c>GET Cellsets('id')</c>: the cellset, or a <c>Tm1NotFoundException</c> if it's gone.</summary>
    [Tm1Endpoint("EntitySet Cellsets")]
    public Task<Cellset> GetAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Cellset>(CellsetPath(id), query), cancellationToken);

    /// <summary><c>DELETE Cellsets('id')</c>: frees the cellset's memory on the server.</summary>
    [Tm1Endpoint("EntitySet Cellsets")]
    public Task DeleteAsync(string id, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(CellsetPath(id)), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Axes</c>, streamed: columns (0), rows (1), and so on, then the
    /// slicer. TM1 (11.8) adds the slicer even without a <c>WHERE</c>: it holds the cube's other
    /// dimensions, such as its virtual <c>Sandboxes</c>, with cardinality 1.</summary>
    [Tm1Endpoint("Navigation Cellset/Axes")]
    public IAsyncEnumerable<CellsetAxis> AxesAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<CellsetAxis>(CellsetPath(id).Nav("Axes"), query), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Axes(n)/Hierarchies</c>, streamed.</summary>
    [Tm1Endpoint("Navigation CellsetAxis/Hierarchies")]
    public IAsyncEnumerable<Hierarchy> HierarchiesAsync(string id, int axis, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Hierarchy>(AxisPath(id, axis).Nav("Hierarchies"), query), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Axes(n)/Tuples</c>, streamed, in order: an axis can be large.</summary>
    [Tm1Endpoint("Navigation CellsetAxis/Tuples")]
    public IAsyncEnumerable<CellsetAxisTuple> TuplesAsync(string id, int axis, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<CellsetAxisTuple>(AxisPath(id, axis).Nav("Tuples"), query), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Axes(n)/Tuples(t)/Members</c>, streamed: one member per hierarchy
    /// on the axis.</summary>
    [Tm1Endpoint("Navigation CellsetAxisTuple/Members")]
    public IAsyncEnumerable<Member> TupleMembersAsync(
        string id,
        int axis,
        int tuple,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<Member>(AxisPath(id, axis).Nav("Tuples").Key(tuple).Nav("Members"), query), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Cells</c>, streamed, in ordinal order. Use <c>$top</c> and
    /// <c>$skip</c>, or <see cref="PartitionAsync"/>, for pages.</summary>
    [Tm1Endpoint("Navigation Cellset/Cells")]
    public IAsyncEnumerable<CellsetCell> CellsAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<CellsetCell>(CellsetPath(id).Nav("Cells"), query), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Cells(n)/Members</c>, streamed: the members that place a cell, one
    /// per hierarchy of the cube. TM1 (11.8) includes the virtual <c>Sandboxes</c> dimension's,
    /// <c>Base</c>, first.</summary>
    [Tm1Endpoint("Navigation CellsetCell/Members")]
    public IAsyncEnumerable<Member> CellMembersAsync(string id, long ordinal, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Member>(CellsetPath(id).Nav("Cells").Key(ordinal).Nav("Members"), query), cancellationToken);

    /// <summary><c>GET Cellsets('id')/Cube</c>: the cube the cellset is from.</summary>
    [Tm1Endpoint("Navigation Cellset/Cube")]
    public Task<Cube> CubeAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Cube>(CellsetPath(id).Nav("Cube"), query), cancellationToken);

    /// <summary><c>tm1.GetPartition(Begin=b,End=e)</c>: a rectangle of cells, streamed. Needs schema
    /// 10.2.2.3.
    /// <para>TM1 (11.8) reads <c>Begin</c> and <c>End</c> as the top-left and bottom-right cells of a
    /// rectangle on the first two axes, not as a range of ordinals: with two columns,
    /// <c>PartitionAsync(id, 2, 4)</c> is column 0 of rows 1 and 2, cells 2 and 4, not 3. A run of
    /// whole rows, from a row's first cell to a row's last, is a range.</para>
    /// <para><paramref name="query"/> selects what of each cell; across pages, keep it the same.</para></summary>
    [Tm1Endpoint("Function Cellset/GetPartition")]
    public IAsyncEnumerable<CellsetCell> PartitionAsync(
        string id,
        long begin,
        long end,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.CellsetGetPartition;
        var range = new Dictionary<string, object?> { ["Begin"] = begin, ["End"] = end };
        return Session.StreamAsync(
            Tm1Request.List<CellsetCell>(CellsetPath(id).Function(operation.Name, range), query).Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary><c>tm1.Update</c>: writes one value to a cell of the cellset, by ordinal. The action
    /// takes a range (<c>BeginOrdinal</c>, <c>EndOrdinal</c>), but TM1 (11.8) writes only the first cell
    /// of it, so this takes one ordinal; <see cref="UpdateCellsAsync"/> writes many. A null <paramref name="value"/> clears the cell.</summary>
    [Tm1Endpoint("Action Cellset/Update")]
    public Task UpdateAsync(string id, long ordinal, object? value, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            CellsetAction(
                Tm1Operations.CellsetUpdate,
                id,
                new Dictionary<string, object?>
                {
                    ["BeginOrdinal"] = ordinal,
                    ["EndOrdinal"] = ordinal,
                    ["Value"] = value,
                }
            ),
            cancellationToken
        );

    /// <summary><c>tm1.UpdateCells</c>: writes many of the cellset's cells, each its own value, by
    /// ordinal. Needs schema 11.0.0. Each of <paramref name="updates"/> is
    /// <c>CellsetUpdatePatch.Builder().Ordinal(n).Value(v)</c>, or with a reference cube and cell to copy
    /// from.</summary>
    [Tm1Endpoint("Action Cellset/UpdateCells")]
    [Tm1Endpoint("Navigation CellsetUpdate/ReferenceCube")]
    [Tm1Endpoint("Navigation CellsetUpdate/ReferenceCell")]
    public Task UpdateCellsAsync(string id, IReadOnlyList<CellsetUpdatePatch> updates, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            CellsetAction(Tm1Operations.CellsetUpdateCells, id, new Dictionary<string, object?> { ["Updates"] = Require(updates, nameof(updates)).ToList() }),
            cancellationToken
        );

    /// <summary><c>tm1.SaveViewAs</c>: saves the cellset's query as a public view of its cube, failing if
    /// one has the name. Needs schema 11.0.0.</summary>
    [Tm1Endpoint("Action Cellset/SaveViewAs")]
    public Task<View> SaveViewAsAsync(string id, string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            CellsetAction<View>(Tm1Operations.CellsetSaveViewAs, id, new Dictionary<string, object?> { ["Name"] = Require(name, nameof(name)) }),
            cancellationToken
        );

    /// <summary><c>tm1.SaveViewAs</c>: saves the cellset's query as a view of its cube, replacing one that
    /// has the name if <paramref name="overwrite"/>, private if <paramref name="makePrivate"/>. Needs
    /// schema 11.0.0.</summary>
    [Tm1Endpoint("Action Cellset/SaveViewAs")]
    public Task<View> SaveViewAsAsync(string id, string name, bool overwrite, bool makePrivate, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(
            CellsetAction<View>(
                Tm1Operations.CellsetSaveViewAs,
                id,
                new Dictionary<string, object?>
                {
                    ["Name"] = Require(name, nameof(name)),
                    ["Overwrite"] = overwrite,
                    ["MakePrivate"] = makePrivate,
                }
            ),
            cancellationToken
        );

    /// <summary>Runs an MDX query and streams its cells to <paramref name="handler"/>, then deletes the
    /// cellset, however the enumeration ends: completed, failed, cancelled or abandoned.
    /// <para>One round trip brings the axes (hierarchies by name, tuples with their members' names and
    /// unique names) and the first <paramref name="pageSize"/> cells; the rest come in pages of
    /// <c>GetPartition</c>, a few at a time and in ordinal order, so memory stays bounded on both sides
    /// however large the cellset.</para>
    /// <code>
    /// await foreach (var row in tm1.Cellsets.StreamAsync(mdx, ODataQuery.None().Select("Ordinal", "Value"), 1000,
    ///     (cellset, cells) => cells.Select(c => (c.Ordinal, c.Value))))
    /// {
    ///     …
    /// }
    /// </code></summary>
    /// <param name="mdx">The query.</param>
    /// <param name="cellQuery">What to select of each cell; include <c>Ordinal</c> to place them.</param>
    /// <param name="pageSize">How many cells to fetch per request.</param>
    /// <param name="handler">Given the cellset (its <c>ID</c> and expanded <c>Axes</c>) and its cells, in
    /// ordinal order; its result is this method's.</param>
    /// <param name="cancellationToken">Cancels the enumeration; the cellset is still deleted.</param>
    [Tm1Endpoint("ActionImport ExecuteMDX")]
    [Tm1Endpoint("Function Cellset/GetPartition")]
    [Tm1Endpoint("EntitySet Cellsets")]
    public IAsyncEnumerable<R> StreamAsync<R>(
        string mdx,
        ODataQuery cellQuery,
        int pageSize,
        Func<Cellset, IAsyncEnumerable<CellsetCell>, IAsyncEnumerable<R>> handler,
        CancellationToken cancellationToken = default
    )
    {
        Require(mdx, nameof(mdx));
        Require(cellQuery, nameof(cellQuery));
        Require(handler, nameof(handler));
        if (pageSize < 1)
        {
            throw new ArgumentException("pageSize must be positive: " + pageSize, nameof(pageSize));
        }
        return Stream(mdx, cellQuery, pageSize, handler, cancellationToken);
    }

    private async IAsyncEnumerable<R> Stream<R>(
        string mdx,
        ODataQuery query,
        int pageSize,
        Func<Cellset, IAsyncEnumerable<CellsetCell>, IAsyncEnumerable<R>> handler,
        [EnumeratorCancellation] CancellationToken cancellationToken
    )
    {
        var first = ODataQuery
            .None()
            .Expand("Axes", a => a.Expand("Hierarchies", h => h.Select("Name")).Expand("Tuples", t => t.Expand("Members", m => m.Select("Name", "UniqueName"))))
            .Expand("Cells", _ => query.Top(pageSize));
        var cellset = await ExecuteAsync(mdx, first, cancellationToken).ConfigureAwait(false);
        try
        {
            await foreach (
                var r in handler(cellset, Cells(cellset, query, pageSize, cancellationToken)).WithCancellation(cancellationToken).ConfigureAwait(false)
            )
            {
                yield return r;
            }
        }
        finally
        {
            // A cellset TM1 sent without an ID has nothing to delete
            if (cellset.Id is { } id)
            {
                await DeleteAsync(id, CancellationToken.None).ConfigureAwait(false);
            }
        }
    }

    /// <summary>The cells of a streamed cellset: the first page, which came inline, then the rest.
    /// <c>GetPartition</c> takes a rectangle, so with two axes the pages are whole rows: the first page's
    /// last row, if it came incomplete, is dropped and fetched again with the next. With more axes, rows
    /// aren't rectangles, so pages come from <c>Cells</c> with <c>$skip</c> and <c>$top</c>.</summary>
    private async IAsyncEnumerable<CellsetCell> Cells(
        Cellset cellset,
        ODataQuery query,
        int pageSize,
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    )
    {
        var inline = cellset.Cells ?? [];
        var axes = cellset.Axes ?? [];
        long total = 1;
        foreach (var axis in axes)
        {
            total *= Cardinality(axis);
        }
        if (inline.Count >= total)
        {
            foreach (var cell in inline)
            {
                yield return cell;
            }
            yield break;
        }
        var id = cellset.Id ?? throw new InvalidOperationException("TM1 sent the cellset without an ID");
        var pages = new List<Func<CancellationToken, IAsyncEnumerable<CellsetCell>>>();
        IReadOnlyList<CellsetCell> head;
        if (axes.Skip(2).Any(a => Cardinality(a) > 1))
        {
            long from = inline.Count;
            head = inline;
            for (long page = 0; page < (total - from + pageSize - 1) / pageSize; page++)
            {
                var skip = (int)(from + page * pageSize);
                pages.Add(ct => CellsAsync(id, query.Skip(skip).Top(pageSize), ct));
            }
        }
        else
        {
            long columns = axes.Count == 0 ? 1 : Cardinality(axes[0]);
            long rowsPerPage = Math.Max(1, pageSize / columns);
            long firstRow = inline.Count / columns;
            long rows = total / columns;
            head = inline.Take((int)(firstRow * columns)).ToList();
            for (long page = 0; page < (rows - firstRow + rowsPerPage - 1) / rowsPerPage; page++)
            {
                long top = firstRow + page * rowsPerPage;
                long bottom = Math.Min(top + rowsPerPage, rows) - 1;
                pages.Add(ct => PartitionAsync(id, top * columns, bottom * columns + columns - 1, query, ct));
            }
        }
        foreach (var cell in head)
        {
            yield return cell;
        }

        // A few pages in flight, yielded in order
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var inFlight = new Queue<Task<List<CellsetCell>>>();
        var next = 0;
        try
        {
            while (next < pages.Count || inFlight.Count > 0)
            {
                while (next < pages.Count && inFlight.Count < PagesInFlight)
                {
                    inFlight.Enqueue(Fetch(pages[next++], stop.Token));
                }
                foreach (var cell in await inFlight.Dequeue().ConfigureAwait(false))
                {
                    yield return cell;
                }
            }
        }
        finally
        {
            await stop.CancelAsync().ConfigureAwait(false);
            foreach (var pending in inFlight)
            {
                try
                {
                    await pending.ConfigureAwait(false);
                }
                catch (Exception)
                {
                    // Abandoned: only waited for, so the cellset isn't deleted under it
                }
            }
        }
    }

    private static async Task<List<CellsetCell>> Fetch(Func<CancellationToken, IAsyncEnumerable<CellsetCell>> page, CancellationToken cancellationToken)
    {
        var cells = new List<CellsetCell>();
        await foreach (var cell in page(cancellationToken).ConfigureAwait(false))
        {
            cells.Add(cell);
        }
        return cells;
    }

    private static long Cardinality(CellsetAxis axis) => axis.Cardinality ?? 1;

    private static Tm1Request<NoContent> CellsetAction(Tm1Operation operation, string id, object body) =>
        Tm1Request.Post(CellsetPath(id).Action(operation.Name), body).Requires(operation.Capability());

    private static Tm1Request<T> CellsetAction<T>(Tm1Operation operation, string id, object body) =>
        Tm1Request.Post<T>(CellsetPath(id).Action(operation.Name), body).Requires(operation.Capability());

    private static ODataPath CellsetPath(string id) => Cellsets.Key(Require(id, nameof(id)));

    private static ODataPath AxisPath(string id, int axis) => CellsetPath(id).Nav("Axes").Key(axis);
}

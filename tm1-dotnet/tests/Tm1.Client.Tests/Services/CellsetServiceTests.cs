using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>CellsetService against a fake TM1: the 1:1 methods send what the Java client's do, and
/// <c>StreamAsync</c> pages a cellset's cells in order and deletes it however the enumeration ends.</summary>
public partial class CellsetServiceTests
{
    private const string Expand = "$expand=Axes($expand=Hierarchies($select=Name),Tuples($expand=Members($select=Name,UniqueName))),Cells($select=Ordinal,Value;$top=";

    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ExecutesMdxStatementsAndViews()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "c1" };
        var tm1 = _tm1.Client();
        var cellset = await tm1.Cellsets.ExecuteAsync("SELECT FROM [Sales]", ODataQuery.None().Expand("Cells"));
        Assert.Equal("c1", cellset.Id);
        Assert.Equal(("POST", "ExecuteMDX?$expand=Cells", """{"MDX":"SELECT FROM [Sales]"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        var view = await tm1.Cellsets.ExecuteViewAsync("Sales", ViewScope.Private, "Mine", ODataQuery.None().Expand("Axes"));
        Assert.Equal("c1", view.Id);
        Assert.Equal(("POST", "Cubes('Sales')/PrivateViews('Mine')/tm1.Execute?$expand=Axes", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Cellsets.ExecuteStatementAsync("SELECT FROM [Sales]");
        Assert.Equal(("POST", "ExecuteMDXStatement", """{"MDX":"SELECT FROM [Sales]"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }

    [Fact]
    public async Task ListsGetsAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = "c1" });
        Assert.Equal("c1", Assert.Single(await tm1.Cellsets.ListAsync(ODataQuery.None().Top(1)).ToListAsync()).Id);
        Assert.Equal("GET Cellsets?$top=1", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "c1" };
        Assert.Equal("c1", (await tm1.Cellsets.GetAsync("c1", ODataQuery.None().Expand("Axes"))).Id);
        Assert.Equal("Cellsets('c1')?$expand=Axes", _tm1.Last.Target);

        _tm1.Respond = _ => null;
        await tm1.Cellsets.DeleteAsync("c1");
        Assert.Equal("DELETE Cellsets('c1')", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task NavigatesAxesTuplesMembersAndCells()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Ordinal = 0, Cardinality = 2, Name = "Region", UniqueName = "[Region].[Region].[North]" });

        Assert.Equal(2, Assert.Single(await tm1.Cellsets.AxesAsync("c1", ODataQuery.None().Expand("Tuples")).ToListAsync()).Cardinality);
        Assert.Equal("Cellsets('c1')/Axes?$expand=Tuples", _tm1.Last.Target);
        Assert.Equal("Region", Assert.Single(await tm1.Cellsets.HierarchiesAsync("c1", 1).ToListAsync()).Name);
        Assert.Equal("Cellsets('c1')/Axes(1)/Hierarchies", _tm1.Last.Target);
        Assert.Equal(0, Assert.Single(await tm1.Cellsets.TuplesAsync("c1", 0, ODataQuery.None().Top(5)).ToListAsync()).Ordinal);
        Assert.Equal("Cellsets('c1')/Axes(0)/Tuples?$top=5", _tm1.Last.Target);
        Assert.Equal("[Region].[Region].[North]", Assert.Single(await tm1.Cellsets.TupleMembersAsync("c1", 0, 3).ToListAsync()).UniqueName);
        Assert.Equal("Cellsets('c1')/Axes(0)/Tuples(3)/Members", _tm1.Last.Target);
        Assert.Equal(0, Assert.Single(await tm1.Cellsets.CellsAsync("c1", ODataQuery.None().Select("Value").Skip(2)).ToListAsync()).Ordinal);
        Assert.Equal("Cellsets('c1')/Cells?$select=Value&$skip=2", _tm1.Last.Target);
        Assert.Single(await tm1.Cellsets.CellMembersAsync("c1", 7).ToListAsync());
        Assert.Equal("Cellsets('c1')/Cells(7)/Members", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Sales" };
        Assert.Equal("Sales", (await tm1.Cellsets.CubeAsync("c1", ODataQuery.None().Select("Name"))).Name);
        Assert.Equal("Cellsets('c1')/Cube?$select=Name", _tm1.Last.Target);
    }

    [Fact]
    public async Task ReadsAPartition()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Ordinal = 2 }, new { Ordinal = 4 });
        var cells = await _tm1.Client().Cellsets.PartitionAsync("c1", 2, 4, ODataQuery.None().Select("Ordinal")).ToListAsync();
        Assert.Equal([2L, 4L], cells.Select(c => c.Ordinal!.Value));
        Assert.Equal("GET Cellsets('c1')/tm1.GetPartition(Begin=2,End=4)?$select=Ordinal", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task WritesCellsAndSavesViews()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => null;
        await tm1.Cellsets.UpdateAsync("c1", 5, 42);
        Assert.Equal(("POST", "Cellsets('c1')/tm1.Update", """{"BeginOrdinal":5,"EndOrdinal":5,"Value":42}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.Cellsets.UpdateAsync("c1", 5, null);
        Assert.Equal("""{"BeginOrdinal":5,"EndOrdinal":5,"Value":null}""", _tm1.Last.Text);

        await tm1.Cellsets.UpdateCellsAsync("c1", [CellsetUpdatePatch.Builder().Ordinal(0).Value("x").Build(), CellsetUpdatePatch.Builder().Ordinal(1).Value(2.5).Build()]);
        Assert.Equal(("Cellsets('c1')/tm1.UpdateCells", """{"Updates":[{"Ordinal":0,"Value":"x"},{"Ordinal":1,"Value":2.5}]}"""), (_tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Saved" };
        Assert.Equal("Saved", (await tm1.Cellsets.SaveViewAsAsync("c1", "Saved")).Name);
        Assert.Equal(("Cellsets('c1')/tm1.SaveViewAs", """{"Name":"Saved"}"""), (_tm1.Last.Target, _tm1.Last.Text));
        await tm1.Cellsets.SaveViewAsAsync("c1", "Saved", true, true);
        Assert.Equal("""{"Name":"Saved","Overwrite":true,"MakePrivate":true}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task StreamsTwoAxesInPagesOfWholeRows()
    {
        // 3 columns × 4 rows; the first page of 5 ends mid-row, so row 1 comes again with the partitions
        var tm1 = Cellset(12, [3, 4, 1], 5);
        var ordinals = await tm1.Cellsets.StreamAsync("SELECT", Cells(), 5, (_, cells) => cells.Select(c => c.Ordinal!.Value)).ToListAsync();
        Assert.Equal(Enumerable.Range(0, 12).Select(i => (long)i), ordinals);
        var targets = Targets();
        Assert.Equal("ExecuteMDX?" + Expand + "5)", Uri.UnescapeDataString(targets[0]));
        Assert.Equal(
            ["Cellsets('c1')/tm1.GetPartition(Begin=3,End=5)?$select=Ordinal,Value", "Cellsets('c1')/tm1.GetPartition(Begin=6,End=8)?$select=Ordinal,Value", "Cellsets('c1')/tm1.GetPartition(Begin=9,End=11)?$select=Ordinal,Value"],
            targets.Skip(1).SkipLast(1).Order());
        Assert.Equal("DELETE Cellsets('c1')", targets[^1]);
    }

    [Fact]
    public async Task StreamsManyRowsPerPage()
    {
        // 2 columns × 7 rows in pages of 4 cells: 2 rows a page
        var tm1 = Cellset(14, [2, 7], 4);
        var ordinals = await tm1.Cellsets.StreamAsync("SELECT", Cells(), 4, (_, cells) => cells.Select(c => c.Ordinal!.Value)).ToListAsync();
        Assert.Equal(Enumerable.Range(0, 14).Select(i => (long)i), ordinals);
        Assert.Equal(["Begin=12,End=13", "Begin=4,End=7", "Begin=8,End=11"], Targets().Where(t => t.Contains("GetPartition")).Select(t => Partition().Match(t).Value).Order());
    }

    [Fact]
    public async Task StreamsMoreAxesBySkipAndTop()
    {
        // 2 × 2 × 2: a third axis isn't a rectangle, so the pages are $skip and $top of Cells
        var tm1 = Cellset(8, [2, 2, 2], 3);
        var ordinals = await tm1.Cellsets.StreamAsync("SELECT", Cells(), 3, (_, cells) => cells.Select(c => c.Ordinal!.Value)).ToListAsync();
        Assert.Equal(Enumerable.Range(0, 8).Select(i => (long)i), ordinals);
        Assert.Equal(
            ["Cellsets('c1')/Cells?$select=Ordinal,Value&$top=3&$skip=3", "Cellsets('c1')/Cells?$select=Ordinal,Value&$top=3&$skip=6"],
            Targets().Where(t => t.Contains("/Cells?")).Order());
        Assert.Equal("DELETE Cellsets('c1')", Targets()[^1]);
    }

    [Fact]
    public async Task AStreamThatFitsInOnePageMakesNoMoreRequests()
    {
        var tm1 = Cellset(4, [2, 2], 10);
        var (id, count) = (await tm1.Cellsets.StreamAsync("SELECT", Cells(), 10, Summarize).ToListAsync()).Single();
        Assert.Equal(("c1", 4), (id, count));
        Assert.Equal(["ExecuteMDX?" + Expand + "10)", "DELETE Cellsets('c1')"], Targets().Select(Uri.UnescapeDataString));
    }

    [Fact]
    public async Task AStreamDeletesItsCellsetWhenAbandonedOrFailed()
    {
        var tm1 = Cellset(12, [3, 4], 3);
        await foreach (var _ in tm1.Cellsets.StreamAsync("SELECT", Cells(), 3, (_, cells) => cells))
        {
            break;
        }
        Assert.Equal("DELETE Cellsets('c1')", Targets()[^1]);

        _tm1.Requests.Clear();
        await Assert.ThrowsAsync<InvalidOperationException>(async () =>
            await tm1.Cellsets.StreamAsync<int>("SELECT", Cells(), 3, (_, _) => throw new InvalidOperationException("handler")).ToListAsync());
        Assert.Equal("DELETE Cellsets('c1')", Targets()[^1]);

        _tm1.Requests.Clear();
        using var cancel = new CancellationTokenSource();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
        {
            await foreach (var _ in tm1.Cellsets.StreamAsync("SELECT", Cells(), 3, (_, cells) => cells, cancel.Token))
            {
                await cancel.CancelAsync();
            }
        });
        Assert.Equal("DELETE Cellsets('c1')", Targets()[^1]);
    }

    [Fact]
    public void AStreamNeedsAPositivePageSize()
    {
        Assert.Throws<ArgumentException>(() => _tm1.Client().Cellsets.StreamAsync("SELECT", Cells(), 0, (_, cells) => cells));
        Assert.Empty(_tm1.Requests);
    }

    private static async IAsyncEnumerable<(string, int)> Summarize(Cellset cellset, IAsyncEnumerable<CellsetCell> cells)
    {
        yield return (cellset.Id!, await cells.CountAsync());
    }

    private static ODataQuery Cells() => ODataQuery.None().Select("Ordinal", "Value");

    private List<string> Targets() => _tm1.Requests.Select(r => r.Method == "DELETE" ? "DELETE " + r.Target : r.Target).ToList();

    /// <summary>A fake cellset of <paramref name="total"/> cells, on axes of these cardinalities, whose
    /// first <paramref name="inline"/> cells come with it; its pages answer by their range.</summary>
    private Tm1Client Cellset(int total, int[] cardinalities, int inline)
    {
        _tm1.Respond = r =>
        {
            if (r.Method == "DELETE")
            {
                return null;
            }
            if (r.Target.StartsWith("ExecuteMDX", StringComparison.Ordinal))
            {
                return new Dictionary<string, object?>
                {
                    ["ID"] = "c1",
                    ["Axes"] = cardinalities.Select((c, i) => new { Ordinal = i, Cardinality = c }).ToArray(),
                    ["Cells"] = Range(0, Math.Min(inline, total) - 1),
                };
            }
            if (Partition().Match(r.Target) is { Success: true } p)
            {
                return FakeTm1.Collection(Range(int.Parse(p.Groups[1].Value), int.Parse(p.Groups[2].Value)));
            }
            var skip = int.Parse(Regex.Match(r.Target, @"\$skip=(\d+)").Groups[1].Value);
            var top = int.Parse(Regex.Match(r.Target, @"\$top=(\d+)").Groups[1].Value);
            return FakeTm1.Collection(Range(skip, Math.Min(skip + top, total) - 1));
        };
        // The fake records requests one at a time: a stream has pages in flight together
        return _tm1.Client(b => b.HttpHandler(new OneAtATime(_tm1)));
    }

    private static object[] Range(int from, int to) => Enumerable.Range(from, Math.Max(0, to - from + 1)).Select(i => (object)new { Ordinal = i, Value = i * 10 }).ToArray();

    [GeneratedRegex(@"Begin=(\d+),End=(\d+)")]
    private static partial Regex Partition();

    private sealed class OneAtATime(HttpMessageHandler inner) : DelegatingHandler(inner)
    {
        private readonly SemaphoreSlim _one = new(1, 1);

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            await _one.WaitAsync(cancellationToken);
            try
            {
                return await base.SendAsync(request, cancellationToken);
            }
            finally
            {
                _one.Release();
            }
        }
    }
}

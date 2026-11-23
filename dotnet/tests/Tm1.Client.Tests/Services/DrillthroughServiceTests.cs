using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>DrillthroughService against a fake TM1: a cell's drillthroughs, drillthrough processes run
/// directly, and rowsets.</summary>
public class DrillthroughServiceTests
{
    private const string Script = "Cellsets('c1')/Cells(4)/DrillthroughScripts('}Drill_Sales')";

    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsACellsDrillthroughs()
    {
        _tm1.Respond = _ =>
            FakeTm1.Collection(new Dictionary<string, object?> { ["@odata.type"] = "#ibm.tm1.api.v1.CubeDrillthrough", ["Name"] = "}Drill_Sales" });
        var scripts = await _tm1.Client().Drillthrough.ScriptsAsync("c1", 4, ODataQuery.None().Select("Name")).ToListAsync();
        Assert.Equal("}Drill_Sales", Assert.Single(scripts).Name);
        Assert.Equal("GET Cellsets('c1')/Cells(4)/DrillthroughScripts?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task ExecutesACellsDrillthroughs()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "c2" };
        Assert.Equal("c2", (await tm1.Drillthrough.ExecuteAsync("c1", 4, "}Drill_Sales", ODataQuery.None().Expand("Cells"))).Id);
        Assert.Equal(("POST", Script + "/tm1.Execute?$expand=Cells", "{}"), (_tm1.Last.Method, Decoded(), _tm1.Last.Text));

        _tm1.Respond = _ => FakeTm1.Collection(new { Region = "North", Amount = 5 });
        var row = Assert.Single(await tm1.Drillthrough.ExecuteRelationalAsync("c1", 4, "}Drill_Sales").ToListAsync());
        Assert.Equal(5, row.GetProperty("Amount").GetInt32());
        Assert.Equal(("POST", Script + "/tm1.Execute", "{}"), (_tm1.Last.Method, Decoded(), _tm1.Last.Text));

        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "r1" };
        Assert.Equal("r1", (await tm1.Drillthrough.ExecuteRelationalWithRowsetAsync("c1", 4, "}Drill_Sales")).Id);
        Assert.Equal(("POST", Script + "/tm1.ExecuteWithRowset"), (_tm1.Last.Method, Decoded()));
    }

    [Fact]
    public async Task ExecutesProcessesDirectly()
    {
        var tm1 = _tm1.Client();
        var parameters = new[] { ProcessParameterPatch.Builder().Name("pRegion").Value("North").Build() };
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "c2" };
        Assert.Equal("c2", (await tm1.Drillthrough.ExecuteProcessAsync("}Drill_Sales", parameters, ODataQuery.None().Expand("Axes"))).Id);
        Assert.Equal(("POST", "ExecuteCubeDrillthrough?$expand=Axes"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Sales')","Parameters":[{"Name":"pRegion","Value":"North"}]}""", _tm1.Last.Text);
        await tm1.Drillthrough.ExecuteProcessAsync("}Drill_Sales", []);
        Assert.Equal("""{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Sales')"}""", _tm1.Last.Text);

        _tm1.Respond = _ => FakeTm1.Collection(new { Amount = 5 }, new { Amount = 6 });
        Assert.Equal(
            [5, 6],
            (await tm1.Drillthrough.ExecuteRelationalProcessAsync("}Drill_Orders", parameters).ToListAsync()).Select(r => r.GetProperty("Amount").GetInt32())
        );
        Assert.Equal(("POST", "ExecuteRelationalDrillthrough"), (_tm1.Last.Method, _tm1.Last.Target));

        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "r1" };
        Assert.Equal("r1", (await tm1.Drillthrough.ExecuteRelationalProcessWithRowsetAsync("}Drill_Orders", [])).Id);
        Assert.Equal(
            ("POST", "ExecuteRelationalDrillthroughWithRowset", """{"DrillthroughProcess@odata.bind":"Processes('%7DDrill_Orders')"}"""),
            (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text)
        );
    }

    [Fact]
    public async Task ReadsAndDeletesRowsets()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ID"] = "r1" };
        Assert.Equal("r1", (await tm1.Drillthrough.RowsetAsync("r1", ODataQuery.None().Select("ID"))).Id);
        Assert.Equal("Rowsets('r1')?$select=ID", _tm1.Last.Target);

        _tm1.Respond = _ => FakeTm1.Collection(new { Region = "North" }, new { Region = "South" });
        Assert.Equal(2, (await tm1.Drillthrough.RowsAsync("r1", ODataQuery.None().Top(2).Skip(1)).ToListAsync()).Count);
        Assert.Equal("Rowsets('r1')/Rows?$top=2&$skip=1", _tm1.Last.Target);

        _tm1.Respond = _ => null;
        await tm1.Drillthrough.DeleteRowsetAsync("r1");
        Assert.Equal("DELETE Rowsets('r1')", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    private string Decoded() => System.Uri.UnescapeDataString(_tm1.Last.Target);
}

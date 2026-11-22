using System.Collections.Generic;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>CellService against a fake TM1: writes by tuple, one or many.</summary>
public class CellServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task WritesACellByItsTuple()
    {
        _tm1.Respond = _ => null;
        await _tm1.Client().Cells.UpdateAsync("Sales", new Dictionary<string, string> { ["Region"] = "North", ["Month"] = "Jan" }, 42);
        Assert.Equal(("POST", "Cubes('Sales')/tm1.Update"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal(
            """{"Cells":[{"Tuple@odata.bind":["Dimensions('Region')/Hierarchies('Region')/Elements('North')","Dimensions('Month')/Hierarchies('Month')/Elements('Jan')"]}],"Value":42}""",
            _tm1.Last.Text);
    }

    [Fact]
    public async Task ClearsACellWithNull()
    {
        _tm1.Respond = _ => null;
        await _tm1.Client().Cells.UpdateAsync("Sales", new Dictionary<string, string> { ["Region"] = "North" }, null);
        Assert.EndsWith("\"Value\":null}", _tm1.Last.Text);
    }

    [Fact]
    public async Task WritesManyCellsInAnOrder()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        var updates = new[]
        {
            CubeUpdatePatch.Builder().BindTuple(Tuples.Of(new Dictionary<string, string> { ["Region"] = "North" })).Value(1).Build(),
            CubeUpdatePatch.Builder().BindTuple(Tuples.Element("Region", "Region", "South")).Value("x").Build(),
        };
        await tm1.Cells.UpdateCellsAsync("Sales", updates);
        Assert.Equal(("POST", "Cubes('Sales')/tm1.UpdateCells"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal(
            """{"Updates":[{"Tuple@odata.bind":["Dimensions('Region')/Hierarchies('Region')/Elements('North')"],"Value":1},{"Tuple@odata.bind":["Dimensions('Region')/Hierarchies('Region')/Elements('South')"],"Value":"x"}]}""",
            _tm1.Last.Text);

        await tm1.Cells.UpdateCellsAsync("Sales", updates, UpdateOrder.ByLevel);
        Assert.EndsWith("""],"Order":"ByLevel"}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task WritesInASandboxThroughItsView()
    {
        _tm1.Respond = _ => null;
        await _tm1.Client().WithSandbox("What if").Cells.UpdateAsync("Sales", new Dictionary<string, string> { ["Region"] = "North" }, 1);
        Assert.Equal("Cubes('Sales')/tm1.Update?!sandbox=What%20if", _tm1.Last.Target);
    }
}

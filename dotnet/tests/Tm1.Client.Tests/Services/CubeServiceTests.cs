using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>CubeService against a fake TM1: each method sends what the Java client's does, and reads
/// TM1's answer the same way.</summary>
public class CubeServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsWithAQuery()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Sales" });
        var tm1 = _tm1.Client();
        var query = ODataQuery.None().Select(Cube.Fields.Name).Filter(Filter.StartsWith(Cube.Fields.Name, "Sales")).OrderBy(Cube.Fields.Name);
        var cubes = await tm1.Cubes.ModelCubesAsync(query).ToListAsync();
        Assert.Equal("Sales", Assert.Single(cubes).Name);
        Assert.Equal("ModelCubes()?$select=Name&$filter=startswith(Name,'Sales')&$orderby=Name", _tm1.Last.Target);
        await tm1.Cubes.ListAsync(ODataQuery.None().Top(2)).ToListAsync();
        Assert.Equal("GET Cubes?$top=2", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }

    [Fact]
    public async Task GetsCreatesUpdatesAndDeletes()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Sales", ["@odata.etag"] = "W/\"1\"" };
        var tm1 = _tm1.Client();
        var cube = await tm1.Cubes.GetAsync("Sales", ODataQuery.None().Select("Name"));
        Assert.Equal(("Sales", "W/\"1\""), (cube.Name, cube.ETag));
        Assert.Equal("Cubes('Sales')?$select=Name", _tm1.Last.Target);

        var dimensions = ODataPath.Of("Dimensions");
        await tm1.Cubes.CreateAsync(CubePatch.Builder().Name("Sales").BindDimensions(dimensions.Key("Region"), dimensions.Key("Month")).Build());
        Assert.Equal("POST", _tm1.Last.Method);
        Assert.Equal("""{"Name":"Sales","Dimensions@odata.bind":["Dimensions('Region')","Dimensions('Month')"]}""", _tm1.Last.Text);

        _tm1.Respond = _ => null;
        await tm1.Cubes.UpdateAsync("Sales", CubePatch.Builder().Rules(null).Build());
        Assert.Equal(("PATCH", """{"Rules":null}"""), (_tm1.Last.Method, _tm1.Last.Text));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Cubes.UpdateAsync("Sales", CubePatch.Builder().Rules("SKIPCHECK;").Build(), "W/\"1\"");
        Assert.Equal("W/\"1\"", _tm1.Last.Headers["if-match"]);
        await tm1.Cubes.DeleteAsync("Sales");
        Assert.Equal(("DELETE", "Cubes('Sales')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task AStaleEtagIsRefused()
    {
        _tm1.Respond = _ => FakeTm1.Json(new { error = new { code = "278", message = "stale" } }, HttpStatusCode.PreconditionFailed);
        var e = await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() =>
            _tm1.Client().Cubes.UpdateAsync("Sales", CubePatch.Builder().Rules("x").Build(), "W/\"0\"")
        );
        Assert.Equal(Tm1ErrorKind.PreconditionFailed, e.Kind);
        Assert.Equal("278", e.Error!.Code);
    }

    [Fact]
    public async Task Operations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => null;
        foreach (
            var (call, action) in new (Func<Task>, string)[]
            {
                (() => tm1.Cubes.LoadAsync("Sales"), "Load"),
                (() => tm1.Cubes.UnloadAsync("Sales"), "Unload"),
                (() => tm1.Cubes.LockAsync("Sales"), "Lock"),
                (() => tm1.Cubes.UnlockAsync("Sales"), "Unlock"),
                (() => tm1.Cubes.ReserveAsync("Sales"), "Reserve"),
                (() => tm1.Cubes.ReleaseAsync("Sales"), "Release"),
            }
        )
        {
            await call();
            Assert.Equal(("POST", $"Cubes('Sales')/tm1.{action}", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        }
        _tm1.Respond = _ => new Dictionary<string, object?> { ["@odata.context"] = "$metadata#Edm.Double", ["value"] = 12.5 };
        Assert.Equal(12.5, await tm1.Cubes.ReorderDimensionsAsync("Sales", ["Month", "Region"]));
        Assert.Equal("""{"Dimensions@odata.bind":["Dimensions('Month')","Dimensions('Region')"]}""", _tm1.Last.Text);

        _tm1.Respond = _ => FakeTm1.Collection(new { LineNumber = 2, Message = "Syntax error" });
        var errors = await tm1.Cubes.CheckRulesAsync("Sales", "SKIPCHECK").ToListAsync();
        Assert.Equal(("Cubes('Sales')/tm1.CheckRules", """{"Rules":"SKIPCHECK"}"""), (_tm1.Last.Target, _tm1.Last.Text));
        Assert.Equal(2, errors[0].LineNumber);
        await tm1.Cubes.CheckRulesAsync("Sales").ToListAsync();
        Assert.Equal("{}", _tm1.Last.Text);

        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Month" });
        await tm1.Cubes.DimensionsAsync("Sales").ToListAsync();
        Assert.Equal("Cubes('Sales')/Dimensions", _tm1.Last.Target);
        await tm1.Cubes.DimensionsStorageOrderAsync("Sales").ToListAsync();
        Assert.Equal("Cubes('Sales')/tm1.DimensionsStorageOrder()", _tm1.Last.Target);
    }

    [Fact]
    public async Task TracesByCell()
    {
        var tm1 = _tm1.Client();
        var cell = new Dictionary<string, string> { ["Region"] = "North", ["Month"] = "Jan" };
        const string Elements =
            """["Dimensions('Region')/Hierarchies('Region')/Elements('North')","Dimensions('Month')/Hierarchies('Month')/Elements('Jan')"]""";
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Type"] = "Rule" };
        await tm1.Cubes.TraceCellCalculationAsync("Sales", cell, ODataQuery.None().Expand("Components"));
        Assert.Equal("Cubes('Sales')/tm1.TraceCellCalculation?$expand=Components", _tm1.Last.Target);
        Assert.Equal("{\"Tuple@odata.bind\":" + Elements + "}", _tm1.Last.Text);
        _tm1.Respond = _ => FakeTm1.Collection();
        await tm1.Cubes.CheckFeedersAsync("Sales", cell, CheckFeedersSelection.Unfed).ToListAsync();
        Assert.Equal("{\"Tuple@odata.bind\":" + Elements + ",\"Selection\":\"Unfed\"}", _tm1.Last.Text);
    }
}

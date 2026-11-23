using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>DimensionService against a fake TM1: each method sends what the Java client's does.</summary>
public class DimensionServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task Lists()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Region" });
        var tm1 = _tm1.Client();
        Assert.Equal("Region", Assert.Single(await tm1.Dimensions.ListAsync(ODataQuery.None().Top(1)).ToListAsync()).Name);
        Assert.Equal(("GET", "Dimensions?$top=1"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Dimensions.ModelDimensionsAsync(ODataQuery.None().Select("Name")).ToListAsync();
        Assert.Equal("ModelDimensions()?$select=Name", _tm1.Last.Target);
        await tm1.Dimensions.ControlDimensionsAsync().ToListAsync();
        Assert.Equal("ControlDimensions()", _tm1.Last.Target);
    }

    [Fact]
    public async Task GetsCreatesUpdatesAndDeletes()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Region", ["@odata.etag"] = "W/\"1\"" };
        var tm1 = _tm1.Client();
        var dimension = await tm1.Dimensions.GetAsync("Region", ODataQuery.None().Select("Name"));
        Assert.Equal(("Region", "W/\"1\""), (dimension.Name, dimension.ETag));
        Assert.Equal("Dimensions('Region')?$select=Name", _tm1.Last.Target);

        await tm1.Dimensions.CreateAsync(DimensionPatch.Builder().Name("Region").Build());
        Assert.Equal(("POST", "Dimensions", """{"Name":"Region"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Dimensions.UpdateAsync("Region", DimensionPatch.Builder().Name("Region").Build());
        Assert.Equal(("PATCH", "Dimensions('Region')"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Dimensions.UpdateAsync("Region", DimensionPatch.Builder().Name("Region").Build(), "W/\"1\"");
        Assert.Equal("W/\"1\"", _tm1.Last.Headers["if-match"]);
        await tm1.Dimensions.DeleteAsync("O'Brien");
        Assert.Equal(("DELETE", "Dimensions('O''Brien')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task Operations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Copy" };
        await tm1.Dimensions.SaveAsAsync("Region", "Copy");
        Assert.Equal(("POST", "Dimensions('Region')/tm1.SaveAs"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("Copy", _tm1.Last.Json.GetProperty("Name").GetString());
        await tm1.Dimensions.SaveAsAsync("Region", "Copy", true, false);
        Assert.True(_tm1.Last.Json.GetProperty("Overwrite").GetBoolean());
        Assert.False(_tm1.Last.Json.GetProperty("KeepExistingAttributes").GetBoolean());

        await tm1.Dimensions.AddAllLeavesHierarchyAsync("Region");
        Assert.Equal("Dimensions('Region')/tm1.AddAllLeavesHierarchy", _tm1.Last.Target);
        await tm1.Dimensions.AddAllLeavesHierarchyAsync("Region", "Leaves", true);
        Assert.Contains("Leaves", _tm1.Last.Text);

        _tm1.Respond = _ => null;
        foreach (
            var (call, action) in new (System.Func<Task>, string)[]
            {
                (() => tm1.Dimensions.LockAsync("Region"), "Lock"),
                (() => tm1.Dimensions.UnlockAsync("Region"), "Unlock"),
                (() => tm1.Dimensions.ReserveAsync("Region"), "Reserve"),
                (() => tm1.Dimensions.ReleaseAsync("Region"), "Release"),
            }
        )
        {
            await call();
            Assert.Equal(("POST", $"Dimensions('Region')/tm1.{action}"), (_tm1.Last.Method, _tm1.Last.Target));
        }
    }
}

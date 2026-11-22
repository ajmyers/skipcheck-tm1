using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>SqlDataSourceService against a fake TM1.</summary>
public class SqlDataSourceServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsAndGets()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Warehouse" });
        var tm1 = _tm1.Client();
        Assert.Equal("Warehouse", Assert.Single(await tm1.SqlDataSources.ListAsync(ODataQuery.None().Select("Name")).ToListAsync()).Name);
        Assert.Equal("GET SQLDataSources?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Warehouse" };
        Assert.Equal("Warehouse", (await tm1.SqlDataSources.GetAsync("Warehouse")).Name);
        Assert.Equal("GET SQLDataSources('Warehouse')", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }
}

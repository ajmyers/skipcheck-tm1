using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>MemberSetService against a fake TM1: MDX set expressions and the member sets they keep.</summary>
public class MemberSetServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ExecutesASetExpression()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Ordinal"] = 3, ["Cardinality"] = 2 };
        var set = await _tm1.Client().MemberSets.ExecuteAsync("{TM1SUBSETALL([Region].[Region])}", ODataQuery.None().Expand("Tuples"));
        Assert.Equal((3, 2), (set.Ordinal, set.Cardinality));
        Assert.Equal(("POST", "ExecuteMDXSetExpression?$expand=Tuples"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal("""{"MDX":"{TM1SUBSETALL([Region].[Region])}"}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task ListsGetsAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Ordinal = 3 });
        Assert.Equal(3, Assert.Single(await tm1.MemberSets.ListAsync(ODataQuery.None().Top(1)).ToListAsync()).Ordinal);
        Assert.Equal("GET MemberSets?$top=1", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Ordinal"] = 3 };
        Assert.Equal(3, (await tm1.MemberSets.GetAsync(3, ODataQuery.None().Select("Cardinality"))).Ordinal);
        Assert.Equal("MemberSets(3)?$select=Cardinality", _tm1.Last.Target);

        _tm1.Respond = _ => null;
        await tm1.MemberSets.DeleteAsync(3);
        Assert.Equal("DELETE MemberSets(3)", $"{_tm1.Last.Method} {_tm1.Last.Target}");
    }
}

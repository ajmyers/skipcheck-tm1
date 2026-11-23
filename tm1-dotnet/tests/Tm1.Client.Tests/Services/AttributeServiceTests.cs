using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>AttributeService and AttributeSet against a fake TM1: each sends what the Java client's does.</summary>
public class AttributeServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    public static IEnumerable<object[]> Sets() =>
        [
            [AttributeSet.Dimensions(), "DimensionAttributes"],
            [AttributeSet.Hierarchies("Region"), "Dimensions('Region')/HierarchyAttributes"],
            [AttributeSet.Elements("Region", "Leaves"), "Dimensions('Region')/Hierarchies('Leaves')/ElementAttributes"],
            [AttributeSet.Subsets("Region", "Region"), "Dimensions('Region')/Hierarchies('Region')/SubsetAttributes"],
            [AttributeSet.Cubes(), "CubeAttributes"],
            [AttributeSet.Views("Sales"), "Cubes('Sales')/ViewAttributes"],
            [AttributeSet.Processes(), "ProcessAttributes"],
            [AttributeSet.Chores(), "ChoreAttributes"],
        ];

    [Theory]
    [MemberData(nameof(Sets))]
    public async Task ListsGetsCreatesAndDeletesInEachSet(AttributeSet set, string path)
    {
        Assert.Equal(path, set.ToString());
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Manager", Type = "String" });
        var attribute = Assert.Single(await tm1.Attributes.ListAsync(set, ODataQuery.None().Select("Name", "Type")).ToListAsync());
        Assert.Equal(("Manager", AttributeType.String), (attribute.Name, attribute.Type));
        Assert.Equal(path + "?$select=Name,Type", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Manager", ["Type"] = "String" };
        await tm1.Attributes.GetAsync(set, "Manager");
        Assert.Equal(("GET", path + "('Manager')"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Attributes.CreateAsync(set, AttributeDefinitionPatch.Builder().Name("Manager").Type(AttributeType.String).Build());
        Assert.Equal(("POST", path, """{"Name":"Manager","Type":"String"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Attributes.DeleteAsync(set, "Manager");
        Assert.Equal(("DELETE", path + "('Manager')"), (_tm1.Last.Method, _tm1.Last.Target));
    }
}

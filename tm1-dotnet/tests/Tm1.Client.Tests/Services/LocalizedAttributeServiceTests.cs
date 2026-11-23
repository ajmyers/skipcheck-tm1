using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>LocalizedAttributeService and AttributeOwner against a fake TM1: each sends what the Java client's does.</summary>
public class LocalizedAttributeServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    public static IEnumerable<object[]> Owners() =>
        [
            [AttributeOwner.Dimension("Region"), "Dimensions('Region')"],
            [AttributeOwner.Hierarchy("Region", "Leaves"), "Dimensions('Region')/Hierarchies('Leaves')"],
            [AttributeOwner.Element("Region", "Region", "West"), "Dimensions('Region')/Hierarchies('Region')/Elements('West')"],
            [AttributeOwner.Member("Region", "Region", "West"), "Dimensions('Region')/Hierarchies('Region')/Members('West')"],
            [AttributeOwner.Subset("Region", "Region", SubsetScope.Private, "s"), "Dimensions('Region')/Hierarchies('Region')/PrivateSubsets('s')"],
            [AttributeOwner.Cube("Sales"), "Cubes('Sales')"],
            [AttributeOwner.View("Sales", ViewScope.Public, "v"), "Cubes('Sales')/Views('v')"],
            [AttributeOwner.View("Sales", ViewScope.Private, "v"), "Cubes('Sales')/PrivateViews('v')"],
            [AttributeOwner.Process("Load"), "Processes('Load')"],
            [AttributeOwner.Chore("Nightly"), "Chores('Nightly')"],
        ];

    [Theory]
    [MemberData(nameof(Owners))]
    public async Task ListsGetsAndCreatesForEachOwner(AttributeOwner owner, string path)
    {
        Assert.Equal(path, owner.ToString());
        var localized = path + "/LocalizedAttributes";
        var tm1 = _tm1.Client();
        _tm1.Respond = _ =>
            FakeTm1.Collection(
                new Dictionary<string, object?>
                {
                    ["LocaleID"] = "fr",
                    ["Attributes"] = new Dictionary<string, object?> { ["Caption"] = "Ouest" },
                }
            );
        var fr = Assert.Single(await tm1.LocalizedAttributes.ListAsync(owner, ODataQuery.None().Top(1)).ToListAsync());
        Assert.Equal(("fr", "Ouest"), (fr.LocaleID, fr.Attributes!.Caption));
        Assert.Equal(localized + "?$top=1", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["LocaleID"] = "de-CH" };
        await tm1.LocalizedAttributes.GetAsync(owner, "de-CH");
        Assert.Equal(("GET", localized + "('de-CH')"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.LocalizedAttributes.CreateAsync(
            owner,
            LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Caption("Ouest").Property("Manager", "Jean").Build()).Build()
        );
        Assert.Equal(
            ("POST", localized, """{"LocaleID":"fr","Attributes":{"Caption":"Ouest","Manager":"Jean"}}"""),
            (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text)
        );
    }

    [Fact]
    public void AViewIsInItsScopesCollection()
    {
        Assert.Equal("Cubes('Sales')/Views('v')", AttributeOwner.View("Sales", ViewScope.Public, "v").ToString());
        Assert.Equal("Cubes('Sales')/PrivateViews('v')", AttributeOwner.View("Sales", ViewScope.Private, "v").ToString());
    }
}

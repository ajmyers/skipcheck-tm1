using System;
using System.Collections.Generic;
using System.Linq;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>Tuples: a cell's element paths, as the Java client's <c>Tuples</c> builds them.</summary>
public class TuplesTests
{
    [Fact]
    public void PlacesEachElementInItsDimensionsOwnHierarchyInOrder()
    {
        var tuple = Tuples.Of(new Dictionary<string, string> { ["Region"] = "North", ["Month"] = "Jan's" });
        Assert.Equal(
            ["Dimensions('Region')/Hierarchies('Region')/Elements('North')", "Dimensions('Month')/Hierarchies('Month')/Elements('Jan''s')"],
            tuple.Select(p => p.ToString())
        );
    }

    [Fact]
    public void PlacesAnElementInAnyHierarchy()
    {
        Assert.Equal("Dimensions('Region')/Hierarchies('By%20Manager')/Elements('North')", Tuples.Element("Region", "By Manager", "North").ToString());
        Assert.Throws<ArgumentNullException>(() => Tuples.Element("Region", null!, "North"));
        Assert.Throws<ArgumentNullException>(() => Tuples.Of(null!));
    }
}

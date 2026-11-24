// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>DimensionService</c>, shown in the guide.</summary>
public static class DimensionServiceExamples
{
    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // The names of the model dimensions, in name order
        return tm1
            .Dimensions.ModelDimensionsAsync(ODataQuery.None().Select(Dimension.Fields.Name).OrderBy(Dimension.Fields.Name))
            .Select(dimension => dimension.Name!);
        // @end
    }

    public static async Task<Dimension> Create(Tm1Client tm1)
    {
        // @start region = create
        // Region, with one hierarchy of the same name: Total = North + South
        var total = ElementCreate.Builder().Name("Total").Type(ElementType.Consolidated).Build();
        var north = ElementCreate.Builder().Name("North").Type(ElementType.Numeric).Build();
        var south = ElementCreate.Builder().Name("South").Type(ElementType.Numeric).Build();
        return await tm1.Dimensions.CreateAsync(
            DimensionCreate
                .Builder()
                .Name("Region")
                .Hierarchies([
                    HierarchyCreate
                        .Builder()
                        .Name("Region")
                        .Elements([total, north, south])
                        .Edges([
                            EdgePatch.Builder().ParentName("Total").ComponentName("North").Weight(1.0).Build(),
                            EdgePatch.Builder().ParentName("Total").ComponentName("South").Weight(1.0).Build(),
                        ])
                        .Build(),
                ])
                .Build()
        );
        // @end
    }

    public static async Task<List<string>> Hierarchies(Tm1Client tm1)
    {
        // @start region = hierarchies
        // A dimension with its hierarchies' names expanded in the same request
        var region = await tm1.Dimensions.GetAsync(
            "Region",
            ODataQuery.None().Expand(Dimension.Fields.Hierarchies, hierarchies => hierarchies.Select(Hierarchy.Fields.Name))
        );
        return region.Hierarchies!.Select(hierarchy => hierarchy.Name!).ToList();
        // @end
    }
}

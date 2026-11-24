// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>SubsetService</c>, shown in the guide.</summary>
public static class SubsetServiceExamples
{
    public static async Task<Subset> CreateStatic(Tm1Client tm1)
    {
        // @start region = create-static
        // A static subset lists its elements, bound by their paths
        var elements = ODataPath.Of("Dimensions").Key("Region").Nav("Hierarchies").Key("Region").Nav("Elements");
        return await tm1.Subsets.CreateAsync(
            "Region",
            "Region",
            SubsetScope.Public,
            SubsetCreate.Builder().Name("Key Regions").BindElements(elements.Key("North"), elements.Key("South")).Build()
        );
        // @end
    }

    public static async Task<Subset> CreateDynamic(Tm1Client tm1)
    {
        // @start region = create-dynamic
        // A dynamic subset has an MDX expression, evaluated each time it's read
        return await tm1.Subsets.CreateAsync(
            "Region",
            "Region",
            SubsetScope.Public,
            SubsetCreate.Builder().Name("All Leaves").Expression("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}").Build()
        );
        // @end
    }

    public static async Task ReplaceElements(Tm1Client tm1)
    {
        // @start region = replace
        // A PATCH appends elements, so empty the list, then add the new ones in order
        await tm1.Subsets.ClearElementsAsync("Region", "Region", SubsetScope.Public, "Key Regions");
        await tm1.Subsets.AddElementsAsync("Region", "Region", SubsetScope.Public, "Key Regions", ["East", "West"]);
        // @end
    }

    public static async Task<List<string>> Session(Tm1Client tm1)
    {
        // @start region = session
        // TM1 names a session subset itself, ignoring the name sent: read it by the name
        // it gives back
        var subset = await tm1.Subsets.CreateSessionSubsetAsync(
            "Region",
            "Region",
            SubsetCreate.Builder().Name("Regions").Expression("{[Region].[Region].[Total].Children}").Build()
        );
        return await tm1
            .Subsets.ElementsAsync("Region", "Region", SubsetScope.Session, subset.Name!, ODataQuery.None().Select(Element.Fields.Name))
            .Select(element => element.Name!)
            .ToListAsync();
        // @end
    }
}

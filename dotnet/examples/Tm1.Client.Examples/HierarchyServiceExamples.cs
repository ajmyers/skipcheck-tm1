// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>HierarchyService</c>, shown in the guide.</summary>
public static class HierarchyServiceExamples
{
    public static async Task<Hierarchy> Create(Tm1Client tm1)
    {
        // @start region = create
        // A second hierarchy of Region, rolling the same leaves up by manager
        return await tm1.Hierarchies.CreateAsync(
            "Region",
            HierarchyPatch
                .Builder()
                .Name("By Manager")
                .Elements([
                    ElementPatch.Builder().Name("Smith").Type(ElementType.Consolidated).Build(),
                    ElementPatch.Builder().Name("North").Type(ElementType.Numeric).Build(),
                    ElementPatch.Builder().Name("South").Type(ElementType.Numeric).Build(),
                ])
                .Edges([
                    EdgePatch.Builder().ParentName("Smith").ComponentName("North").Weight(1.0).Build(),
                    EdgePatch.Builder().ParentName("Smith").ComponentName("South").Weight(1.0).Build(),
                ])
                .Build()
        );
        // @end
    }

    public static async Task ReplaceStructure(Tm1Client tm1)
    {
        // @start region = replace
        // Makes the elements and edges exactly these: TM1 deletes every other element of
        // the hierarchy, with its data
        var all = ElementPatch.Builder().Name("All Versions").Type(ElementType.Consolidated).Build();
        var actual = ElementPatch.Builder().Name("Actual").Type(ElementType.Numeric).Build();
        var budget = ElementPatch.Builder().Name("Budget").Type(ElementType.Numeric).Build();
        await tm1.Hierarchies.ReplaceStructureAsync(
            "Version",
            "Version",
            [all, actual, budget],
            [
                EdgePatch.Builder().ParentName("All Versions").ComponentName("Actual").Weight(1.0).Build(),
                EdgePatch.Builder().ParentName("All Versions").ComponentName("Budget").Weight(1.0).Build(),
            ]
        );
        // @end
    }

    public static IAsyncEnumerable<string> Roots(Tm1Client tm1)
    {
        // @start region = roots
        // The top of the hierarchy: the members with no parent, in order
        return tm1.Hierarchies.RootMembersAsync("Region", "Region", ODataQuery.None().Select(Member.Fields.Name)).Select(member => member.Name!);
        // @end
    }
}

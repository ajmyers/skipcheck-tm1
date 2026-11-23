using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ElementService</c>, shown in the guide.</summary>
public static class ElementServiceExamples
{
    public static IAsyncEnumerable<string> Consolidations(Tm1Client tm1)
    {
        // @start region = filter
        // The names of the consolidated elements, in name order
        return tm1
            .Elements.ListAsync(
                "Region",
                "Region",
                ODataQuery.None().Select(Element.Fields.Name).Filter(Element.Fields.Type.Eq(ElementType.Consolidated)).OrderBy(Element.Fields.Name)
            )
            .Select(element => element.Name!);
        // @end
    }

    public static async Task AddChild(Tm1Client tm1)
    {
        // @start region = add-child
        // A new element is a root; SetComponent then puts it last under Total
        await tm1.Elements.CreateAsync("Region", "Region", ElementPatch.Builder().Name("West").Type(ElementType.Numeric).Build());
        await tm1.Elements.SetComponentAsync("Region", "Region", "Total", "West");
        // @end
    }

    public static IAsyncEnumerable<string> Children(Tm1Client tm1)
    {
        // @start region = children
        // The children of Total, in order
        return tm1.Elements.ComponentsAsync("Region", "Region", "Total", ODataQuery.None().Select(Element.Fields.Name)).Select(element => element.Name!);
        // @end
    }
}

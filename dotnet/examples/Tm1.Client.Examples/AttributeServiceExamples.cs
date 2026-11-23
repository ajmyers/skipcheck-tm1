using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>AttributeService</c>, shown in the guide.</summary>
public static class AttributeServiceExamples
{
    public static async Task<AttributeDefinition> Create(Tm1Client tm1)
    {
        // @start region = create
        // A text attribute on every element of the Region hierarchy
        return await tm1.Attributes.CreateAsync(
            AttributeSet.Elements("Region", "Region"),
            AttributeDefinitionPatch.Builder().Name("Manager").Type(AttributeType.String).Build()
        );
        // @end
    }

    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // The attributes that every cube has
        return tm1.Attributes.ListAsync(AttributeSet.Cubes(), ODataQuery.None().Select(AttributeDefinition.Fields.Name)).Select(attribute => attribute.Name!);
        // @end
    }
}

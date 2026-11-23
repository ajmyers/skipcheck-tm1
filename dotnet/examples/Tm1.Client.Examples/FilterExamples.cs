// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>Filter</c>, shown in the guide.</summary>
public static class FilterExamples
{
    public static IReadOnlyList<Filter> Build()
    {
        // @start region = build
        var numericQ = Filter.And(
            Filter.StartsWith(Element.Fields.Name, "Q"),
            Element.Fields.Type.Eq(ODataLiterals.EnumMember("ibm.tm1.api.v1.ElementType", "Numeric"))
        );
        // (startswith(Name,'Q') and (Type eq ibm.tm1.api.v1.ElementType'Numeric'))
        var hasRegion = Filter.Any("Hierarchies", "h", h => h.Nav("Name").Eq("Region"));
        // Hierarchies/any(h:h/Name eq 'Region')
        // @end
        return [numericQ, hasRegion];
    }
}

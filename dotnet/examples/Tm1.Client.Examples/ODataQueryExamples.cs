// SPDX-License-Identifier: Apache-2.0

using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ODataQuery</c>, shown in the guide.</summary>
public static class ODataQueryExamples
{
    public static string Build()
    {
        // @start region = build
        var query = ODataQuery
            .None()
            .Select("Name", "Type")
            .Expand("Hierarchies", h => h.Select("Name").Top(5))
            .Filter(Filter.StartsWith(ODataProperty.Of("Name"), "Q"))
            .ToQueryString();
        // $select=Name,Type&$expand=Hierarchies($select=Name;$top=5)&$filter=startswith(Name,'Q'), each value encoded
        // @end
        return query;
    }
}

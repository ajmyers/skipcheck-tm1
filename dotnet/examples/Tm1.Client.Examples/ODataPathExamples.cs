// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ODataPath</c>, shown in the guide.</summary>
public static class ODataPathExamples
{
    public static IReadOnlyList<ODataPath> Build()
    {
        // @start region = build
        var element = ODataPath.Of("Dimensions").Key("Region").Nav("Hierarchies").Key("Region").Nav("Elements").Key("O'Neil");
        // Dimensions('Region')/Hierarchies('Region')/Elements('O''Neil')
        var reserve = ODataPath.Of("Cubes").Key("Sales").Action("tm1.Reserve");
        // Cubes('Sales')/tm1.Reserve
        // @end
        return [element, reserve];
    }
}

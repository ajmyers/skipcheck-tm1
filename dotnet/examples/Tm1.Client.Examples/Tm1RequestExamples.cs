// SPDX-License-Identifier: Apache-2.0

using System.Text.Json;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>Tm1Request</c>, shown in the guide.</summary>
public static class Tm1RequestExamples
{
    public static (Tm1Request<JsonElement> Cube, Tm1Request<NoContent> Reserve) Build()
    {
        // @start region = build
        // A cube's name, read as plain JSON
        var cube = Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("Sales"), ODataQuery.None().Select("Name"));
        // An action with no answer
        var reserve = Tm1Request.Post(ODataPath.Of("Cubes").Key("Sales").Action("tm1.Reserve"), new { });
        // @end
        return (cube, reserve);
    }
}

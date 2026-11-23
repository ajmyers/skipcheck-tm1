// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>CubeService</c>, shown in the guide.</summary>
public static class CubeServiceExamples
{
    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // The names of the model cubes starting with "Sales", in name order
        return tm1
            .Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name).Filter(Filter.StartsWith(Cube.Fields.Name, "Sales")).OrderBy(Cube.Fields.Name))
            .Select(cube => cube.Name!);
        // @end
    }

    public static async Task<Cube> Create(Tm1Client tm1)
    {
        // @start region = create
        // A cube over two existing dimensions, in this order
        var dimensions = ODataPath.Of("Dimensions");
        return await tm1.Cubes.CreateAsync(CubePatch.Builder().Name("Sales").BindDimensions(dimensions.Key("Region"), dimensions.Key("Month")).Build());
        // @end
    }

    public static async Task UpdateRules(Tm1Client tm1)
    {
        // @start region = update-rules
        // TM1 saves rules even when they don't compile, so check them first
        var rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
        var errors = await tm1.Cubes.CheckRulesAsync("Sales", rules).ToListAsync();
        if (errors.Count > 0)
        {
            throw new ArgumentException("The rules don't compile: " + string.Join("; ", errors.Select(e => e.Message)));
        }
        await tm1.Cubes.UpdateAsync("Sales", CubePatch.Builder().Rules(rules).Build());
        // @end
    }
}

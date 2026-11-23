using System.Collections.Generic;
using System.Linq;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>LevelService</c>, shown in the guide.</summary>
public static class LevelServiceExamples
{
    public static IAsyncEnumerable<string> List(Tm1Client tm1)
    {
        // @start region = list
        // Each level's number and how many members it has, as "1: 2"
        return tm1.Levels.ListAsync("Region", "Region").Select(level => $"{level.Number}: {level.Cardinality}");
        // @end
    }

    public static IAsyncEnumerable<string> Members(Tm1Client tm1)
    {
        // @start region = members
        // Levels count from the top: level 0 holds the roots, level 1 their children
        return tm1.Levels.MembersAsync("Region", "Region", 1, ODataQuery.None().Select(Member.Fields.Name)).Select(member => member.Name!);
        // @end
    }
}

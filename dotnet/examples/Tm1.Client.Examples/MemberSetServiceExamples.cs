using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>MemberSetService</c>, shown in the guide.</summary>
public static class MemberSetServiceExamples
{
    public static async Task<List<string>> Names(Tm1Client tm1)
    {
        // @start region = names
        // The names of Region's leaves, with the member set deleted once they're read
        var set = await tm1.MemberSets.ExecuteAsync(
            "{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}",
            ODataQuery.None().Expand(CellsetAxis.Fields.Tuples, tuples => tuples.Expand("Members", members => members.Select(Member.Fields.Name)))
        );
        await tm1.MemberSets.DeleteAsync(set.Ordinal!.Value);
        return set.Tuples!.Select(tuple => tuple.Members![0].Name!).ToList();
        // @end
    }

    public static async Task<int> Keep(Tm1Client tm1)
    {
        // @start region = keep
        // Keep a large set on the server and read it later by its ordinal; TM1 doesn't
        // list member sets, so hold on to the ordinal, and delete the set when done
        var set = await tm1.MemberSets.ExecuteAsync("{TM1SUBSETALL([Product].[Product])}");
        return set.Ordinal!.Value;
        // @end
    }
}

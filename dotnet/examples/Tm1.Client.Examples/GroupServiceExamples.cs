using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>GroupService</c>, shown in the guide.</summary>
public static class GroupServiceExamples
{
    public static IAsyncEnumerable<string> Members(Tm1Client tm1)
    {
        // @start region = members
        return tm1.Groups.UsersAsync("Planners", ODataQuery.None().Select(User.Fields.Name)).Select(user => user.Name!);
        // @end
    }

    public static async Task CreateAndAdd(Tm1Client tm1)
    {
        // @start region = create
        // A new group, then alice added to it, from her side: her groups plus this one
        await tm1.Groups.CreateAsync("Planners");
        var groups = await tm1.Users.GroupsAsync("alice", ODataQuery.None().Select(Group.Fields.Name)).Select(group => group.Name!).ToListAsync();
        await tm1.Users.SetGroupsAsync("alice", [.. groups, "Planners"]);
        // @end
    }
}

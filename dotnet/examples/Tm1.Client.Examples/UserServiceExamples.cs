using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>UserService</c>, shown in the guide.</summary>
public static class UserServiceExamples
{
    public static async Task<User> Create(Tm1Client tm1)
    {
        // @start region = create
        // A user in two existing groups, bound by their paths
        var groups = ODataPath.Of("Groups");
        return await tm1.Users.CreateAsync(
            UserPatch.Builder().Name("alice").Password("initial-password").BindGroups(groups.Key("Planners"), groups.Key("Sales")).Build()
        );
        // @end
    }

    public static async Task LeaveGroup(Tm1Client tm1)
    {
        // @start region = leave-group
        // Groups are set whole: keep every group but Planners
        var rest = await tm1
            .Users.GroupsAsync("alice", ODataQuery.None().Select(Group.Fields.Name))
            .Select(group => group.Name!)
            .Where(group => group != "Planners")
            .ToListAsync();
        await tm1.Users.SetGroupsAsync("alice", rest);
        // @end
    }

    public static async Task LockOut(Tm1Client tm1)
    {
        // @start region = lock-out
        // Stop the user logging in, then end the sessions they already have
        await tm1.Users.UpdateAsync("alice", UserPatch.Builder().Enabled(false).Build());
        await tm1.Users.DisconnectAsync("alice");
        // @end
    }
}

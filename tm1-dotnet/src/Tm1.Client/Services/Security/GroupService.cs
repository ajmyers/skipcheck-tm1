using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Security groups: <c>/Groups</c> and their users. A user's membership is set from the user's side, with <c>UserService#setGroups</c>.</summary>
public sealed class GroupService : Tm1Service
{
    public GroupService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Users: <c>/Users</c>, the session's own user, their groups, and disconnecting them.</summary>
public sealed class UserService : Tm1Service
{
    public UserService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

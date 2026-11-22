using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Server sessions: every session logged in to the server (<c>/Sessions</c>), this one's (<c>ActiveSession</c>), their users and threads, and closing another session.</summary>
public sealed class SessionService : Tm1Service
{
    public SessionService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

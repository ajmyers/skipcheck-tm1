using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The server: what it is (<c>Server</c>), how it's configured, and its loggers.</summary>
public sealed class ServerService : Tm1Service
{
    public ServerService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

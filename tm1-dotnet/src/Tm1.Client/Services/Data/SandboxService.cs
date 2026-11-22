using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Sandboxes: private copies of the data a user writes to before publishing. They belong to the user the session is for.</summary>
public sealed class SandboxService : Tm1Service
{
    public SandboxService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

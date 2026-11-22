using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Change sets: groups of cell writes that can be undone together.</summary>
public sealed class ChangeSetService : Tm1Service
{
    public ChangeSetService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

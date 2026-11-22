using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Chores: scheduled runs of processes, as ordered tasks. <c>/Chores</c>, their tasks, and running them now.</summary>
public sealed class ChoreService : Tm1Service
{
    public ChoreService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

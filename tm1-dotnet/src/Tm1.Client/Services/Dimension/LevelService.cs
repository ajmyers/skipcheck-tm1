using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Levels: <c>/Dimensions('d')/Hierarchies('h')/Levels</c>, keyed by number, and their members. Read only: TM1 derives levels from the hierarchy's structure.</summary>
public sealed class LevelService : Tm1Service
{
    public LevelService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

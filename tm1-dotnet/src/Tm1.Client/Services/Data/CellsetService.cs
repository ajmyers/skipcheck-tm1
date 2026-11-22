using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Cellsets: the server-side results of an MDX query or a view, with their axes and cells.</summary>
public sealed class CellsetService : Tm1Service
{
    public CellsetService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

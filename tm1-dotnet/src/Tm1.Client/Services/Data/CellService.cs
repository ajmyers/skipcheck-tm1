using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Cell writes to a cube, by coordinates. Cells are given as <c>Tuples#of</c> maps of dimension to element; reading is through cellsets (<c>CellsetService</c>), and writing into one by ordinal is there too.</summary>
public sealed class CellService : Tm1Service
{
    public CellService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

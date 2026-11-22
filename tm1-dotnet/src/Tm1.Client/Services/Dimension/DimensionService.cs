using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Dimensions: <c>/Dimensions</c> and the operations bound to a dimension.</summary>
public sealed class DimensionService : Tm1Service
{
    public DimensionService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

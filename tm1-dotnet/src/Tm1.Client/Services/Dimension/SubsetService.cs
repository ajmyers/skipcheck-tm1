using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Subsets: a hierarchy's public, private and session subsets (<c>SubsetScope</c>), their elements, and the operations bound to a subset or creating a session subset.</summary>
public sealed class SubsetService : Tm1Service
{
    public SubsetService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

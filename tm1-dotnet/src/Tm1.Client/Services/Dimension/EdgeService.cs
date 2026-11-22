using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Edges: <c>/Dimensions('d')/Hierarchies('h')/Edges</c>, the parent-child links of a hierarchy, each keyed by its parent's and its component's names.</summary>
public sealed class EdgeService : Tm1Service
{
    public EdgeService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

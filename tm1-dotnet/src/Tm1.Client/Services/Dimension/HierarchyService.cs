using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Hierarchies: <c>/Dimensions('d')/Hierarchies</c>, a dimension's default hierarchy, and the operations bound to a hierarchy. Elements, edges, members, levels and subsets have services of their own.</summary>
public sealed class HierarchyService : Tm1Service
{
    public HierarchyService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

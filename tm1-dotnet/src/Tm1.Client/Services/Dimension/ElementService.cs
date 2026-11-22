using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Elements: <c>/Dimensions('d')/Hierarchies('h')/Elements</c>, an element's parents, components and edges, and the operations bound to an element.</summary>
public sealed class ElementService : Tm1Service
{
    public ElementService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

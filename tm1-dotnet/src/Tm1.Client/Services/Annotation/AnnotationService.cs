using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Annotations: comments on cube cells, as Planning Analytics Workspace makes them, and the application context facets that tie them to cubes.</summary>
public sealed class AnnotationService : Tm1Service
{
    public AnnotationService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

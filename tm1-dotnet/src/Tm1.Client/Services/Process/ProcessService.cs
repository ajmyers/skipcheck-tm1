using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Processes (TurboIntegrator): <c>/Processes</c>, compiling and running them, and their error logs.</summary>
public sealed class ProcessService : Tm1Service
{
    public ProcessService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

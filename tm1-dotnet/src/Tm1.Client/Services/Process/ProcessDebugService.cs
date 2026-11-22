using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Process debugging: a run of a process that stops at breakpoints and steps line by line, held on the server as a <c>ProcessDebugContext</c> until deleted.</summary>
public sealed class ProcessDebugService : Tm1Service
{
    public ProcessDebugService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Server threads: what the server is doing right now (<c>/Threads</c>), and cancelling an operation, which is how to stop a running process: cancelling a client's request doesn't.</summary>
public sealed class ThreadService : Tm1Service
{
    public ThreadService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

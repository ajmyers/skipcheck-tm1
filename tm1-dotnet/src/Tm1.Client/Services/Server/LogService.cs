using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The server's logs: the message log, the transaction log (cell changes), the audit log (changes to objects and security), and the error logs processes write.</summary>
public sealed class LogService : Tm1Service
{
    public LogService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

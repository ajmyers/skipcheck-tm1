using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>TM1 Git (schema 11.5.0): ties the model to a Git repository, pushes its objects there as JSON files (<c>dimensions/Region.json</c>, …), and pulls them back, on this server or another.</summary>
public sealed class GitService : Tm1Service
{
    public GitService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

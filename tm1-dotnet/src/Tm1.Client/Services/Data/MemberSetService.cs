using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Member sets: the results of MDX set expressions, as a single axis of tuples, kept on the server in <c>/MemberSets</c> and keyed by an ordinal TM1 assigns (1, 2, … in the session).</summary>
public sealed class MemberSetService : Tm1Service
{
    public MemberSetService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

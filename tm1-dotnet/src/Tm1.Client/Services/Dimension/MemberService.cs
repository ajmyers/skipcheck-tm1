using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Members: <c>/Dimensions('d')/Hierarchies('h')/Members</c>, the MDX view of a hierarchy's elements, and a member's parent, children, element, level and hierarchy. Read only: members change through their elements.</summary>
public sealed class MemberService : Tm1Service
{
    public MemberService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Attribute definitions: which attributes a kind of object has, and their types, for each <c>AttributeSet</c>. Creating one adds it to every object of that kind; the values are cells of the matching <code>}…Attributes</code> control cube.</summary>
public sealed class AttributeService : Tm1Service
{
    public AttributeService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

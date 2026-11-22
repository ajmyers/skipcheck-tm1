using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Translated attribute values: an <c>AttributeOwner</c>'s <c>LocalizedAttributes</c>, one per locale, keyed by locale ID (<c>fr</c>, <c>de-CH</c>). The values are in <c>Attributes</c>: <c>Caption</c>, and every other attribute in its <c>dynamicProperties</c> (set with <c>AttributesPatch.Builder#property</c>).</summary>
public sealed class LocalizedAttributeService : Tm1Service
{
    public LocalizedAttributeService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

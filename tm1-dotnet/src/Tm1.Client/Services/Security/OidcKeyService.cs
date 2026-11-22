using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The OpenID Connect signing keys TM1 has cached, for servers that check OIDC tokens themselves. Removing a key makes TM1 fetch it from the provider again, as after the provider rotates its keys. On a server that checks no tokens, the cache is empty.</summary>
public sealed class OidcKeyService : Tm1Service
{
    public OidcKeyService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Encryption at rest (schema 11.8.35, except where a method says): the model's files encrypted with a data encryption key, itself protected by a key from a key management service. The provider settings are a <c>KeyProtectSettingsPatch</c> (IBM Key Protect), <c>VaultSettingsPatch</c> (HashiCorp Vault) or <c>LocalKeyFileProviderSettingsPatch</c> (a key file made with the local GSKit).</summary>
public sealed class EncryptionService : Tm1Service
{
    public EncryptionService(Tm1Session session)
        : base(session)
    {
    }

    // Not ported yet: see JavaParityTests
}

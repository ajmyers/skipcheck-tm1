using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;

namespace Examples;

/// <summary>Examples for <c>EncryptionService</c>, shown in the guide.</summary>
public static class EncryptionServiceExamples
{
    public static async Task Configure(Tm1Client tm1)
    {
        // @start region = configure
        // Check the settings first: the report says what's wrong, without secrets
        var keyProtect = KeyProtectSettingsPatch
            .Builder()
            .ServiceEndpoint("https://us-south.kms.cloud.ibm.com")
            .InstanceID("8f2c1d6e-0000-4000-8000-000000000000")
            .AuthRef("/run/secrets/key-protect-api-key")
            .KeyRef("planning-root-key")
            .Build();
        var report = await tm1.Encryption.ValidateAsync(keyProtect);
        if (report.IsValid != true)
        {
            throw new InvalidOperationException("Can't encrypt: " + string.Join("; ", report.Issues ?? []));
        }
        await tm1.Encryption.ConfigureAsync(keyProtect);
        // @end
    }

    public static async Task Rotate(Tm1Client tm1)
    {
        // @start region = rotate
        // A new data encryption key; TM1 re-encrypts the whole model with it
        await tm1.Encryption.RotateDataEncryptionKeyAsync();
        // @end
    }
}

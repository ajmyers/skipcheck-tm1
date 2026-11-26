// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>EncryptionService against a real TM1: a port of the Java client's EncryptionServiceTm1Test.
/// The image has no GSKit command line, so the local key file provider can't make a key, and nothing
/// here gets encrypted: the test pins how TM1 says so. (Java starts a TM1 of its own for this; nothing
/// here changes the server, so it shares the one the other live tests use.)</summary>
public class EncryptionServiceLiveTests
{
    private static readonly LocalKeyFileProviderSettingsPatch Local = LocalKeyFileProviderSettingsPatch.Builder().KeyRef("/tmp/tm1-client.key").Build();

    [Tm1Fact]
    public async Task ValidatesSettingsWithoutChangingAnything()
    {
        await using var tm1 = Live.Client();
        if (!await ServerAssumptions.SupportsAsync(tm1, Tm1Operations.ValidateEncryptionAtRestSettings))
        {
            return; // the server doesn't define it (xUnit 2 can't skip a test once it runs)
        }
        var report = await tm1.Encryption.ValidateAsync(Local);
        if (await MakesItsOwnKeyAsync(tm1))
        {
            Assert.True(report.IsValid);
            Assert.Empty(report.Issues ?? []);
        }
        else
        {
            Assert.False(report.IsValid);
            Assert.Contains("Could not generate a new key using the local GSKit installation", Assert.Single(report.Issues!));
        }
    }

    [Tm1Fact]
    public async Task RefusesChangesWithBareCodeNames()
    {
        await using var tm1 = Live.Client();
        // From 11.8.040 the server can make the key, so configuring would really convert it and
        // stop it: not tried there
        if (!await MakesItsOwnKeyAsync(tm1))
        {
            await Refused(tm1, Tm1Operations.ConfigureEncryptionAtRest, () => tm1.Encryption.ConfigureAsync(Local), "DataModelConversion");
        }
        await Refused(tm1, Tm1Operations.DisableEncryptionAtRest, () => tm1.Encryption.DisableAsync(), "DataModelConversion");
        await Refused(tm1, Tm1Operations.RotateDataEncryptionKey, () => tm1.Encryption.RotateDataEncryptionKeyAsync(), "DataEncryptionKeyRotationFailed");
        await Refused(tm1, Tm1Operations.RotateDataModelKey, () => tm1.Encryption.RotateDataModelKeyAsync(), "RotateDataModelKey");
        await Refused(
            tm1,
            Tm1Operations.EncryptDataFile,
            () => tm1.Encryption.EncryptFileAsync("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.Object),
            "DataFileConversion"
        );
        await Refused(
            tm1,
            Tm1Operations.DecryptDataFile,
            () => tm1.Encryption.DecryptFileAsync("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.Object),
            "DataFileConversion"
        );
    }

    /// <summary>TM1's refusal, with its bare code name in the message, where the server defines the
    /// operation; the client's own refusal, sending nothing, where it doesn't.</summary>
    private static async Task Refused(Tm1Client tm1, Tm1Operation operation, Func<Task> call, string code)
    {
        if (await ServerAssumptions.SupportsAsync(tm1, operation))
        {
            var e = await Assert.ThrowsAsync<Tm1BadRequestException>(call);
            Assert.Contains(code, e.Message, StringComparison.Ordinal);
        }
        else
        {
            await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(call);
        }
    }

    /// <summary>From TM1 11.8.040 the server makes the local key file itself, and the settings
    /// check as valid; before, the image's missing GSKit command line stops it.</summary>
    private static async Task<bool> MakesItsOwnKeyAsync(Tm1Client tm1) =>
        (await ServerAssumptions.ProductVersionAsync(tm1)).CompareTo(Skipcheck.Tm1.Client.Version.Tm1Version.Parse("11.8.04000")) >= 0;
}

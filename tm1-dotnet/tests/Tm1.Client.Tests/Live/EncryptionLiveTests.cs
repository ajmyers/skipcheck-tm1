using System;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>EncryptionService against a real TM1: a port of the Java client's EncryptionServiceTm1Test.
/// The image has no GSKit command line, so the local key file provider can't make a key, and nothing
/// here gets encrypted: the test pins how TM1 says so. (Java starts a TM1 of its own for this; nothing
/// here changes the server, so it shares the one the other live tests use.)</summary>
public class EncryptionLiveTests
{
    private static readonly LocalKeyFileProviderSettingsPatch Local = LocalKeyFileProviderSettingsPatch.Builder().KeyRef("/tmp/tm1-client.key").Build();

    [Tm1Fact]
    public async Task ValidatesSettingsWithoutChangingAnything()
    {
        await using var tm1 = Live.Client();
        var report = await tm1.Encryption.ValidateAsync(Local);
        Assert.False(report.IsValid);
        Assert.Contains("Could not generate a new key using the local GSKit installation", Assert.Single(report.Issues!));
    }

    [Tm1Fact]
    public async Task RefusesChangesWithBareCodeNames()
    {
        await using var tm1 = Live.Client();
        await Refused(() => tm1.Encryption.ConfigureAsync(Local), "DataModelConversion");
        await Refused(() => tm1.Encryption.DisableAsync(), "DataModelConversion");
        await Refused(() => tm1.Encryption.RotateDataEncryptionKeyAsync(), "DataEncryptionKeyRotationFailed");
        await Refused(() => tm1.Encryption.RotateDataModelKeyAsync(), "RotateDataModelKey");
        await Refused(() => tm1.Encryption.EncryptFileAsync("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.Object), "DataFileConversion");
        await Refused(() => tm1.Encryption.DecryptFileAsync("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.Object), "DataFileConversion");
    }

    private static async Task Refused(Func<Task> call, string code)
    {
        var e = await Assert.ThrowsAsync<Tm1BadRequestException>(call);
        Assert.Contains(code, e.Message, StringComparison.Ordinal);
    }
}

using System;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>EncryptionService against a fake TM1: each method sends what the Java client's does.</summary>
public class EncryptionServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    private string Sent => $"{_tm1.Last.Method} {_tm1.Last.Target}";

    [Fact]
    public async Task ValidatesSettingsGivenAsAParameterAlias()
    {
        _tm1.Respond = _ => new { IsValid = false, Issues = new[] { "Could not generate a new key using the local GSKit installation" } };
        var settings = LocalKeyFileProviderSettingsPatch.Builder().KeyRef("/tmp/a.key").Build();
        var report = await _tm1.Client().Encryption.ValidateAsync(settings);
        Assert.False(report.IsValid);
        Assert.Single(report.Issues!);
        Assert.Equal("GET", _tm1.Last.Method);
        Assert.StartsWith("ValidateEncryptionAtRestSettings(Settings=@settings)?@settings=", _tm1.Last.Target);
        var json = Uri.UnescapeDataString(_tm1.Last.Target[_tm1.Last.Target.IndexOf('=', _tm1.Last.Target.IndexOf('?'))..][1..]);
        Assert.Contains("\"KeyRef\":\"/tmp/a.key\"", json);
        Assert.Contains("LocalKeyFileProviderSettings", json);
    }

    [Fact]
    public async Task CallsTheActions()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => null;
        await tm1.Encryption.ConfigureAsync(LocalKeyFileProviderSettingsPatch.Builder().KeyRef("/tmp/a.key").Build());
        Assert.Equal("POST ConfigureEncryptionAtRest", Sent);
        Assert.Equal("/tmp/a.key", _tm1.Last.Json.GetProperty("Settings").GetProperty("KeyRef").GetString());
        foreach (
            var (call, name) in new (Func<Task>, string)[]
            {
                (() => tm1.Encryption.DisableAsync(), "DisableEncryptionAtRest"),
                (() => tm1.Encryption.RotateDataEncryptionKeyAsync(), "RotateDataEncryptionKey"),
                (() => tm1.Encryption.RotateDataModelKeyAsync(), "RotateDataModelKey"),
            }
        )
        {
            await call();
            Assert.Equal(($"POST {name}", "{}"), (Sent, _tm1.Last.Text));
        }

        await tm1.Encryption.EncryptFileAsync("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.Object);
        Assert.Equal(
            ("POST EncryptDataFile", """{"SourcePath":"tm1s.cfg","DestinationPath":"/tmp/tm1s.cfg.enc","FileType":"Object"}"""),
            (Sent, _tm1.Last.Text)
        );
        await tm1.Encryption.DecryptFileAsync("a.log", "/tmp/a.log", EncryptionFileType.TransactionLog);
        Assert.Equal(("POST DecryptDataFile", """{"SourcePath":"a.log","DestinationPath":"/tmp/a.log","FileType":"TransactionLog"}"""), (Sent, _tm1.Last.Text));

#pragma warning disable CS0618
        await tm1.Encryption.EncryptModelAsync(TimeSpan.FromSeconds(30));
        Assert.Equal(("POST EncryptDataModel", """{"TimeUntilShutdown":"PT30S"}"""), (Sent, _tm1.Last.Text));
        await tm1.Encryption.DecryptModelAsync(null);
        Assert.Equal(("POST DecryptDataModel", "{}"), (Sent, _tm1.Last.Text));
#pragma warning restore CS0618
    }
}

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>ServerService against a real TM1: a port of the Java client's ServerServiceTm1Test.</summary>
public class ServerServiceLiveTests
{
    [Tm1Fact]
    public async Task ReadsTheServerAndItsConfigurations()
    {
        await using var tm1 = Live.Client();
        var read = await tm1.Server.ServerAsync();
        Assert.False(string.IsNullOrEmpty(read.ProductVersion));
        Assert.Null(read.ETag); // none for the Server
        var fixedSettings = await tm1.Server.StaticConfigurationAsync();
        var active = await tm1.Server.ActiveConfigurationAsync();
        Assert.Equal(read.Name, fixedSettings.ServerName);
        Assert.Equal(read.Name, active.ServerName);
#pragma warning disable CS0618
        Assert.Equal(read.Name, (await tm1.Server.ConfigurationAsync()).ServerName);
#pragma warning restore CS0618
        await tm1.Server.UpdateStaticConfigurationAsync(ServerSettingsPatch.Builder().ServerName(fixedSettings.ServerName).Build());
        // The active configuration is read only
        await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
            tm1.Session.ExecuteAsync(Tm1Request.Patch(ODataPath.Of("ActiveConfiguration"), ServerSettingsPatch.Builder().ServerName(active.ServerName).Build()))
        );
    }

    [Tm1Fact]
    public async Task ReadsAndSetsLoggerLevels()
    {
        await using var tm1 = Live.Client();
        var loggers = await tm1.Server.LoggersAsync().ToListAsync();
        Assert.NotEmpty(loggers);
        // Not the first, TM1's root logger: at DEBUG it floods the message log of the server every
        // test shares, with lines alike to the millisecond
        var first = loggers.First(l => l.Name == "TM1.Blob");
        Assert.Equal(first.Level, (await tm1.Server.LoggerAsync(first.Name!)).Level);
        var other = first.Level == LogLevel.Debug ? LogLevel.Info : LogLevel.Debug;
        await tm1.Server.SetLoggerLevelAsync(first.Name!, other);
        try
        {
            Assert.Equal(other, (await tm1.Server.LoggerAsync(first.Name!)).Level);
        }
        finally
        {
            await tm1.Server.SetLoggerLevelAsync(first.Name!, first.Level!.Value);
        }
    }
}

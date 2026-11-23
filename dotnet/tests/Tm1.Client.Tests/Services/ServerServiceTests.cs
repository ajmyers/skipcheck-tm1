using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ServerService against a fake TM1: each method sends what the Java client's does.</summary>
public class ServerServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ReadsTheServerAndItsConfigurations()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => new { Name = "Planning", ProductVersion = "11.8.03500.6" };
        var server = await tm1.Server.ServerAsync(ODataQuery.None().Select(Server.Fields.Name));
        Assert.Equal("Planning", server.Name);
        Assert.Equal("GET Server?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new { ServerName = "Planning" };
        Assert.Equal("Planning", (await tm1.Server.StaticConfigurationAsync()).ServerName);
        Assert.Equal("GET StaticConfiguration", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        Assert.Equal("Planning", (await tm1.Server.ActiveConfigurationAsync(ODataQuery.None().Select("ServerName"))).ServerName);
        Assert.Equal("GET ActiveConfiguration?$select=ServerName", $"{_tm1.Last.Method} {_tm1.Last.Target}");
#pragma warning disable CS0618
        Assert.Equal("Planning", (await tm1.Server.ConfigurationAsync()).ServerName);
#pragma warning restore CS0618
        Assert.Equal("GET Configuration", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => null;
        await tm1.Server.UpdateStaticConfigurationAsync(ServerSettingsPatch.Builder().ServerName("Planning").Build());
        Assert.Equal(("PATCH", "StaticConfiguration", """{"ServerName":"Planning"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }

    [Fact]
    public async Task ReadsAndSetsLoggers()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "TM1.Server", Level = "Info" });
        var loggers = await tm1.Server.LoggersAsync().ToListAsync();
        Assert.Equal(LogLevel.Info, Assert.Single(loggers).Level);
        Assert.Equal("GET Loggers", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new { Name = "TM1.Server", Level = "Debug" };
        Assert.Equal(LogLevel.Debug, (await tm1.Server.LoggerAsync("TM1.Server", ODataQuery.None().Select("Level"))).Level);
        Assert.Equal("GET Loggers('TM1.Server')?$select=Level", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => null;
        await tm1.Server.SetLoggerLevelAsync("TM1.Server", LogLevel.Debug);
        Assert.Equal(("PATCH", "Loggers('TM1.Server')", """{"Level":"Debug"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }
}

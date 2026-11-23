using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The client against a real TM1: the server's <c>$metadata</c> against the spec, and cubes.</summary>
public class ServerLiveTests
{
    [Tm1Fact]
    public async Task TheServerHasWhatTheSpecSays()
    {
        await using var tm1 = Live.Client();
        var caps = await tm1.Session.CapabilitiesAsync();
        if (caps.SchemaVersion == "11.8.35")
        {
            Assert.True(caps.Endpoints.SetEquals(CoverageGateTests.Endpoints()));
        }
    }

    [Tm1Fact]
    public async Task ListsTheModelCubes()
    {
        await using var tm1 = Live.Client();
        var cubes = await tm1.Cubes.ModelCubesAsync(OData.ODataQuery.None().Select(Cube.Fields.Name)).ToListAsync();
        Assert.DoesNotContain(cubes, c => c.Name!.StartsWith('}'));
    }
}

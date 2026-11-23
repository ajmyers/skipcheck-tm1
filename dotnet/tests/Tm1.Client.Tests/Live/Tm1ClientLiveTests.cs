// SPDX-License-Identifier: Apache-2.0

using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1ClientTm1Test</c>.</summary>
public class Tm1ClientLiveTests
{
    [Tm1Fact]
    public async Task ConnectsReadsTheServerAndCloses()
    {
        var client = Live.Client();
        var server = await (await client.ConnectAsync()).Session.ExecuteAsync(
            Tm1Request.Get<JsonElement>(ODataPath.Of("Server"), ODataQuery.None().Select("Name", "ProductVersion"))
        );
        var version = await client.Session.VersionAsync();
        await client.CloseAsync();
        Assert.Equal(version.ToString(), server.GetProperty("ProductVersion").GetString());
        Assert.True(client.Session.IsClosed());
        Assert.Equal("x", client.WithSandbox("x").Session.Sandbox());
    }
}

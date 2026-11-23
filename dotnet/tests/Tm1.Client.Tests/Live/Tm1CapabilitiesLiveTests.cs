using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using System.Xml.Linq;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1CapabilitiesTm1Test</c>.</summary>
public class Tm1CapabilitiesLiveTests
{
    [Tm1Fact]
    public async Task ReadsTheVersionAndTheCapabilitiesTheSnapshotDescribes()
    {
        var session = RawTm1.Sessions().Build();
        try
        {
            var reported = (await session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Server"), ODataQuery.None().Select("ProductVersion"))))
                .GetProperty("ProductVersion")
                .GetString()!;
            Assert.Equal(Tm1Version.Parse(reported), await session.VersionAsync());
            var capabilities = await session.CapabilitiesAsync();
            var snapshot = XDocument.Load(Repo.Snapshot);
            var snapshotVersion = Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot)).SchemaVersion;
            Assert.Equal(snapshotVersion, capabilities.SchemaVersion);
            var container = snapshot.Descendants().Single(e => e.Name.LocalName == "EntityContainer");
            foreach (var set in container.Elements().Where(e => e.Name.LocalName == "EntitySet"))
            {
                Assert.True(capabilities.Supports(Tm1Capability.EntitySet((string)set.Attribute("Name")!)), (string?)set.Attribute("Name"));
            }
            foreach (var singleton in container.Elements().Where(e => e.Name.LocalName == "Singleton"))
            {
                Assert.True(capabilities.Supports(Tm1Capability.Singleton((string)singleton.Attribute("Name")!)), (string?)singleton.Attribute("Name"));
            }
            foreach (var import in container.Elements().Where(e => e.Name.LocalName == "ActionImport"))
            {
                Assert.True(capabilities.Supports(Tm1Capability.UnboundOperation((string)import.Attribute("Name")!)), (string?)import.Attribute("Name"));
            }
            Assert.True(capabilities.Supports(Tm1Capability.BoundOperation("tm1.Cube", "tm1.Reserve")));
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task RefusesARequestTheServerDoesntDefine()
    {
        var session = RawTm1.Sessions().Build();
        try
        {
            var version = await session.VersionAsync();
            var missing = Tm1Request.Get<JsonElement>(ODataPath.Of("Bananas")).Requires(Tm1Capability.EntitySet("Bananas").Since("99.0"));
            var e = await Assert.ThrowsAsync<Tm1UnsupportedOperationException>(() => session.ExecuteAsync(missing));
            Assert.Contains("added in schema 99.0", e.Message);
            Assert.Contains("reports schema " + Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot)).SchemaVersion, e.Message);
            Assert.Contains("product version " + version, e.Message);
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact]
    public async Task SharesOneFetchBetweenAPoolsSessions()
    {
        var (_, user, password) = Live.Target();
        await using var pool = Tm1SessionPool.Builder(RawTm1.Sessions()).Build();
        var first = await pool.Session().CapabilitiesAsync();
        var second = await pool.Session(Tm1Identity.WithCredentials("again", Tm1Authenticator.Basic(user, password))).CapabilitiesAsync();
        Assert.Same(first, second);
    }
}

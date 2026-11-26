// SPDX-License-Identifier: Apache-2.0

using System.IO;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>What a live test needs of the server it runs against, as Java's ServerAssumptions, so
/// the suite runs against every supported TM1. The server's own <c>$metadata</c> decides, as the
/// client decides. xUnit 2 can't skip a test once it runs, so a test whose server lacks what it
/// needs returns there and passes.</summary>
internal static class ServerAssumptions
{
    /// <summary>Whether the server defines the operation.</summary>
    public static async Task<bool> SupportsAsync(Tm1Client tm1, Tm1Operation operation) =>
        (await tm1.Session.CapabilitiesAsync()).Supports(operation.Capability());

    /// <summary>Whether the server's operation has the parameter.</summary>
    public static async Task<bool> SupportsParameterAsync(Tm1Client tm1, Tm1Operation operation, string parameter) =>
        (await tm1.Session.CapabilitiesAsync()).SupportsParameter(operation.Capability(), parameter);

    /// <summary>The server's product version.</summary>
    public static async Task<Tm1Version> ProductVersionAsync(Tm1Client tm1) => (await tm1.Session.CapabilitiesAsync()).ProductVersion!;

    /// <summary>The committed snapshot of the server's own schema version: every supported version
    /// has one.</summary>
    public static string OwnSnapshot(Tm1Capabilities capabilities)
    {
        var path = Path.Combine(Repo.Root, "java", "tm1-client", "src", "model", "metadata", $"tm1-schema-{capabilities.SchemaVersion}.xml");
        Assert.True(File.Exists(path), "No metadata snapshot for schema " + capabilities.SchemaVersion);
        return File.ReadAllText(path);
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.IO;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>What a live test needs of the server it runs against, as Java's ServerAssumptions, so
/// the suite runs against every supported TM1. The server's own <c>$metadata</c> decides, as the
/// client decides. xUnit 2 can't skip a test once it runs, so a test whose server lacks what it
/// needs returns there and passes.</summary>
internal static class ServerAssumptions
{
    /// <summary>From this TM1 the first Git pull after the remote moves plans the commit TM1 had,
    /// and the next the new one; before, the first plans the new one. Tried on every Planning
    /// Analytics 2.1 release: 11.8.03200.3 (2.1.13 to 2.1.16) plans the new one, 11.8.03400.2
    /// (2.1.17) the stale, and no build between them was released.</summary>
    public static readonly Tm1Version StaleFirstPullFrom = Tm1Version.Parse("11.8.03400");

    /// <summary>From this TM1 the server makes a local key file itself, so the image's settings
    /// check as valid. Tried at 11.8.035 (invalid) and 11.8.040 (valid); a build between falls on
    /// the older side unverified.</summary>
    public static readonly Tm1Version MakesOwnKeyFrom = Tm1Version.Parse("11.8.04000");

    /// <summary>Whether the server defines the operation.</summary>
    public static async Task<bool> SupportsAsync(Tm1Client tm1, Tm1Operation operation) =>
        (await tm1.Session.CapabilitiesAsync()).Supports(operation.Capability());

    /// <summary>Whether the server's operation has the parameter.</summary>
    public static async Task<bool> SupportsParameterAsync(Tm1Client tm1, Tm1Operation operation, string parameter) =>
        (await tm1.Session.CapabilitiesAsync()).SupportsParameter(operation.Capability(), parameter);

    /// <summary>The server's product version.</summary>
    public static async Task<Tm1Version> ProductVersionAsync(Tm1Client tm1) => (await tm1.Session.CapabilitiesAsync()).ProductVersion!;

    /// <summary>Whether the server's product version is at least the version.</summary>
    public static async Task<bool> IsAtLeastAsync(Tm1Client tm1, Tm1Version version) => (await ProductVersionAsync(tm1)).IsAtLeast(version);

    /// <summary>The committed snapshot of the server's own schema version, or null: its first
    /// document, whose elements a later document of the schema has too (Java's
    /// MetadataSnapshotTest holds them to it). Every supported version has one; a server of a
    /// schema without one (a newer TM1) has the test return, as Java's MetadataDriftTm1Test
    /// passes it by.</summary>
    public static string? OwnSnapshot(Tm1Capabilities capabilities)
    {
        var path = Path.Combine(Repo.Root, "java", "tm1-client", "src", "model", "metadata", $"tm1-schema-{capabilities.SchemaVersion}.xml");
        return File.Exists(path) ? File.ReadAllText(path) : null;
    }

    /// <summary>The schema version a <c>$metadata</c> document's <c>Core.SchemaVersion</c>
    /// annotation gives, read from its text rather than by the client's parser.</summary>
    public static string SchemaVersionIn(string metadata)
    {
        var found = Regex.Match(metadata, "Term=\"Core\\.SchemaVersion\">\\s*<String>([^<]+)</String>");
        Assert.True(found.Success, "$metadata has no Core.SchemaVersion annotation");
        return found.Groups[1].Value.Trim();
    }
}

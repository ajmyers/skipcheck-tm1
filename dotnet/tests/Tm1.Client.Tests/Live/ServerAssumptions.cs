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
    /// and the next the new one; before, the first plans the new one. Tried at 11.8.032 (the new)
    /// and 11.8.035 (the stale): a build between falls on the older side unverified, so a failure
    /// there is this bound to move.</summary>
    public static readonly Tm1Version StaleFirstPullFrom = Tm1Version.Parse("11.8.03500");

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

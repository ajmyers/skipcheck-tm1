using System;
using System.IO;
using System.Linq;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>Where a <see cref="PawContainer"/> gets Planning Analytics Workspace from: IBM's PAW
/// Local zip, <c>ipa_workspace_local_&lt;release&gt;.&lt;build&gt;.zip</c>, as the Java
/// <c>PawSource</c>.</summary>
public sealed record PawSource
{
    /// <summary>The setting naming the zip.</summary>
    public const string ZipSetting = "PAW_ZIP";

    /// <summary>The setting naming a folder holding zips: the newest is used.</summary>
    public const string BundleDir = "PAW_BUNDLE_DIR";

    private const string Prefix = "ipa_workspace_local_";

    /// <summary>A PAW Local zip.</summary>
    /// <exception cref="ArgumentException">It isn't a file.</exception>
    public PawSource(string zip)
    {
        ArgumentNullException.ThrowIfNull(zip);
        Zip = Settings.Absolute(zip);
        if (!File.Exists(Zip))
        {
            throw new ArgumentException(
                $"Not a file: {Zip}. Point {ZipSetting} at IBM's ipa_workspace_local_<release>.<build>.zip, or {BundleDir} at its folder.",
                nameof(zip)
            );
        }
    }

    /// <summary>The zip, absolute.</summary>
    public string Zip { get; }

    /// <summary>Where PAW comes from, for logs.</summary>
    public string Describe() => "PAW Local zip " + Zip;

    /// <summary>The zip set with <c>PAW_ZIP</c>, or else the newest in <c>PAW_BUNDLE_DIR</c>; null
    /// when neither is set.</summary>
    /// <exception cref="ArgumentException">A setting is wrong.</exception>
    public static PawSource? Discover() => Discover(Settings.Get);

    /// <summary>As <see cref="Discover()"/>, looking settings up with <paramref name="lookup"/>.</summary>
    public static PawSource? Discover(Func<string, string?> lookup)
    {
        ArgumentNullException.ThrowIfNull(lookup);
        if (Settings.Text(lookup(ZipSetting)) is { } zip)
        {
            return new PawSource(zip);
        }
        if (Settings.Text(lookup(BundleDir)) is { } dir)
        {
            return new PawSource(NewestZip(dir));
        }
        return null;
    }

    private static string NewestZip(string dir)
    {
        if (!Directory.Exists(dir))
        {
            throw new ArgumentException($"{BundleDir} is not a folder: {dir}");
        }
        return new DirectoryInfo(dir)
            .EnumerateFiles()
            .Where(f => f.Name.StartsWith(Prefix, StringComparison.Ordinal) && f.Name.EndsWith(".zip", StringComparison.Ordinal))
            .OrderByDescending(f => f.LastWriteTimeUtc)
            .FirstOrDefault()
            ?.FullName
            ?? throw new ArgumentException($"{BundleDir} has no {Prefix}*.zip: {Settings.Absolute(dir)}");
    }
}

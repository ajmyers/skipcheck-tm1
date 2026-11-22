using System;
using System.IO;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>Where a <see cref="CognosContainer"/> gets Cognos Analytics from: an image with it built
/// in (<see cref="PrivateImage"/>), a volume holding an install (<see cref="InstallVolume"/>), a
/// folder holding IBM's <c>casrv-*-linuxi38664h.zip</c> (<see cref="Zip"/>), or a download link
/// for it (<see cref="ZipUrl"/>). <see cref="Discover()"/> picks one from settings, as the Java
/// <c>CognosSource</c> does, and falls back to <see cref="Tm1Source.BundleDir"/> when that folder
/// holds the zip.</summary>
public abstract record CognosSource
{
    /// <summary>The setting naming an image with Cognos built in.</summary>
    public const string PrivateImageSetting = "COGNOS_PRIVATE_IMAGE";

    /// <summary>The setting naming a Docker volume holding an install.</summary>
    public const string InstallVolumeSetting = "COGNOS_INSTALL_VOLUME";

    /// <summary>The setting naming the folder holding IBM's zip.</summary>
    public const string ZipDir = "COGNOS_ZIP_DIR";

    /// <summary>The setting naming the zip in <see cref="ZipDir"/> (the newest otherwise).</summary>
    public const string ZipSetting = "COGNOS_ZIP";

    /// <summary>The setting holding a download link for the zip.</summary>
    public const string ZipUrlSetting = "COGNOS_ZIP_URL";

    /// <summary>The setting holding the zip's SHA-256.</summary>
    public const string ZipSha256 = "COGNOS_ZIP_SHA256";

    private protected CognosSource()
    {
    }

    /// <summary>Where Cognos comes from, for logs: never a URL's credentials or query string.</summary>
    public abstract string Describe();

    /// <summary>The first source set, from <see cref="AppContext"/> switches or environment
    /// variables; null when none is.</summary>
    /// <exception cref="ArgumentException">A source is set but wrong.</exception>
    public static CognosSource? Discover() => Discover(Settings.Get);

    /// <summary>The first source set, in this order: <c>COGNOS_PRIVATE_IMAGE</c>,
    /// <c>COGNOS_INSTALL_VOLUME</c>, <c>COGNOS_ZIP_DIR</c> (with <c>COGNOS_ZIP</c>),
    /// <c>COGNOS_ZIP_URL</c> (with <c>COGNOS_ZIP_SHA256</c>), then <c>TM1_BUNDLE_DIR</c> if it holds a
    /// <c>casrv-*-linuxi38664h.zip</c> (or <c>COGNOS_ZIP</c> is set).</summary>
    public static CognosSource? Discover(Func<string, string?> lookup)
    {
        ArgumentNullException.ThrowIfNull(lookup);
        var get = Settings.Trimmed(lookup);
        if (get(PrivateImageSetting) is { } image)
        {
            return new PrivateImage(image);
        }
        if (get(InstallVolumeSetting) is { } volume)
        {
            return new InstallVolume(volume);
        }
        if (get(ZipDir) is { } dir)
        {
            return new Zip(dir, get(ZipSetting));
        }
        if (get(ZipUrlSetting) is { } url)
        {
            return new ZipUrl(new Uri(url), get(ZipSha256));
        }
        if (get(Tm1Source.BundleDir) is { } tm1Dir && (get(ZipSetting) is not null || Settings.HasFile(tm1Dir, IsZip)))
        {
            return new Zip(tm1Dir, get(ZipSetting));
        }
        return null;
    }

    private static bool IsZip(string name) => name.StartsWith("casrv-", StringComparison.Ordinal) && name.EndsWith("-linuxi38664h.zip", StringComparison.Ordinal);

    /// <summary>An image with Cognos Analytics built in, used in place of <c>cognos-server</c>.</summary>
    public sealed record PrivateImage : CognosSource
    {
        /// <summary>A private image.</summary>
        public PrivateImage(string image)
        {
            Settings.RequireText(image, nameof(image));
            Image = image;
        }

        /// <summary>The image's name.</summary>
        public string Image { get; }

        /// <inheritdoc/>
        public override string Describe() => "private image " + Image;
    }

    /// <summary>A Docker volume holding a Cognos Analytics install.</summary>
    public sealed record InstallVolume : CognosSource
    {
        /// <summary>An install volume.</summary>
        public InstallVolume(string name)
        {
            Settings.RequireText(name, nameof(name));
            Name = name;
        }

        /// <summary>The volume's name.</summary>
        public string Name { get; }

        /// <inheritdoc/>
        public override string Describe() => "install volume " + Name;
    }

    /// <summary>A folder holding IBM's Cognos Analytics zip, which the image installs.</summary>
    public sealed record Zip : CognosSource
    {
        /// <summary>A zip folder, and the file in it (null for the newest).</summary>
        /// <exception cref="ArgumentException">The folder isn't there, or the file isn't in it.</exception>
        public Zip(string dir, string? file = null)
        {
            ArgumentNullException.ThrowIfNull(dir);
            Dir = Settings.Absolute(dir);
            if (!Directory.Exists(Dir))
            {
                throw new ArgumentException($"{ZipDir} is not a folder: {Dir}. Point it at the folder holding the zip; name the file itself with {ZipSetting}.", nameof(dir));
            }
            File = string.IsNullOrWhiteSpace(file) ? null : file;
            if (File is not null && !System.IO.File.Exists(Path.Combine(Dir, File)))
            {
                throw new ArgumentException($"{ZipSetting} names {File}, which isn't in {Dir}", nameof(file));
            }
        }

        /// <summary>The folder, absolute.</summary>
        public string Dir { get; }

        /// <summary>The zip's file name, or null for the newest in <see cref="Dir"/>.</summary>
        public string? File { get; }

        /// <inheritdoc/>
        public override string Describe() => "zip " + (File is not null ? Path.Combine(Dir, File) : Dir + " (newest)");
    }

    /// <summary>A download link for IBM's Cognos Analytics zip.</summary>
    public sealed record ZipUrl : CognosSource
    {
        /// <summary>A zip URL, and its SHA-256 (null not to check).</summary>
        public ZipUrl(Uri url, string? sha256 = null)
        {
            ArgumentNullException.ThrowIfNull(url);
            Url = url;
            Sha256 = string.IsNullOrWhiteSpace(sha256) ? null : sha256;
        }

        /// <summary>The link.</summary>
        public Uri Url { get; }

        /// <summary>The zip's SHA-256, or null.</summary>
        public string? Sha256 { get; }

        /// <inheritdoc/>
        public override string Describe() => "zip URL " + Settings.Safe(Url);
    }
}

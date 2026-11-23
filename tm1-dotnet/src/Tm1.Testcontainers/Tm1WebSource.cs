using System;
using System.IO;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>Where a <see cref="Tm1WebContainer"/> gets TM1 Web from, as <see cref="Tm1Source"/> for
/// TM1: an image with TM1 Web built in, an installed <c>tm1web</c> folder, a volume holding one, a
/// folder holding IBM's TM1 Web bundle, or a download link for it. <see cref="Discover()"/> picks
/// one from settings, as the Java <c>Tm1WebSource</c> does, and falls back to
/// <see cref="Tm1Source.BundleDir"/> when that folder holds a TM1 Web bundle.</summary>
public abstract record Tm1WebSource
{
    /// <summary>The setting naming an image with TM1 Web built in.</summary>
    public const string PrivateImageSetting = "TM1_WEB_PRIVATE_IMAGE";

    /// <summary>The setting naming an installed <c>tm1web</c> folder.</summary>
    public const string HomeDir = "TM1_WEB_HOME_DIR";

    /// <summary>The setting naming a Docker volume holding an install.</summary>
    public const string InstallVolumeSetting = "TM1_WEB_INSTALL_VOLUME";

    /// <summary>The setting naming the folder holding IBM's bundle.</summary>
    public const string BundleDir = "TM1_WEB_BUNDLE_DIR";

    /// <summary>The setting naming the bundle file in <see cref="BundleDir"/> (the newest otherwise).</summary>
    public const string BundleSetting = "TM1_WEB_BUNDLE";

    /// <summary>The setting holding a download link for the bundle.</summary>
    public const string BundleUrlSetting = "TM1_WEB_BUNDLE_URL";

    /// <summary>The setting holding the bundle's SHA-256, checked after the download.</summary>
    public const string BundleSha256 = "TM1_WEB_BUNDLE_SHA256";

    private protected Tm1WebSource() { }

    /// <summary>Where TM1 comes from, for logs: never a URL's credentials or query string.</summary>
    public abstract string Describe();

    /// <summary>The first source set, from <see cref="AppContext"/> switches or environment
    /// variables; null when none is.</summary>
    /// <exception cref="ArgumentException">A source is set but wrong (a folder that isn't there, say):
    /// a test should fail on that, not skip.</exception>
    public static Tm1WebSource? Discover() => Discover(Settings.Get);

    /// <summary>The first source set, looking settings up with <paramref name="lookup"/>, in this
    /// order: <c>TM1_WEB_PRIVATE_IMAGE</c>, <c>TM1_WEB_HOME_DIR</c>, <c>TM1_WEB_INSTALL_VOLUME</c>,
    /// <c>TM1_WEB_BUNDLE_DIR</c> (with <c>TM1_WEB_BUNDLE</c>), <c>TM1_WEB_BUNDLE_URL</c> (with
    /// <c>TM1_WEB_BUNDLE_SHA256</c>), then <c>TM1_BUNDLE_DIR</c> if it holds a <c>tm1web-*_bundle.tar.gz</c>
    /// or <c>tm1web-*-linuxi38664h.zip</c> (or <c>TM1_WEB_BUNDLE</c> is set). Blank settings count as unset.</summary>
    public static Tm1WebSource? Discover(Func<string, string?> lookup)
    {
        ArgumentNullException.ThrowIfNull(lookup);
        var get = Settings.Trimmed(lookup);
        if (get(PrivateImageSetting) is { } image)
        {
            return new PrivateImage(image);
        }
        if (get(HomeDir) is { } home)
        {
            return new InstallDir(home);
        }
        if (get(InstallVolumeSetting) is { } volume)
        {
            return new InstallVolume(volume);
        }
        if (get(BundleDir) is { } dir)
        {
            return new Bundle(dir, get(BundleSetting));
        }
        if (get(BundleUrlSetting) is { } url)
        {
            return new BundleUrl(new Uri(url), get(BundleSha256));
        }
        if (get(Tm1Source.BundleDir) is { } tm1Dir && (get(BundleSetting) is not null || Settings.HasFile(tm1Dir, IsBundle)))
        {
            return new Bundle(tm1Dir, get(BundleSetting));
        }
        return null;
    }

    private static bool IsBundle(string name) =>
        name.StartsWith("tm1web-", StringComparison.Ordinal)
        && (name.EndsWith("_bundle.tar.gz", StringComparison.Ordinal) || name.EndsWith("-linuxi38664h.zip", StringComparison.Ordinal));

    /// <summary>An image with TM1 Web built in, used in place of <c>tm1-server</c>.</summary>
    public sealed record PrivateImage : Tm1WebSource
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

    /// <summary>An installed <c>tm1web</c> folder, mounted read-only.</summary>
    public sealed record InstallDir : Tm1WebSource
    {
        /// <summary>An install folder.</summary>
        /// <exception cref="ArgumentException">It has no <c>wlp/bin/server</c>.</exception>
        public InstallDir(string dir)
        {
            ArgumentNullException.ThrowIfNull(dir);
            Dir = Settings.Absolute(dir);
            if (!File.Exists(Path.Combine(Dir, "wlp", "bin", "server")))
            {
                throw new ArgumentException(
                    $"Not a TM1 Web install: {Dir} has no wlp/bin/server. Point {HomeDir} at the tm1web folder of a Linux TM1 Web install.",
                    nameof(dir)
                );
            }
        }

        /// <summary>The folder, absolute.</summary>
        public string Dir { get; }

        /// <inheritdoc/>
        public override string Describe() => "install folder " + Dir;
    }

    /// <summary>A Docker volume holding a TM1 Web install.</summary>
    public sealed record InstallVolume : Tm1WebSource
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

    /// <summary>A folder holding IBM's TM1 Web bundle, which the image unpacks into the shared cache volume.</summary>
    public sealed record Bundle : Tm1WebSource
    {
        /// <summary>A bundle folder, and the file in it (null for the newest).</summary>
        /// <exception cref="ArgumentException">The folder isn't there, or the file isn't in it.</exception>
        public Bundle(string dir, string? file = null)
        {
            ArgumentNullException.ThrowIfNull(dir);
            Dir = Settings.Absolute(dir);
            if (!Directory.Exists(Dir))
            {
                throw new ArgumentException(
                    $"{BundleDir} is not a folder: {Dir}. Point it at the folder holding the bundle; name the file itself with {BundleSetting}.",
                    nameof(dir)
                );
            }
            File = string.IsNullOrWhiteSpace(file) ? null : file;
            if (File is not null && !System.IO.File.Exists(Path.Combine(Dir, File)))
            {
                throw new ArgumentException($"{BundleSetting} names {File}, which isn't in {Dir}", nameof(file));
            }
        }

        /// <summary>The folder, absolute.</summary>
        public string Dir { get; }

        /// <summary>The bundle's file name, or null for the newest in <see cref="Dir"/>.</summary>
        public string? File { get; }

        /// <inheritdoc/>
        public override string Describe() => "bundle " + (File is not null ? Path.Combine(Dir, File) : Dir + " (newest)");
    }

    /// <summary>A download link for IBM's TM1 Web bundle, fetched by the image into the shared cache volume.</summary>
    public sealed record BundleUrl : Tm1WebSource
    {
        /// <summary>A bundle URL, and its SHA-256 (null not to check).</summary>
        public BundleUrl(Uri url, string? sha256 = null)
        {
            ArgumentNullException.ThrowIfNull(url);
            Url = url;
            Sha256 = string.IsNullOrWhiteSpace(sha256) ? null : sha256;
        }

        /// <summary>The link.</summary>
        public Uri Url { get; }

        /// <summary>The bundle's SHA-256, or null.</summary>
        public string? Sha256 { get; }

        /// <inheritdoc/>
        public override string Describe() => "bundle URL " + Settings.Safe(Url);
    }
}

using System;
using System.IO;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>Where a <see cref="Tm1ServerContainer"/> gets TM1 from. The <c>tm1-server</c> image holds
/// no IBM software, so one of these supplies it: an image with TM1 built in
/// (<see cref="PrivateImage"/>), an installed <c>tm1_64</c> folder (<see cref="InstallDir"/>), a
/// Docker volume holding one (<see cref="InstallVolume"/>), a folder holding IBM's bundle
/// (<see cref="Bundle"/>), or a download link for the bundle (<see cref="BundleUrl"/>).
/// <see cref="Discover()"/> picks one from settings, as the Java <c>Tm1Source</c> does.</summary>
public abstract record Tm1Source
{
    /// <summary>The setting naming an image with TM1 built in.</summary>
    public const string PrivateImageSetting = "TM1_PRIVATE_IMAGE";

    /// <summary>The setting naming an installed <c>tm1_64</c> folder.</summary>
    public const string HomeDir = "TM1_HOME_DIR";

    /// <summary>The setting naming a Docker volume holding an install.</summary>
    public const string InstallVolumeSetting = "TM1_INSTALL_VOLUME";

    /// <summary>The setting naming the folder holding IBM's bundle.</summary>
    public const string BundleDir = "TM1_BUNDLE_DIR";

    /// <summary>The setting naming the bundle file in <see cref="BundleDir"/> (the newest otherwise).</summary>
    public const string BundleSetting = "TM1_BUNDLE";

    /// <summary>The setting holding a download link for the bundle.</summary>
    public const string BundleUrlSetting = "TM1_BUNDLE_URL";

    /// <summary>The setting holding the bundle's SHA-256, checked after the download.</summary>
    public const string BundleSha256 = "TM1_BUNDLE_SHA256";

    private protected Tm1Source() { }

    /// <summary>Where TM1 comes from, for logs: never a URL's credentials or query string.</summary>
    public abstract string Describe();

    /// <summary>The first source set, from <see cref="AppContext"/> switches or environment
    /// variables; null when none is.</summary>
    /// <exception cref="ArgumentException">A source is set but wrong (a folder that isn't there, say):
    /// a test should fail on that, not skip.</exception>
    public static Tm1Source? Discover() => Discover(Settings.Get);

    /// <summary>The first source set, looking settings up with <paramref name="lookup"/>, in this
    /// order: <c>TM1_PRIVATE_IMAGE</c>, <c>TM1_HOME_DIR</c>, <c>TM1_INSTALL_VOLUME</c>,
    /// <c>TM1_BUNDLE_DIR</c> (with <c>TM1_BUNDLE</c>), <c>TM1_BUNDLE_URL</c> (with
    /// <c>TM1_BUNDLE_SHA256</c>). Blank settings count as unset.</summary>
    public static Tm1Source? Discover(Func<string, string?> lookup)
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
        return null;
    }

    /// <summary>An image with TM1 built in, used in place of <c>tm1-server</c>.</summary>
    public sealed record PrivateImage : Tm1Source
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

    /// <summary>An installed <c>tm1_64</c> folder, mounted read-only.</summary>
    public sealed record InstallDir : Tm1Source
    {
        /// <summary>An install folder.</summary>
        /// <exception cref="ArgumentException">It has no <c>bin64/tm1s.exe</c>.</exception>
        public InstallDir(string dir)
        {
            ArgumentNullException.ThrowIfNull(dir);
            Dir = Settings.Absolute(dir);
            if (!File.Exists(Path.Combine(Dir, "bin64", "tm1s.exe")))
            {
                throw new ArgumentException(
                    $"Not a TM1 install: {Dir} has no bin64/tm1s.exe. Point {HomeDir} at the tm1_64 folder of a Linux TM1 Server install.",
                    nameof(dir)
                );
            }
        }

        /// <summary>The folder, absolute.</summary>
        public string Dir { get; }

        /// <inheritdoc/>
        public override string Describe() => "install folder " + Dir;
    }

    /// <summary>A Docker volume holding a TM1 install.</summary>
    public sealed record InstallVolume : Tm1Source
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

    /// <summary>A folder holding IBM's TM1 bundle, which the image unpacks into the shared cache volume.</summary>
    public sealed record Bundle : Tm1Source
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

    /// <summary>A download link for IBM's TM1 bundle, fetched by the image into the shared cache volume.</summary>
    public sealed record BundleUrl : Tm1Source
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

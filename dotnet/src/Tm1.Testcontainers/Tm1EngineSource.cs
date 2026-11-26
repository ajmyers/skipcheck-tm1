// SPDX-License-Identifier: Apache-2.0

using System;
using System.IO;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>Where a <see cref="Tm1EngineContainer"/> gets TM1 v12 from, as <see cref="Tm1Source"/> for
/// TM1 11. The <c>tm1-engine</c> image holds no IBM software, so one of these supplies it: an image
/// with TM1 built in (<see cref="PrivateImage"/>), an install the image made, as a folder
/// (<see cref="InstallDir"/>) or a Docker volume (<see cref="InstallVolume"/>), a folder holding
/// IBM's package (<see cref="Bundle"/>), or a download link for it (<see cref="BundleUrl"/>).
/// <see cref="Discover()"/> picks one from settings, as the Java <c>Tm1EngineSource</c> does, and
/// falls back to <see cref="Tm1Source.BundleDir"/> when that folder holds a TM1 v12 package too.</summary>
public abstract record Tm1EngineSource
{
    /// <summary>The setting naming an image with TM1 v12 built in.</summary>
    public const string PrivateImageSetting = "TM1_ENGINE_PRIVATE_IMAGE";

    /// <summary>The setting naming an install the image made, as a folder.</summary>
    public const string HomeDir = "TM1_ENGINE_HOME_DIR";

    /// <summary>The setting naming a Docker volume holding an install.</summary>
    public const string InstallVolumeSetting = "TM1_ENGINE_INSTALL_VOLUME";

    /// <summary>The setting naming the folder holding IBM's package.</summary>
    public const string BundleDir = "TM1_ENGINE_BUNDLE_DIR";

    /// <summary>The setting naming the package's file in <see cref="BundleDir"/> (the newest otherwise).</summary>
    public const string BundleSetting = "TM1_ENGINE_BUNDLE";

    /// <summary>The setting holding a download link for IBM's package.</summary>
    public const string BundleUrlSetting = "TM1_ENGINE_BUNDLE_URL";

    /// <summary>The setting holding the package's SHA-256, checked after the download.</summary>
    public const string BundleSha256 = "TM1_ENGINE_BUNDLE_SHA256";

    private protected Tm1EngineSource() { }

    /// <summary>Where TM1 comes from, for logs: never a URL's credentials or query string.</summary>
    public abstract string Describe();

    /// <summary>The first source set, from <see cref="AppContext"/> switches or environment
    /// variables; null when none is.</summary>
    /// <exception cref="ArgumentException">A source is set but wrong (a folder that isn't there, say):
    /// a test should fail on that, not skip.</exception>
    public static Tm1EngineSource? Discover() => Discover(Settings.Get);

    /// <summary>The first source set, looking settings up with <paramref name="lookup"/>, in this
    /// order: <c>TM1_ENGINE_PRIVATE_IMAGE</c>, <c>TM1_ENGINE_HOME_DIR</c>, <c>TM1_ENGINE_INSTALL_VOLUME</c>,
    /// <c>TM1_ENGINE_BUNDLE_DIR</c> (with <c>TM1_ENGINE_BUNDLE</c>), <c>TM1_ENGINE_BUNDLE_URL</c> (with
    /// <c>TM1_ENGINE_BUNDLE_SHA256</c>), then <c>TM1_BUNDLE_DIR</c>, TM1 11's downloads folder, if it holds a
    /// <c>tm1-v*-standalone-linux.tgz</c> (or <c>TM1_ENGINE_BUNDLE</c> is set): one folder then serves both
    /// versions. Blank settings count as unset.</summary>
    /// <exception cref="ArgumentException">A setting is present but wrong.</exception>
    public static Tm1EngineSource? Discover(Func<string, string?> lookup)
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
        // The TM1 downloads folder, when it holds TM1 v12 too. Skipped quietly when it doesn't: that
        // folder is TM1 11's setting, and is valid without v12 in it.
        if (get(Tm1Source.BundleDir) is { } tm1Dir && (get(BundleSetting) is not null || Settings.HasFile(tm1Dir, IsPackage)))
        {
            return new Bundle(tm1Dir, get(BundleSetting));
        }
        return null;
    }

    private static bool IsPackage(string name) =>
        name.StartsWith("tm1-v", StringComparison.Ordinal) && name.EndsWith("-standalone-linux.tgz", StringComparison.Ordinal);

    /// <summary>An image built on <c>tm1-engine</c> with TM1 inside, from a private registry: it
    /// contains IBM software, so it must never be pushed anywhere public.</summary>
    public sealed record PrivateImage : Tm1EngineSource
    {
        /// <summary>A private image, by its full name.</summary>
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

    /// <summary>A TM1 v12 install the <c>tm1-engine</c> image made (<c>install</c> as its command), as a
    /// folder: the one containing <c>bin/tm1sd.exe</c>. Mounted read-only. An install made by IBM's
    /// installer isn't one.</summary>
    public sealed record InstallDir : Tm1EngineSource
    {
        /// <summary>An install folder.</summary>
        /// <exception cref="ArgumentException">It has no <c>bin/tm1sd.exe</c>.</exception>
        public InstallDir(string dir)
        {
            ArgumentNullException.ThrowIfNull(dir);
            Dir = Settings.Absolute(dir);
            if (!File.Exists(Path.Combine(Dir, "bin", "tm1sd.exe")))
            {
                throw new ArgumentException(
                    $"Not a TM1 v12 install: {Dir} has no bin/tm1sd.exe. Point {HomeDir} at an install the tm1-engine image made.",
                    nameof(dir)
                );
            }
        }

        /// <summary>The folder, absolute.</summary>
        public string Dir { get; }

        /// <inheritdoc/>
        public override string Describe() => "install folder " + Dir;
    }

    /// <summary>A Docker volume holding an install the <c>tm1-engine</c> image made. If the volume is
    /// empty, the container fails to start, since there's nothing to install from.</summary>
    public sealed record InstallVolume : Tm1EngineSource
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

    /// <summary>A folder holding IBM's <c>tm1-v&lt;version&gt;-standalone-linux.tgz</c>, mounted read-only.
    /// The image installs TM1 from it on first start into a shared cache volume, so later starts are
    /// fast. The folder can hold TM1 11, TM1 Web and Cognos downloads too: each image picks its own.</summary>
    public sealed record Bundle : Tm1EngineSource
    {
        /// <summary>A package folder, and the file in it (null for the newest).</summary>
        /// <exception cref="ArgumentException">The folder isn't there, or the file isn't in it.</exception>
        public Bundle(string dir, string? file = null)
        {
            ArgumentNullException.ThrowIfNull(dir);
            Dir = Settings.Absolute(dir);
            if (!Directory.Exists(Dir))
            {
                throw new ArgumentException(
                    $"{BundleDir} is not a folder: {Dir}. Point it at the folder holding the package; name the file itself with {BundleSetting}.",
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

        /// <summary>The package's file name, or null for the newest in <see cref="Dir"/>.</summary>
        public string? File { get; }

        /// <inheritdoc/>
        public override string Describe() => "package " + (File is not null ? Path.Combine(Dir, File) : Dir + " (newest)");
    }

    /// <summary>A download link for IBM's package, such as a pre-signed S3, R2 or GCS URL. Links can
    /// carry credentials in the query string or the user-info part, so neither is ever logged.</summary>
    public sealed record BundleUrl : Tm1EngineSource
    {
        /// <summary>A package URL, and its SHA-256 (null not to check).</summary>
        public BundleUrl(Uri url, string? sha256 = null)
        {
            ArgumentNullException.ThrowIfNull(url);
            Url = url;
            Sha256 = string.IsNullOrWhiteSpace(sha256) ? null : sha256;
        }

        /// <summary>The link.</summary>
        public Uri Url { get; }

        /// <summary>The package's SHA-256, or null.</summary>
        public string? Sha256 { get; }

        /// <inheritdoc/>
        public override string Describe() => "package URL " + Settings.Safe(Url);
    }
}

using System;
using System.Collections.Generic;
using System.IO;

namespace AjMyers.Tm1.Testcontainers.Tests;

/// <summary>Java's Tm1SourceTest.</summary>
public class Tm1SourceTests : TempDir
{
    [Fact]
    public void FindsNothingWhenNothingIsSet()
    {
        Assert.Null(Tm1Source.Discover(Settings([])));
        Assert.Null(Tm1Source.Discover(Settings(new() { [Tm1Source.HomeDir] = "  " })));
    }

    [Fact]
    public void TriesSourcesInTheImagesOrder()
    {
        var install = FakeInstall();
        var all = new Dictionary<string, string>
        {
            [Tm1Source.PrivateImageSetting] = "registry.example.com/private/tm1-server:2.1.19",
            [Tm1Source.HomeDir] = install,
            [Tm1Source.InstallVolumeSetting] = "tm1_tm1-install",
            [Tm1Source.BundleDir] = Temp,
            [Tm1Source.BundleUrlSetting] = "https://example.com/b.tar.gz",
        };
        Assert.IsType<Tm1Source.PrivateImage>(Tm1Source.Discover(Settings(all)));
        Assert.Equal(new Tm1Source.InstallDir(install), Tm1Source.Discover(Settings(Without(all, Tm1Source.PrivateImageSetting))));
        Assert.Equal(
            new Tm1Source.InstallVolume("tm1_tm1-install"),
            Tm1Source.Discover(Settings(Without(all, Tm1Source.PrivateImageSetting, Tm1Source.HomeDir)))
        );
        Assert.Equal(
            new Tm1Source.Bundle(Temp, null),
            Tm1Source.Discover(Settings(Without(all, Tm1Source.PrivateImageSetting, Tm1Source.HomeDir, Tm1Source.InstallVolumeSetting)))
        );
        Assert.Equal(
            new Tm1Source.BundleUrl(new Uri("https://example.com/b.tar.gz"), "abc"),
            Tm1Source.Discover(Settings(new() { [Tm1Source.BundleUrlSetting] = "https://example.com/b.tar.gz", [Tm1Source.BundleSha256] = "abc" }))
        );
    }

    [Fact]
    public void FailsOnAWrongSettingRatherThanSkipping()
    {
        Assert.Contains(
            "no bin64/tm1s.exe",
            Assert.Throws<ArgumentException>(() => Tm1Source.Discover(Settings(new() { [Tm1Source.HomeDir] = Temp }))).Message
        );
        Assert.Contains(
            "is not a folder",
            Assert.Throws<ArgumentException>(() => Tm1Source.Discover(Settings(new() { [Tm1Source.BundleDir] = Path.Combine(Temp, "missing") }))).Message
        );
        Assert.Contains(
            "isn't in",
            Assert
                .Throws<ArgumentException>(() =>
                    Tm1Source.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp, [Tm1Source.BundleSetting] = "nope.tar.gz" }))
                )
                .Message
        );
    }

    [Fact]
    public void NamesABundleFileWithinItsFolder()
    {
        Touch("tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz");
        Assert.Equal(
            new Tm1Source.Bundle(Temp, "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"),
            Tm1Source.Discover(
                Settings(new() { [Tm1Source.BundleDir] = Temp, [Tm1Source.BundleSetting] = "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz" })
            )
        );
    }

    [Fact]
    public void NeverDescribesAUrlsQueryString()
    {
        var url = new Tm1Source.BundleUrl(new Uri("https://bucket.s3.amazonaws.com/b.tar.gz?X-Amz-Signature=secret"));
        Assert.Equal("bundle URL https://bucket.s3.amazonaws.com/b.tar.gz", url.Describe());
    }

    [Fact]
    public void NeverDescribesCredentialsInAUrl()
    {
        var url = new Tm1Source.BundleUrl(new Uri("https://ci-user:s3cr3t@artifacts.example.com:8443/tm1/b.tar.gz"));
        Assert.Equal("bundle URL https://artifacts.example.com:8443/tm1/b.tar.gz", url.Describe());
    }

    [Fact]
    public void TagsSnapshotsWithEdge()
    {
        Assert.Equal("edge", Tm1ServerContainer.ImageTag("0.1.0-SNAPSHOT"));
        Assert.Equal("edge", Tm1ServerContainer.ImageTag("0.1.0-dev"));
        Assert.Equal("0.1.0", Tm1ServerContainer.ImageTag("0.1.0"));
    }

    [Fact]
    public void MountsEachSourceAsJavaDoes()
    {
        Touch("b.tar.gz");
        var bundle = new Tm1ServerContainer(new Tm1Source.Bundle(Temp, "b.tar.gz"));
        Assert.Equal([$"{Temp}:/ibm:ro", "tm1-testcontainers-cache:/tm1/cache"], bundle.Binds);
        Assert.Equal("b.tar.gz", bundle.EnvMap["TM1_BUNDLE"]);
        Assert.Equal("empty", bundle.EnvMap["TM1_SEED"]);
        Assert.Equal([8010], bundle.ExposedPorts);
        var install = new Tm1ServerContainer(new Tm1Source.InstallDir(FakeInstall()));
        Assert.Equal([$"{Path.Combine(Temp, "tm1_64")}:/opt/ibm/cognos/tm1_64:ro"], install.Binds);
        var volume = new Tm1ServerContainer(new Tm1Source.InstallVolume("v"));
        Assert.Equal(["v:/opt/ibm/cognos/tm1_64"], volume.Binds);
        var url = new Tm1ServerContainer(new Tm1Source.BundleUrl(new Uri("https://x/b.tar.gz?sig=s"), "ab"));
        Assert.Equal("https://x/b.tar.gz?sig=s", url.EnvMap["TM1_BUNDLE_URL"]);
        Assert.Equal("ab", url.EnvMap["TM1_BUNDLE_SHA256"]);
        Assert.Equal(["tm1-testcontainers-cache:/tm1/cache"], url.Binds);
        var image = new Tm1ServerContainer(new Tm1Source.PrivateImage("me/tm1:11.8"));
        Assert.Equal("me/tm1:11.8", image.DockerImageName);
        Assert.Empty(image.Binds);
        Assert.StartsWith(Tm1ServerContainer.DefaultImage + ":", volume.DockerImageName);
    }

    private string FakeInstall()
    {
        Touch(Path.Combine("tm1_64", "bin64", "tm1s.exe"));
        return Path.Combine(Temp, "tm1_64");
    }
}

/// <summary>Java's CognosSourceTest.</summary>
public class CognosSourceTests : TempDir
{
    [Fact]
    public void FindsNothingWhenNothingIsSet()
    {
        Assert.Null(CognosSource.Discover(Settings([])));
        Assert.Null(CognosSource.Discover(Settings(new() { [CognosSource.ZipDir] = "  " })));
    }

    [Fact]
    public void TriesSourcesInOrder()
    {
        Assert.Equal(
            new CognosSource.PrivateImage("cognos-server:12.1.1-private"),
            CognosSource.Discover(
                Settings(new() { [CognosSource.PrivateImageSetting] = "cognos-server:12.1.1-private", [CognosSource.InstallVolumeSetting] = "v" })
            )
        );
        Assert.Equal(
            new CognosSource.InstallVolume("v"),
            CognosSource.Discover(Settings(new() { [CognosSource.InstallVolumeSetting] = "v", [CognosSource.ZipDir] = Temp }))
        );
        Assert.Equal(
            new CognosSource.Zip(Temp, null),
            CognosSource.Discover(Settings(new() { [CognosSource.ZipDir] = Temp, [CognosSource.ZipUrlSetting] = "https://example.com/c.zip" }))
        );
        Assert.Equal(
            new CognosSource.ZipUrl(new Uri("https://example.com/c.zip"), "abc"),
            CognosSource.Discover(Settings(new() { [CognosSource.ZipUrlSetting] = "https://example.com/c.zip", [CognosSource.ZipSha256] = "abc" }))
        );
    }

    [Fact]
    public void UsesTheTm1BundleFolderWhenItHoldsCognos()
    {
        Assert.Null(CognosSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp })));
        Touch("casrv-12.1.1-2512181837-linuxi38664h.zip");
        Assert.Equal(new CognosSource.Zip(Temp, null), CognosSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp })));
        Assert.Equal(
            new CognosSource.Zip(Temp, "casrv-12.1.1-2512181837-linuxi38664h.zip"),
            CognosSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp, [CognosSource.ZipSetting] = "casrv-12.1.1-2512181837-linuxi38664h.zip" }))
        );
    }

    [Fact]
    public void FailsOnAWrongSettingRatherThanSkipping()
    {
        Assert.Contains(
            CognosSource.ZipDir,
            Assert.Throws<ArgumentException>(() => CognosSource.Discover(Settings(new() { [CognosSource.ZipDir] = Path.Combine(Temp, "missing") }))).Message
        );
        Assert.Contains(
            "nope.zip",
            Assert
                .Throws<ArgumentException>(() =>
                    CognosSource.Discover(Settings(new() { [CognosSource.ZipDir] = Temp, [CognosSource.ZipSetting] = "nope.zip" }))
                )
                .Message
        );
    }

    [Fact]
    public void NeverDescribesAUrlsCredentials()
    {
        var source = new CognosSource.ZipUrl(new Uri("https://user:secret@example.com:8443/c.zip?X-Amz-Signature=SECRET"));
        Assert.Equal("zip URL https://example.com:8443/c.zip", source.Describe());
    }
}

/// <summary>Java's PawSourceTest.</summary>
public class PawSourceTests : TempDir
{
    [Fact]
    public void FindsNothingWhenNothingIsSet()
    {
        Assert.Null(PawSource.Discover(Settings([])));
        Assert.Null(PawSource.Discover(Settings(new() { [PawSource.ZipSetting] = "  ", [PawSource.BundleDir] = "" })));
    }

    [Fact]
    public void UsesTheZipSetDirectly()
    {
        var zip = Touch("ipa_workspace_local_2.1.19.1260.zip");
        Assert.Equal(new PawSource(zip), PawSource.Discover(Settings(new() { [PawSource.ZipSetting] = zip })));
    }

    [Fact]
    public void PicksTheNewestZipInTheBundleFolder()
    {
        var older = Touch("ipa_workspace_local_2.1.18.1000.zip");
        var newer = Touch("ipa_workspace_local_2.1.19.1260.zip");
        Touch("something-else.zip");
        File.SetLastWriteTimeUtc(older, DateTime.UnixEpoch.AddSeconds(1));
        File.SetLastWriteTimeUtc(newer, DateTime.UnixEpoch.AddSeconds(2));
        Assert.Equal(new PawSource(newer), PawSource.Discover(Settings(new() { [PawSource.BundleDir] = Temp })));
    }

    [Fact]
    public void PrefersTheZipOverTheBundleFolder()
    {
        var zip = Touch("ipa_workspace_local_2.1.19.1260.zip");
        Assert.Equal(new PawSource(zip), PawSource.Discover(Settings(new() { [PawSource.ZipSetting] = zip, [PawSource.BundleDir] = "/nowhere" })));
    }

    [Fact]
    public void FailsOnAWrongSettingRatherThanSkipping()
    {
        Assert.Contains(
            "Not a file",
            Assert.Throws<ArgumentException>(() => PawSource.Discover(Settings(new() { [PawSource.ZipSetting] = Path.Combine(Temp, "missing.zip") }))).Message
        );
        Assert.Contains(
            "has no ipa_workspace_local_*.zip",
            Assert.Throws<ArgumentException>(() => PawSource.Discover(Settings(new() { [PawSource.BundleDir] = Temp }))).Message
        );
        Assert.Contains(
            "is not a folder",
            Assert.Throws<ArgumentException>(() => PawSource.Discover(Settings(new() { [PawSource.BundleDir] = Path.Combine(Temp, "nope") }))).Message
        );
    }

    [Fact]
    public async System.Threading.Tasks.Task RefusesToStartWithoutTheLicense()
    {
        var paw = new PawContainer(new PawSource(Touch("ipa_workspace_local_2.1.19.1260.zip")));
        if (!PawContainer.LicenseAcceptedBySetting())
        {
            Assert.Contains(PawContainer.AcceptLicenseSetting, (await Assert.ThrowsAsync<InvalidOperationException>(() => paw.StartAsync())).Message);
        }
        Assert.Throws<ArgumentException>(() => paw.WithSetting("TM1_HOME_DIR", "x"));
    }
}

/// <summary>Java's Tm1WebSourceTest.</summary>
public class Tm1WebSourceTests : TempDir
{
    [Fact]
    public void FindsNothingWhenNothingIsSet()
    {
        Assert.Null(Tm1WebSource.Discover(Settings([])));
        Assert.Null(Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.HomeDir] = "  " })));
    }

    [Fact]
    public void TriesSourcesInTheImagesOrder()
    {
        var install = FakeInstall();
        Assert.IsType<Tm1WebSource.PrivateImage>(
            Tm1WebSource.Discover(
                Settings(new() { [Tm1WebSource.PrivateImageSetting] = "registry.example.com/private/tm1-web:2.1.19", [Tm1WebSource.HomeDir] = install })
            )
        );
        Assert.Equal(
            new Tm1WebSource.InstallDir(install),
            Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.HomeDir] = install, [Tm1WebSource.InstallVolumeSetting] = "v" }))
        );
        Assert.Equal(
            new Tm1WebSource.InstallVolume("v"),
            Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.InstallVolumeSetting] = "v", [Tm1WebSource.BundleDir] = Temp }))
        );
        Assert.Equal(
            new Tm1WebSource.Bundle(Temp, null),
            Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.BundleDir] = Temp, [Tm1WebSource.BundleUrlSetting] = "https://example.com/b.tar.gz" }))
        );
        Assert.Equal(
            new Tm1WebSource.BundleUrl(new Uri("https://example.com/b.tar.gz"), "abc"),
            Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.BundleUrlSetting] = "https://example.com/b.tar.gz", [Tm1WebSource.BundleSha256] = "abc" }))
        );
    }

    [Fact]
    public void UsesTheTm1BundleFolderWhenItHoldsTm1Web()
    {
        Assert.Null(Tm1WebSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp })));
        Touch("tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz");
        Assert.Equal(new Tm1WebSource.Bundle(Temp, null), Tm1WebSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp })));
        Assert.Equal(
            new Tm1WebSource.Bundle(Temp, "tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz"),
            Tm1WebSource.Discover(
                Settings(new() { [Tm1Source.BundleDir] = Temp, [Tm1WebSource.BundleSetting] = "tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz" })
            )
        );
        var other = Directory.CreateDirectory(Path.Combine(Temp, "other")).FullName;
        Assert.Equal(
            new Tm1WebSource.Bundle(other, null),
            Tm1WebSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp, [Tm1WebSource.BundleDir] = other }))
        );
    }

    [Fact]
    public void FailsOnAWrongSettingRatherThanSkipping()
    {
        Assert.Contains(
            "no wlp/bin/server",
            Assert.Throws<ArgumentException>(() => Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.HomeDir] = Temp }))).Message
        );
        Assert.Contains(
            "is not a folder",
            Assert.Throws<ArgumentException>(() => Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.BundleDir] = Path.Combine(Temp, "missing") }))).Message
        );
        Assert.Contains(
            "isn't in",
            Assert
                .Throws<ArgumentException>(() =>
                    Tm1WebSource.Discover(Settings(new() { [Tm1WebSource.BundleDir] = Temp, [Tm1WebSource.BundleSetting] = "nope.tar.gz" }))
                )
                .Message
        );
    }

    [Fact]
    public void NeverDescribesAUrlWithItsCredentials()
    {
        var source = new Tm1WebSource.BundleUrl(new Uri("https://user:token@example.com:8443/b/tm1web.tar.gz?X-Amz-Signature=SECRET"));
        Assert.Equal("bundle URL https://example.com:8443/b/tm1web.tar.gz", source.Describe());
    }

    private string FakeInstall()
    {
        Touch(Path.Combine("tm1web", "wlp", "bin", "server"));
        return Path.Combine(Temp, "tm1web");
    }
}

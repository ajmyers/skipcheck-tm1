using System.Collections.Generic;
using System.IO;
using AjMyers.Tm1.Client.Tests.Live;

namespace AjMyers.Tm1.Client.Tests;

/// <summary>The live tests' TM1 container finds TM1 as Java's tm1-testcontainers does: the same
/// settings, the same order, the same mounts and cache volume.</summary>
public class LiveHarnessTests
{
    private static readonly string[] Common = ["run", "-d", "--platform", "linux/amd64", "-p", "127.0.0.1::8010", "-e", "TM1_SEED=empty"];
    private static readonly string[] Label = ["--label", "net.ajmyers.tm1.tests=dotnet"];

    [Fact]
    public void FindsTheFirstSourceSetInJavasOrder()
    {
        var env = new Dictionary<string, string?> { ["TM1_BUNDLE_URL"] = "https://x/b.tar.gz?sig=secret", ["TM1_INSTALL_VOLUME"] = "tm1_tm1-install", ["TM1_PRIVATE_IMAGE"] = " " };
        Assert.Equal(new Tm1Container.Source("install-volume", "tm1_tm1-install"), Tm1Container.Discover(n => env.GetValueOrDefault(n)));
        env["TM1_PRIVATE_IMAGE"] = "me/tm1:11.8";
        Assert.Equal(new Tm1Container.Source("private-image", "me/tm1:11.8"), Tm1Container.Discover(n => env.GetValueOrDefault(n)));
        var url = Tm1Container.Discover(n => n switch { "TM1_BUNDLE_URL" => "https://x/b.tar.gz?sig=secret", "TM1_BUNDLE_SHA256" => "ab", _ => null });
        Assert.Equal(new Tm1Container.Source("bundle-url", "https://x/b.tar.gz?sig=secret", "ab"), url);
        Assert.DoesNotContain("secret", url!.Describe());
        Assert.Null(Tm1Container.Discover(_ => null));
        Assert.Throws<System.ArgumentException>(() => Tm1Container.Discover(n => n == "TM1_BUNDLE_DIR" ? "/no/such/folder" : null));
    }

    [Fact]
    public void MountsEachSourceAsJavasTm1ServerContainer()
    {
        var dir = Path.GetTempPath().TrimEnd('/');
        Assert.Equal([.. Common, .. Label, "me/tm1"], Tm1Container.RunArguments(new("private-image", "me/tm1"), _ => null));
        Assert.Equal([.. Common, "-v", "/opt/tm1:/opt/ibm/cognos/tm1_64:ro", .. Label, Tm1Container.DefaultImage], Tm1Container.RunArguments(new("install-dir", "/opt/tm1"), _ => null));
        Assert.Equal([.. Common, "-v", $"{dir}:/ibm:ro", "-v", "tm1-testcontainers-cache:/tm1/cache", "-e", "TM1_BUNDLE=b.tar.gz", .. Label, "local/tm1-server"],
            Tm1Container.RunArguments(new("bundle", dir, "b.tar.gz"), n => n == "TM1_SERVER_IMAGE" ? "local/tm1-server" : null));
        Assert.Equal([.. Common, "-e", "TM1_BUNDLE_URL=https://x/b", "-v", "tm1-testcontainers-cache:/tm1/cache", "-e", "TM1_BUNDLE_SHA256=ab", .. Label, Tm1Container.DefaultImage],
            Tm1Container.RunArguments(new("bundle-url", "https://x/b", "ab"), _ => null));
    }
}

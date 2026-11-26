// SPDX-License-Identifier: Apache-2.0

using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>The example tests that start containers of their own (Java's <c>examples/*ExampleTest</c>)
/// run one at a time, as JUnit runs Java's: xUnit runs test classes side by side otherwise, which
/// would start several TM1 servers and Cognos at once.</summary>
[CollectionDefinition(Name)]
public sealed class OwnContainers
{
    public const string Name = "Examples that start their own containers";
}

/// <summary>Java's CamSecurityExampleTest: three users, three TM1 security profiles, through Cognos
/// (CAM) security: an administrator, an analyst who may write to a cube, and a viewer who may only
/// read it. Cognos takes a couple of minutes to start, so this class has one test.</summary>
[Collection(OwnContainers.Name)]
public class CamSecurityExampleTests(CognosFixture fixture) : IClassFixture<CognosFixture>
{
    [RequiresCognosFact]
    public async Task EachUserGetsTheirGroupsRights()
    {
        var tm1Source = Tm1Source.Discover() ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);
        await using var cognos = new CognosContainer(fixture.Source)
            .WithUser("admin", "adminpw", "Admins")
            .WithUser("analyst", "analystpw", "Analysts", "Readers")
            .WithUser("viewer", "viewerpw", "Readers");
        await using var tm1 = new Tm1ServerContainer(tm1Source).WithCamSecurity(cognos, "admin");
        await tm1.StartAsync();

        await using var admin = new Tm1Rest(tm1, cognos.CamAuthorization("admin"));
        await using var analyst = new Tm1Rest(tm1, cognos.CamAuthorization("analyst"));
        await using var viewer = new Tm1Rest(tm1, cognos.CamAuthorization("viewer"));
        // TM1 knows users by CAMID, and puts them in their Cognos groups at logon
        Assert.Contains("\"Type\":\"Admin\"", await admin.GetAsync("ActiveUser?$select=Type"), StringComparison.Ordinal);
        var groups = await analyst.GetAsync("ActiveUser/Groups?$select=Name");
        Assert.Contains("CAMID(\\\"LDAP:g:cn=analysts,ou=groups\\\")", groups, StringComparison.Ordinal);
        Assert.Contains("CAMID(\\\"LDAP:g:cn=readers,ou=groups\\\")", groups, StringComparison.Ordinal);

        // The administrator builds a cube and its security: Analysts write, Readers read
        Assert.Equal(HttpStatusCode.Created, (await admin.PostAsync("ExecuteProcessWithReturn", SetUpSecurity())).Status);

        Assert.Equal(HttpStatusCode.NoContent, (await analyst.PostAsync("Cubes('Sales')/tm1.Update", Write("150"))).Status);
        var read = await viewer.PostAsync(
            "ExecuteMDX?$expand=Cells($select=Value)",
            "{\"MDX\":\"SELECT {[Measure].[Amount]} ON 0, {[Region].[North]} ON 1 FROM [Sales]\"}"
        );
        Assert.Contains("\"Value\":150", read.Body, StringComparison.Ordinal);
        var refused = await viewer.PostAsync("Cubes('Sales')/tm1.Update", Write("999"));
        Assert.Equal(HttpStatusCode.BadRequest, refused.Status);
        Assert.Contains("CubeCellWriteStatusCubeNoWriteAccess", refused.Body, StringComparison.Ordinal);
    }

    /// <summary>A TurboIntegrator process that creates the Sales cube and gives the groups access.</summary>
    private static string SetUpSecurity()
    {
        var analysts = "CAMID(\\\"LDAP:g:cn=analysts,ou=groups\\\")";
        var readers = "CAMID(\\\"LDAP:g:cn=readers,ou=groups\\\")";
        var prolog = string.Join(
            "\\n",
            "DimensionCreate('Region');",
            "DimensionElementInsert('Region', '', 'North', 'N');",
            "DimensionCreate('Measure');",
            "DimensionElementInsert('Measure', '', 'Amount', 'N');",
            "CubeCreate('Sales', 'Region', 'Measure');",
            "If(CubeExists('}CubeSecurity') = 0);",
            "  CubeCreate('}CubeSecurity', '}Cubes', '}Groups');",
            "EndIf;",
            "CellPutS('WRITE', '}CubeSecurity', 'Sales', '" + analysts + "');",
            "CellPutS('READ', '}CubeSecurity', 'Sales', '" + readers + "');",
            "SecurityRefresh;"
        );
        return "{\"Process\":{\"Name\":\"zSetUpSecurity\",\"PrologProcedure\":\"" + prolog + "\"}}";
    }

    private static string Write(string value) =>
        "{\"Cells\":[{\"Tuple@odata.bind\":[\"Dimensions('Region')/Hierarchies('Region')/Elements('North')\","
        + "\"Dimensions('Measure')/Hierarchies('Measure')/Elements('Amount')\"]}],\"Value\":\""
        + value
        + "\"}";
}

/// <summary>Java's ExplicitSourceExampleTest: choosing where TM1 comes from in code, instead of with
/// settings. Each test runs only where that kind of source is available, read from the same
/// environment variables <c>Discover()</c> uses, so these skip where they aren't set up.</summary>
[Collection(OwnContainers.Name)]
public class ExplicitSourceExampleTests
{
    [RequiresTm1AndEnvironmentFact(Tm1Source.HomeDir)]
    public async Task FromAnInstalledFolder()
    {
        // The tm1_64 folder of a Linux TM1 Server install. Fastest to start: nothing to install.
        var source = new Tm1Source.InstallDir(Environment.GetEnvironmentVariable(Tm1Source.HomeDir)!);
        await AssertStartsAsync(new Tm1ServerContainer(source));
    }

    [RequiresTm1AndEnvironmentFact(Tm1Source.BundleDir)]
    public async Task FromIbmsBundleFile()
    {
        // IBM's download, as is. Installed on first use into a shared cache volume.
        // Pass a file name as the second argument to pick one of several bundles.
        var source = new Tm1Source.Bundle(Environment.GetEnvironmentVariable(Tm1Source.BundleDir)!, null);
        await AssertStartsAsync(new Tm1ServerContainer(source));
    }

    [RequiresTm1AndEnvironmentFact(Tm1Source.BundleUrlSetting)]
    public async Task FromADownloadLink()
    {
        // A pre-signed S3/R2/GCS link, or any URL curl can fetch. The SHA-256 is optional but
        // recommended. The URL's query string (where pre-signed credentials live) is never logged.
        var source = new Tm1Source.BundleUrl(
            new Uri(Environment.GetEnvironmentVariable(Tm1Source.BundleUrlSetting)!),
            Environment.GetEnvironmentVariable(Tm1Source.BundleSha256)
        );
        await AssertStartsAsync(new Tm1ServerContainer(source));
    }

    [RequiresTm1AndEnvironmentFact(Tm1Source.InstallVolumeSetting)]
    public async Task FromADockerVolume()
    {
        // A volume that already holds an install, such as the one the tm1-server compose setup
        // fills (tm1_tm1-install), so tests reuse the TM1 you already run.
        var source = new Tm1Source.InstallVolume(Environment.GetEnvironmentVariable(Tm1Source.InstallVolumeSetting)!);
        await AssertStartsAsync(new Tm1ServerContainer(source));
    }

    [RequiresTm1AndEnvironmentFact(Tm1Source.PrivateImageSetting)]
    public async Task FromAPrivateImageWithTm1Inside()
    {
        // Built from docker/tm1-server/examples/private-image and pushed to a PRIVATE registry
        // (it contains IBM software). Fastest for CI: nothing to mount or install.
        var source = new Tm1Source.PrivateImage(Environment.GetEnvironmentVariable(Tm1Source.PrivateImageSetting)!);
        await AssertStartsAsync(new Tm1ServerContainer(source));
    }

    private static async Task AssertStartsAsync(Tm1ServerContainer container)
    {
        await using (container)
        {
            await container.StartAsync();
            Assert.NotNull(container.Tm1Version);
        }
    }

    /// <summary>A TM1 test that also needs an environment variable set, as Java's
    /// <c>@EnabledIfEnvironmentVariable(named = …, matches = ".+")</c>: the environment only, not a setting.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class RequiresTm1AndEnvironmentFactAttribute : RequiresTm1FactAttribute
    {
        public RequiresTm1AndEnvironmentFactAttribute(string named)
        {
            Named = named;
            Skip ??= string.IsNullOrEmpty(Environment.GetEnvironmentVariable(named)) ? "Environment variable " + named + " isn't set" : null;
        }

        public string Named { get; }
    }
}

/// <summary>Java's OidcExampleTest: TM1 in CAM security through Cognos's OIDC namespace, with Keycloak
/// as the provider. Two ways in, without a browser: a password, which Cognos checks with Keycloak
/// (<c>CAMNamespace …:OIDC</c>); and an ID token an application already holds, exchanged at Cognos
/// for a CAM passport (<c>CAMPassport</c>).</summary>
[Collection(OwnContainers.Name)]
public class OidcExampleTests(CognosFixture fixture) : IClassFixture<CognosFixture>
{
    [RequiresCognosFact]
    public async Task PasswordsAndTokensLogOnToTm1()
    {
        var tm1Source = Tm1Source.Discover() ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);
        await using var keycloak = new KeycloakContainer();
        await using var cognos = new CognosContainer(fixture.Source)
            .WithUser("admin", "adminpw", "Admins")
            .WithUser("viewer", "viewerpw", "Readers")
            .WithKeycloak(keycloak);
        await using var tm1 = new Tm1ServerContainer(tm1Source).WithCamSecurity(cognos, CognosContainer.Namespace.Oidc, "admin");
        await tm1.StartAsync();

        // A password: TM1 hands it to Cognos, which checks it with Keycloak
        await using (var admin = new Tm1Rest(tm1, cognos.CamAuthorization("admin", CognosContainer.Namespace.Oidc)))
        {
            var user = await admin.GetAsync("ActiveUser?$select=Name,Type");
            Assert.Contains("CAMID(\\\"OIDC:u:admin\\\")", user, StringComparison.Ordinal);
            Assert.Contains("\"Type\":\"Admin\"", user, StringComparison.Ordinal);
        }

        // A token: the app holds viewer's ID token, and Cognos turns it into a passport
        var passport = await cognos.PassportAsync(await keycloak.IdTokenAsync("viewer", "viewerpw"));
        await using var viewer = new Tm1Rest(tm1, "CAMPassport " + passport);
        Assert.Contains("CAMID(\\\"OIDC:g:readers\\\")", await viewer.GetAsync("ActiveUser/Groups?$select=Name"), StringComparison.Ordinal);
    }
}

/// <summary>Java's OwnDatabaseExampleTest: a server running your own database. <c>WithData</c> takes a
/// TM1 data folder, copied into the container before TM1 starts. This uses IBM's SData sample folder
/// from the TM1 install as "your" database, so it only runs when TM1 comes from an install folder
/// (<c>TM1_HOME_DIR</c>).</summary>
[Collection(OwnContainers.Name)]
public class OwnDatabaseExampleTests(Tm1ServerFixture fixture) : IClassFixture<Tm1ServerFixture>
{
    [RequiresTm1AndItsSampleFolderFact]
    public async Task RunsADatabaseFromAFolder()
    {
        var shared = fixture.Tm1Server;
        var myDatabase = SampleFolder((Tm1Source.InstallDir)shared.Source);

        await using var server = new Tm1ServerContainer(shared.Source).WithData(myDatabase).WithCredentials("admin", "apple");
        await server.StartAsync();

        await using var tm1 = new Tm1Rest(server);
        Assert.Contains("SalesCube", await tm1.GetAsync("Cubes('SalesCube')?$select=Name"), StringComparison.OrdinalIgnoreCase);
    }

    private static string SampleFolder(Tm1Source.InstallDir install) => Path.Combine(install.Dir, "samples", "tm1", "SData");

    /// <summary>A TM1 test that also needs TM1 from an install folder with the SData sample in it, as
    /// Java's two assumeTrue on the shared server's source (which is the discovered one). A source
    /// set wrong runs the test, to fail on it.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class RequiresTm1AndItsSampleFolderFactAttribute : RequiresTm1FactAttribute
    {
        public RequiresTm1AndItsSampleFolderFactAttribute()
        {
            Skip ??= WhyNot();
        }

        private static string? WhyNot()
        {
            try
            {
                return Tm1Source.Discover() is not Tm1Source.InstallDir install ? "needs TM1_HOME_DIR, to find a sample database folder"
                    : Directory.Exists(SampleFolder(install)) ? null
                    : "this install has no SData sample";
            }
            catch (ArgumentException)
            {
                return null;
            }
        }
    }
}

/// <summary>Java's PinnedVersionExampleTest: making sure tests run against the TM1 version you mean.
/// <c>WithExpectedVersion</c> makes the container refuse to start on another version; or check
/// <c>Tm1Version</c> and leave the test. Java skips these with assumeTrue once the server is known;
/// xUnit 2 can't skip a test that has started, so where Java would skip, these end without failing.</summary>
[Collection(OwnContainers.Name)]
public class PinnedVersionExampleTests(Tm1ServerFixture fixture) : IClassFixture<Tm1ServerFixture>
{
    // The version this example pins to. Change it to yours.
    private static readonly Tm1Version Pa2119 = Tm1Version.Parse("11.8.03500.6");

    [RequiresTm1Fact]
    public async Task RefusesToStartOnAnyOtherVersion()
    {
        var shared = fixture.Tm1Server;
        if (!shared.Tm1Version.Equals(Pa2119))
        {
            return; // this example pins to 11.8.03500.6
        }

        await using var server = new Tm1ServerContainer(shared.Source).WithExpectedVersion(Pa2119);
        await server.StartAsync(); // throws "Expected TM1 11.8.03500.6 but the server from ... reports ..." on a mismatch
        Assert.Equal(Pa2119, server.Tm1Version);
    }

    [RequiresTm1Fact]
    public void SkipsWhereTheFeatureDoesntExist()
    {
        var tm1 = fixture.Tm1Server;
        // Say the behavior under test first appeared in this (made-up) release
        var introducedIn = Tm1Version.Parse("11.8.03000");
        if (!tm1.Tm1Version.IsAtLeast(introducedIn))
        {
            return; // needs TM1 11.8.03000 or newer
        }

        Assert.True(tm1.Tm1Version >= introducedIn); // ...then test the behavior
    }
}

/// <summary>Java's SampleDatabaseExampleTest: a server of your own, for one test class, with IBM's
/// SData sample in it. The class gets its own container, started before its tests and stopped after
/// them (Java's <c>@BeforeAll</c> and <c>@AfterAll</c> are a class fixture here), and the shared
/// container's source tells this one where TM1 is. <see cref="Tm1Seed.Sdata"/> also sets the
/// sample's login, admin / apple.</summary>
[Collection(OwnContainers.Name)]
public class SampleDatabaseExampleTests(SampleDatabaseExampleTests.SampleServer fixture) : IClassFixture<SampleDatabaseExampleTests.SampleServer>
{
    [RequiresTm1Fact]
    public async Task ReadsTheSampleCubes()
    {
        Assert.Equal("apple", fixture.Sdata.Password);
        Assert.True(await fixture.Tm1.ModelCubeCountAsync() > 0);
        Assert.Contains("\"Name\":\"SalesCube\"", await fixture.Tm1.GetAsync("Cubes('SalesCube')?$select=Name"), StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>The class's own server and a session with it. Nothing starts when
    /// <see cref="RequiresTm1FactAttribute"/> would skip: xUnit makes a class's fixture even then.</summary>
    public sealed class SampleServer : IAsyncLifetime
    {
        private Tm1ServerContainer? sdata;
        private Tm1Rest? tm1;

        public Tm1ServerContainer Sdata => sdata ?? throw new InvalidOperationException("The SData server isn't started");

        public Tm1Rest Tm1 => tm1 ?? throw new InvalidOperationException("The SData server isn't started");

        public async Task InitializeAsync()
        {
            if (RequiresTm1FactAttribute.Tm1SkipReason() is not null)
            {
                return;
            }
            var shared = await Tm1ServerContainer.SharedAsync();
            sdata = new Tm1ServerContainer(shared.Source).WithSeed(Tm1Seed.Sdata);
            await sdata.StartAsync();
            tm1 = new Tm1Rest(sdata);
        }

        public async Task DisposeAsync()
        {
            if (tm1 is not null)
            {
                await tm1.DisposeAsync();
            }
            if (sdata is not null)
            {
                await sdata.DisposeAsync();
            }
        }
    }
}

/// <summary>Java's SharedAdminServerExampleTest: several TM1 servers, one Admin Server, as in a real
/// deployment. The servers register with a <see cref="Tm1AdminServerContainer"/>, and TM1 Web lists
/// them all from it. Each server is named with the image's <c>TM1_SERVER_NAME</c>.</summary>
[Collection(OwnContainers.Name)]
public class SharedAdminServerExampleTests
{
    [RequiresTm1Fact]
    public async Task ServersRegisterWithOneAdminServer()
    {
        var source = Tm1Source.Discover() ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);
        await using var admin = new Tm1AdminServerContainer(source);
        await using var planning = new Tm1ServerContainer(source).WithEnv("TM1_SERVER_NAME", "planning").WithAdminServer(admin);
        await using var reporting = new Tm1ServerContainer(source).WithEnv("TM1_SERVER_NAME", "reporting").WithAdminServer(admin);
        await planning.StartAsync();
        await reporting.StartAsync();

        var servers = await ServersAsync(admin);
        Assert.Contains("\"Name\":\"planning\"", servers, StringComparison.Ordinal);
        Assert.Contains("\"Name\":\"reporting\"", servers, StringComparison.Ordinal);
        await using var rest = new Tm1Rest(planning);
        Assert.Equal("planning", await rest.GetAsync("Configuration/ServerName/$value"));
    }

    [RequiresTm1WebFact]
    public async Task Tm1WebListsEveryRegisteredServer()
    {
        var source = Tm1Source.Discover() ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);
        var webSource = Tm1WebSource.Discover() ?? throw new InvalidOperationException(RequiresTm1WebFactAttribute.NoSourceMessage);
        await using var admin = new Tm1AdminServerContainer(source);
        await using var planning = new Tm1ServerContainer(source).WithEnv("TM1_SERVER_NAME", "planning").WithAdminServer(admin);
        await using var reporting = new Tm1ServerContainer(source).WithEnv("TM1_SERVER_NAME", "reporting").WithAdminServer(admin);
        await using var web = new Tm1WebContainer(webSource).WithAdminServer(admin);
        await planning.StartAsync();
        await reporting.StartAsync();
        await web.StartAsync();

        using var session = await Tm1WebSession.OpenAsync(web);
        var servers = await session.ServersAsync(web.AdminHost);
        Assert.Contains("planning", servers);
        Assert.Contains("reporting", servers);
    }

    /// <summary>The Admin Server's list of registered servers. TM1 servers register within seconds.</summary>
    private static async Task<string> ServersAsync(Tm1AdminServerContainer admin)
    {
        using var http = new HttpClient();
        var body = "";
        for (var i = 0; i < 30 && !(body.Contains("planning", StringComparison.Ordinal) && body.Contains("reporting", StringComparison.Ordinal)); i++)
        {
            await Task.Delay(1000);
            using var response = await http.GetAsync(new Uri(admin.RestUrl + "Servers?$select=Name"));
            body = await response.Content.ReadAsStringAsync();
        }
        return body;
    }
}

/// <summary>Java's SharedServerExampleTest: the everyday case, one shared TM1 server for all your
/// tests. Java's <c>@RequiresTm1</c> injects the shared container wherever a test declares a
/// <c>Tm1ServerContainer</c> parameter; here a class takes <see cref="Tm1ServerFixture"/>, or a
/// fixture of its own built on it for what's set up once per class (Java's static
/// <c>@BeforeAll</c>). Because the server is shared, give the objects you create unique names and
/// delete them afterwards.</summary>
public class SharedServerExampleTests(SharedServerExampleTests.Connection fixture) : IClassFixture<SharedServerExampleTests.Connection>
{
    [RequiresTm1Fact]
    public async Task UsesTheClientSetUpOnce() => Assert.Equal(0, await fixture.Tm1.ModelCubeCountAsync()); // a new, empty database

    [RequiresTm1Fact]
    public async Task GetsTheServerAsAParameter()
    {
        var injected = await Tm1ServerContainer.SharedAsync();
        Assert.Same(fixture.Tm1Server, injected); // always the same shared server
        Assert.EndsWith("/api/v1/", injected.RestUrl, StringComparison.Ordinal);
        Assert.Equal("admin", injected.Username);
        Assert.Empty(injected.Password);
    }

    /// <summary>The shared server, and something expensive set up once per class: a session with it.</summary>
    public sealed class Connection : Tm1ServerFixture
    {
        private Tm1Rest? tm1;

        public Tm1Rest Tm1 => tm1 ?? throw new InvalidOperationException(RequiresTm1FactAttribute.Tm1SkipReason() ?? "The TM1 server isn't started");

        public override async Task InitializeAsync()
        {
            await base.InitializeAsync();
            if (RequiresTm1FactAttribute.Tm1SkipReason() is null)
            {
                tm1 = new Tm1Rest(Tm1Server);
            }
        }

        public override async Task DisposeAsync()
        {
            if (tm1 is not null)
            {
                await tm1.DisposeAsync();
            }
            await base.DisposeAsync();
        }
    }
}

/// <summary>Java's Tm1WebExampleTest: TM1 Web against a TM1 server of its own, here with IBM's SData
/// sample, for tests that need data in TM1 Web or a server no other test touches.
/// <c>WithTm1Server</c> gives the TM1 server a Docker network shared with TM1 Web, and starting TM1
/// Web starts the server first.</summary>
[Collection(OwnContainers.Name)]
public class Tm1WebExampleTests(Tm1WebFixture fixture) : IClassFixture<Tm1WebFixture>
{
    [RequiresTm1WebFact]
    public async Task LogsOnToItsOwnServerThroughTm1Web()
    {
        // Same sources as the shared containers; pass your own Tm1Source/Tm1WebSource instead
        await using var tm1 = new Tm1ServerContainer(fixture.Tm1Server.Source).WithSeed(Tm1Seed.Sdata);
        await using var web = new Tm1WebContainer(fixture.Tm1Web.Source).WithTm1Server(tm1);
        await web.StartAsync();

        // TM1 Web drives a browser; Tm1WebSession stands in for one here
        using var session = await Tm1WebSession.OpenAsync(web);
        Assert.Equal(["tm1"], await session.ServersAsync(web.AdminHost));
        var tm1SessionId = await session.LoginAsync(web.AdminHost, "tm1", tm1.Username, tm1.Password);
        Assert.False(string.IsNullOrWhiteSpace(tm1SessionId));

        // What a universal report does: evaluate MDX through TM1 Web's evalservice
        var info = await tm1.InspectAsync();
        var serviceRoot = $"http://{info.Config!.Hostname}:{Tm1ServerContainer.RestPort}";
        var result = await session.EvaluateMdxAsync(
            serviceRoot,
            "tm1",
            tm1SessionId,
            "SELECT {[Model].[Model].Members} ON 0 FROM [SalesCube] WHERE ([Actvsbud].[Actual], [Region].[World], [Account1].[Sales], [Month].[Year])"
        );
        Assert.Contains("\"success\":true", result, StringComparison.Ordinal);
    }
}

/// <summary>Java's TwoServersExampleTest: two TM1 servers at once, for code that works across servers.
/// Each container is an independent TM1 server with its own Admin Server, on its own mapped ports.</summary>
[Collection(OwnContainers.Name)]
public class TwoServersExampleTests(Tm1ServerFixture fixture) : IClassFixture<Tm1ServerFixture>
{
    [RequiresTm1Fact]
    public async Task ComparesASourceAndATarget()
    {
        // The shared, empty server is the target. Start a second one, with data, as the source.
        var target = fixture.Tm1Server;
        await using var source = new Tm1ServerContainer(target.Source).WithSeed(Tm1Seed.Sdata);
        await source.StartAsync();

        await using var from = new Tm1Rest(source);
        await using var to = new Tm1Rest(target);
        Assert.NotEqual(target.RestUrl, source.RestUrl);
        Assert.True(await from.ModelCubeCountAsync() > 0);
        Assert.Equal(0, await to.ModelCubeCountAsync());
        // ...then run your migration from source to target and compare again
    }
}

/// <summary>Java's WithoutTheExtensionExampleTest: without <c>[RequiresTm1Fact]</c> or a fixture, for
/// full control of the container's lifetime, or a test that damages its server: a fresh server of
/// its own, thrown away at the end.</summary>
[Collection(OwnContainers.Name)]
public class WithoutTheExtensionExampleTests
{
    [NeedsDockerAndATm1SourceFact]
    public async Task StartsAThrowawayServer()
    {
        var source = Tm1Source.Discover() ?? throw new InvalidOperationException("needs a TM1 source");

        await using var tm1 = new Tm1ServerContainer(source);
        await tm1.StartAsync();
        await using var rest = new Tm1Rest(tm1);
        Assert.Equal(tm1.Tm1Version.ToString(), await rest.GetAsync("Configuration/ProductVersion/$value"));
        // the container, and anything the test did to it, is removed when the method ends
    }

    /// <summary>Java's two assumeTrue, on Docker and on a source, as a plain <c>[Fact]</c>'s Skip: a
    /// source set wrong runs the test, to fail on it.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class NeedsDockerAndATm1SourceFactAttribute : FactAttribute
    {
        public NeedsDockerAndATm1SourceFactAttribute()
        {
            Skip =
                !DockerHost.Available() ? "needs Docker"
                : !HasSource() ? "needs a TM1 source"
                : null;
        }

        private static bool HasSource()
        {
            try
            {
                return Tm1Source.Discover() is not null;
            }
            catch (ArgumentException)
            {
                return true;
            }
        }
    }
}

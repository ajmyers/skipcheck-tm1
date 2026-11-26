// SPDX-License-Identifier: Apache-2.0

using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers.Xunit;
using Xunit.Abstractions;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>Java's Tm1EngineSourceTest.</summary>
public class Tm1EngineSourceTests : TempDir
{
    private const string Package = "tm1-v12.6.7-standalone-linux.tgz";

    [Fact]
    public void FindsNothingWhenNothingIsSet()
    {
        Assert.Null(Tm1EngineSource.Discover(Settings([])));
        Assert.Null(Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.HomeDir] = "  " })));
    }

    [Fact]
    public void TriesSourcesInTheImagesOrder()
    {
        var install = FakeInstall();
        Assert.IsType<Tm1EngineSource.PrivateImage>(
            Tm1EngineSource.Discover(
                Settings(
                    new() { [Tm1EngineSource.PrivateImageSetting] = "registry.example.com/private/tm1-engine:12.6.7", [Tm1EngineSource.HomeDir] = install }
                )
            )
        );
        Assert.Equal(
            new Tm1EngineSource.InstallDir(install),
            Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.HomeDir] = install, [Tm1EngineSource.InstallVolumeSetting] = "v" }))
        );
        Assert.Equal(
            new Tm1EngineSource.InstallVolume("v"),
            Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.InstallVolumeSetting] = "v", [Tm1EngineSource.BundleDir] = Temp }))
        );
        Assert.Equal(
            new Tm1EngineSource.Bundle(Temp, null),
            Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.BundleDir] = Temp, [Tm1EngineSource.BundleUrlSetting] = "https://example.com/p.tgz" }))
        );
        Assert.Equal(
            new Tm1EngineSource.BundleUrl(new Uri("https://example.com/p.tgz"), "abc"),
            Tm1EngineSource.Discover(
                Settings(new() { [Tm1EngineSource.BundleUrlSetting] = "https://example.com/p.tgz", [Tm1EngineSource.BundleSha256] = "abc" })
            )
        );
    }

    [Fact]
    public void UsesTheTm1BundleFolderWhenItHoldsTm1V12()
    {
        // TM1 11's folder without a v12 package in it: no source, so the tests are skipped
        Touch("tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz");
        Assert.Null(Tm1EngineSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp })));

        Touch(Package);
        Assert.Equal(new Tm1EngineSource.Bundle(Temp, null), Tm1EngineSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp })));
        // TM1_ENGINE_BUNDLE names a file within it
        Assert.Equal(
            new Tm1EngineSource.Bundle(Temp, Package),
            Tm1EngineSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp, [Tm1EngineSource.BundleSetting] = Package }))
        );
        // Its own folder setting wins
        var other = Directory.CreateDirectory(Path.Combine(Temp, "other")).FullName;
        Assert.Equal(
            new Tm1EngineSource.Bundle(other, null),
            Tm1EngineSource.Discover(Settings(new() { [Tm1Source.BundleDir] = Temp, [Tm1EngineSource.BundleDir] = other }))
        );
    }

    [Fact]
    public void FailsOnAWrongSettingRatherThanSkipping()
    {
        Assert.Contains(
            "no bin/tm1sd.exe",
            Assert.Throws<ArgumentException>(() => Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.HomeDir] = Temp }))).Message
        );
        Assert.Contains(
            "is not a folder",
            Assert
                .Throws<ArgumentException>(() => Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.BundleDir] = Path.Combine(Temp, "missing") })))
                .Message
        );
        Assert.Contains(
            "which isn't in",
            Assert
                .Throws<ArgumentException>(() =>
                    Tm1EngineSource.Discover(Settings(new() { [Tm1EngineSource.BundleDir] = Temp, [Tm1EngineSource.BundleSetting] = "missing.tgz" }))
                )
                .Message
        );
    }

    [Fact]
    public void DescribesAUrlWithoutItsSecrets()
    {
        var source = new Tm1EngineSource.BundleUrl(new Uri("https://user:token@downloads.example.com:8443/tm1/p.tgz?X-Amz-Signature=secret"), null);
        Assert.Equal("package URL https://downloads.example.com:8443/tm1/p.tgz", source.Describe());
    }

    private string FakeInstall()
    {
        Touch(Path.Combine("tm1-engine", "bin", "tm1sd.exe"));
        return Path.Combine(Temp, "tm1-engine");
    }
}

/// <summary>Java's Tm1EngineWiringTest. Wiring only: nothing here starts a container.</summary>
public class Tm1EngineWiringTests : TempDir
{
    private static readonly Tm1EngineSource Source = new Tm1EngineSource.PrivateImage("tm1-engine:test");

    [Fact]
    public void HasOneDatabaseUnlessSomeAreNamed()
    {
        var engine = new Tm1EngineContainer(Source);
        engine.Configure();
        Assert.Equal("tm1/tm1", engine.EnvMap["TM1_DATABASES"]);
        Assert.False(engine.EnvMap.ContainsKey("TM1_USERS"));
        Assert.Equal("tm1", engine.Database("tm1").Instance);

        var named = new Tm1EngineContainer(Source).WithDatabase("planning").WithDatabase("other", "sales");
        named.Configure();
        Assert.Equal("tm1/planning,other/sales", named.EnvMap["TM1_DATABASES"]);
        Assert.Equal("sales", named.Database("other", "sales").Name);
        Assert.Contains("No database tm1/tm1 was added", Assert.Throws<ArgumentException>(() => named.Database("tm1")).Message);
    }

    [Fact]
    public void PassesTheUsersAndTheirGroupsToTheImage()
    {
        var engine = new Tm1EngineContainer(Source).WithUser("analyst", "Analysts", "Planners").WithUser("viewer");
        engine.Configure();
        Assert.Equal("analyst:Analysts:Planners,viewer", engine.EnvMap["TM1_USERS"]);
    }

    [Fact]
    public void RefusesNamesTheImageCantTake()
    {
        var engine = new Tm1EngineContainer(Source);
        // TM1 wants at least three characters
        Assert.Throws<ArgumentException>(() => engine.WithDatabase("ab"));
        Assert.Throws<ArgumentException>(() => engine.WithDatabase("two words"));
        Assert.Throws<ArgumentException>(() => engine.WithDatabase("a/b", "sales"));
        // An instance's name needs no three characters, as a database's does
        Assert.Throws<ArgumentException>(() => engine.WithDatabase("i", "ab"));
        Assert.Equal("i", new Tm1EngineContainer(Source).WithDatabase("i", "sales").Database("i", "sales").Instance);
        Assert.Throws<ArgumentException>(() => engine.WithUser("two words"));
        Assert.Throws<ArgumentException>(() => engine.WithUser("analyst", "a:b"));
        engine.WithUser("analyst");
        Assert.Contains("already", Assert.Throws<ArgumentException>(() => engine.WithUser("analyst", "Again")).Message);
    }

    [Fact]
    public void RunsItsDatastoreOnItsOwnNetwork()
    {
        var engine = new Tm1EngineContainer(Source);
        engine.Configure();
        var datastore = engine.Datastore;
        Assert.Equal("mongo:8.0", datastore.DockerImageName);
        Assert.NotNull(engine.Network);
        Assert.Same(engine.Network, datastore.Network);
        Assert.Contains(Tm1EngineContainer.DatastoreAlias, datastore.NetworkAliases);
        Assert.Contains(datastore, engine.Dependencies);
        Assert.StartsWith("mongodb://", engine.EnvMap["TM1_DATASTORE_URL"], StringComparison.Ordinal);
        Assert.EndsWith("@tm1-datastore:27017", engine.EnvMap["TM1_DATASTORE_URL"], StringComparison.Ordinal);
        // Both are this library's, like every container it starts
        Assert.Equal("dotnet", ((IGenericContainer)engine).Labels["io.skipcheck.tm1.testcontainers"]);
        Assert.Equal("dotnet", ((IGenericContainer)datastore).Labels["io.skipcheck.tm1.testcontainers"]);
        // What the other languages start it with
        Assert.Equal("mongodb://root:tm1datastore@tm1-datastore:27017", engine.EnvMap["TM1_DATASTORE_URL"]);
        Assert.Equal("root", datastore.EnvMap["MONGO_INITDB_ROOT_USERNAME"]);
        Assert.Equal("tm1datastore", datastore.EnvMap["MONGO_INITDB_ROOT_PASSWORD"]);
        Assert.Equal(["--wiredTigerCacheSizeGB", "0.5"], datastore.CommandParts);
        Assert.Equal([Tm1EngineContainer.Port], engine.ExposedPorts);
    }

    [Fact]
    public void TakesOAuth2TokensFromAKeycloakOnItsNetwork()
    {
        var keycloak = new KeycloakContainer();
        var engine = new Tm1EngineContainer(Source).WithOAuth2(keycloak);
        // Keycloak's address on the network is what its tokens name, and what TM1 can reach
        Assert.Equal("http://keycloak:8080/realms/tm1", engine.EnvMap["TM1_OAUTH2_ISSUER_URL"]);
        Assert.Equal("tm1", engine.EnvMap["TM1_OAUTH2_AUDIENCE"]);
        Assert.Equal("preferred_username", engine.EnvMap["TM1_OAUTH2_ID_CLAIMS"]);
        // Which Keycloak says of itself, wherever a token is asked for from
        Assert.Equal("http://keycloak:8080", keycloak.EnvMap["KC_HOSTNAME"]);
        Assert.NotNull(keycloak.Network);
        Assert.Same(engine.Network, keycloak.Network);
        Assert.Same(engine.Datastore.Network, keycloak.Network);
        Assert.Contains(keycloak, engine.Dependencies);
        Assert.Contains(engine.Datastore, engine.Dependencies);
        Assert.Same(keycloak, engine.Keycloak);
        Assert.Null(new Tm1EngineContainer(Source).Keycloak);

        // Moved off the Keycloak's network, TM1 couldn't reach its issuer
        var datastoreNetwork = engine.Datastore.Network;
        Assert.Contains("set the network before WithOAuth2", Assert.Throws<InvalidOperationException>(() => engine.WithNetwork(Network.NewNetwork())).Message);
        // And nothing moved
        Assert.Same(keycloak.Network, engine.Network);
        Assert.Same(datastoreNetwork, engine.Datastore.Network);
        Assert.Same(engine, engine.WithNetwork(keycloak.Network));

        // A Keycloak that has a network already is joined there, the datastore too
        var network = Network.NewNetwork();
        var placed = new KeycloakContainer().WithNetwork(network);
        var joining = new Tm1EngineContainer(Source).WithOAuth2(placed);
        Assert.Same(network, joining.Network);
        Assert.Same(network, joining.Datastore.Network);
    }

    [Fact]
    public void KeycloakImportsTheRealmAsItIsWhenKeycloakStarts()
    {
        var keycloak = new KeycloakContainer().WithCommand("start-dev", "--features=token-exchange");
        var engine = new Tm1EngineContainer(Source).WithOAuth2(keycloak);
        // Added after WithOAuth2, and still in the realm: it's asked for at Keycloak's start
        engine.WithUser("analyst").WithLogin("analyst", "analystpw");
        keycloak.Configure();
        // The import is added to the command it has: a caller's own options stay
        Assert.Equal(["start-dev", "--features=token-exchange", "--import-realm"], keycloak.CommandParts);
        Assert.Equal(["/opt/keycloak/data/import/tm1-realm.json"], keycloak.RealmFiles().Keys);
        Assert.Equal(engine.Realm(), keycloak.RealmFiles().Values.First());
        Assert.Contains("\"analyst\"", keycloak.RealmFiles().Values.First(), StringComparison.Ordinal);
        // And it's what is written into the container
        Assert.Equal(engine.Realm(), Encoding.UTF8.GetString(keycloak.Contents["/opt/keycloak/data/import/tm1-realm.json"]));
        // Configured again, as a start after a test's look does: the import isn't said twice
        keycloak.Configure();
        Assert.Equal(["start-dev", "--features=token-exchange", "--import-realm"], keycloak.CommandParts);
        // Without a realm, Keycloak starts as it was
        var plain = new KeycloakContainer();
        plain.Configure();
        Assert.Equal(["start-dev"], plain.CommandParts);
    }

    [Fact]
    public void AnEngineHasOneKeycloakOfItsOwnThatItStarts()
    {
        var keycloak = new KeycloakContainer();
        var engine = new Tm1EngineContainer(Source).WithOAuth2(keycloak);
        // The same again is nothing new
        Assert.Single(engine.WithOAuth2(keycloak).Dependencies, d => ReferenceEquals(d, keycloak));
        // A second would start beside the first, under the same name on the network
        Assert.Contains(
            "already takes tokens from another Keycloak",
            Assert.Throws<InvalidOperationException>(() => engine.WithOAuth2(new KeycloakContainer())).Message
        );
        Assert.Same(keycloak, engine.Keycloak);
        // And another engine's realm would replace this one's
        var second = new Tm1EngineContainer(Source);
        Assert.Contains("each engine needs a Keycloak of its own", Assert.Throws<InvalidOperationException>(() => second.WithOAuth2(keycloak)).Message);
        // Refused before anything of the second engine's changed
        Assert.Null(second.Keycloak);
        Assert.NotSame(keycloak.Network, second.Network);
        Assert.False(second.EnvMap.ContainsKey("TM1_OAUTH2_ISSUER_URL"));
        // An engine taken off its network gets one again, and the Keycloak with it
        var off = new Tm1EngineContainer(Source).WithNetwork(null).WithOAuth2(new KeycloakContainer());
        Assert.NotNull(off.Network);
        Assert.Same(off.Network, off.Keycloak!.Network);
        Assert.Same(off.Network, off.Datastore.Network);
        // A Keycloak moved since is found when the engine is configured, not by TM1
        keycloak.WithNetwork(Network.NewNetwork());
        Assert.Contains("set the network before WithOAuth2", Assert.Throws<InvalidOperationException>(engine.Configure).Message);
    }

    [Fact]
    public async Task AStartThatFailsLeavesItsKeycloakWhereItIs()
    {
        // The Keycloak is the caller's: a start that failed neither stops it nor takes away the network
        // it's on, which the engine made
        var keycloak = new KeycloakContainer();
        var engine = new FailsToStart(Source);
        engine.WithOAuth2(keycloak);
        var shared = engine.Network;
        Assert.Same(shared, keycloak.Network);
        Assert.Equal("can't start", (await Assert.ThrowsAsync<InvalidOperationException>(() => engine.StartAsync())).Message);
        Assert.Same(shared, engine.Made);
        Assert.Same(shared, engine.Network);
        Assert.Same(shared, keycloak.Network);
        Assert.Same(shared, engine.Datastore.Network);
        Assert.Same(keycloak, engine.Keycloak);
        // The engine's own parts are stopped all the same
        Assert.Equal(1, engine.Stopped);
        Assert.False(engine.Datastore.IsRunning);

        // A Keycloak moved since WithOAuth2 fails the start, with why, before anything starts
        var moved = new Tm1EngineContainer(Source).WithOAuth2(new KeycloakContainer());
        moved.Keycloak!.WithNetwork(Network.NewNetwork());
        Assert.Contains("set the network before WithOAuth2", (await Assert.ThrowsAsync<InvalidOperationException>(() => moved.StartAsync())).Message);
        Assert.False(moved.Keycloak.IsRunning);
        Assert.False(moved.Datastore.IsRunning);
    }

    [Fact]
    public void GivesKeycloakARealmWithTheUsersWhoHaveAPassword()
    {
        var keycloak = new KeycloakContainer();
        var engine = new Tm1EngineContainer(Source)
            .WithOAuth2(keycloak)
            // After WithOAuth2: the realm is asked for when Keycloak starts
            .WithUser("analyst", "Analysts")
            .WithUser("viewer")
            .WithLogin("analyst", "an$pw")
            .WithLogin("Admin", "adminpw");
        using var document = JsonDocument.Parse(engine.Realm());
        var realm = document.RootElement;
        Assert.Equal("tm1", realm.GetProperty("realm").GetString());
        Assert.True(realm.GetProperty("enabled").GetBoolean());
        var client = realm.GetProperty("clients")[0];
        Assert.Equal("tm1", client.GetProperty("clientId").GetString());
        Assert.True(client.GetProperty("publicClient").GetBoolean());
        Assert.True(client.GetProperty("directAccessGrantsEnabled").GetBoolean());
        Assert.False(client.GetProperty("standardFlowEnabled").GetBoolean());
        var mapper = client.GetProperty("protocolMappers")[0];
        Assert.Equal("oidc-audience-mapper", mapper.GetProperty("protocolMapper").GetString());
        Assert.Equal("tm1", mapper.GetProperty("config").GetProperty("included.custom.audience").GetString());
        // viewer has no password, so nothing to log in to Keycloak with; admin is always a user
        var users = realm.GetProperty("users");
        Assert.Equal(2, users.GetArrayLength());
        Assert.Equal("analyst", users[0].GetProperty("username").GetString());
        Assert.Equal("an$pw", users[0].GetProperty("credentials")[0].GetProperty("value").GetString());
        Assert.Equal("user1@example.com", users[0].GetProperty("email").GetString());
        Assert.Equal("Admin", users[1].GetProperty("username").GetString());
        // The whole of it, as the other languages write it (quotes written ' here)
        var whole =
            "{'realm':'tm1','enabled':true,'clients':[{'clientId':'tm1','publicClient':true,'directAccessGrantsEnabled':true,'standardFlowEnabled':false,"
            + "'protocolMappers':[{'name':'tm1-audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper',"
            + "'config':{'included.custom.audience':'tm1','access.token.claim':'true','id.token.claim':'false'}}]}],"
            + "'users':[{'username':'analyst','enabled':true,'emailVerified':true,'email':'user1@example.com','firstName':'analyst','lastName':'Test',"
            + "'credentials':[{'type':'password','value':'an$pw','temporary':false}]},"
            + "{'username':'Admin','enabled':true,'emailVerified':true,'email':'user2@example.com','firstName':'Admin','lastName':'Test',"
            + "'credentials':[{'type':'password','value':'adminpw','temporary':false}]}]}";
        Assert.Equal(whole.Replace('\'', '"'), JsonSerializer.Serialize(realm));
        // TM1 gets names and groups only
        engine.Configure();
        Assert.Equal("analyst:Analysts,viewer", engine.EnvMap["TM1_USERS"]);

        // Keycloak imports it as it starts, from a file made then: nothing is written before
        Assert.Equal(["start-dev"], keycloak.CommandParts);
        Assert.Empty(keycloak.Contents);
        engine.WithUser("late").WithLogin("late", "latepw");
        keycloak.Configure();
        Assert.Equal(["start-dev", "--import-realm"], keycloak.CommandParts);
        Assert.Equal(["/opt/keycloak/data/import/tm1-realm.json"], keycloak.Contents.Keys);
        Assert.Equal(engine.Realm(), Encoding.UTF8.GetString(keycloak.Contents["/opt/keycloak/data/import/tm1-realm.json"]));
        Assert.Contains("\"late\"", engine.Realm(), StringComparison.Ordinal);
        // A Keycloak without a realm to import starts as it did
        var plain = new KeycloakContainer();
        plain.Configure();
        Assert.Equal(["start-dev"], plain.CommandParts);
        Assert.Empty(plain.Contents);
    }

    [Fact]
    public async Task RefusesAnOAuth2LoginItCantGive()
    {
        var engine = new Tm1EngineContainer(Source).WithUser("analyst").WithLogin("analyst", "pw");
        var database = engine.Database("tm1");
        Assert.Contains("give it a Keycloak with WithOAuth2", Assert.Throws<InvalidOperationException>(() => database.OAuth2Login("analyst")).Message);
        engine.WithOAuth2(new KeycloakContainer());
        Assert.Contains("viewer has no password to log in with", Assert.Throws<ArgumentException>(() => database.OAuth2Login("viewer")).Message);
        // A name is one whatever its case
        Assert.Equal("analyst", database.OAuth2Login("ANALYST").User);
        // And a second password for it replaces the first
        engine.WithLogin("Analyst", "second");
        Assert.Equal("second", database.OAuth2Login("analyst").Password);
        using (var realm = JsonDocument.Parse(engine.Realm()))
        {
            Assert.Equal(1, realm.RootElement.GetProperty("users").GetArrayLength());
        }
        engine.WithLogin("analyst", "pw");
        Assert.True(database.OAuth2Login("analyst").IsOAuth2);
        Assert.Equal("pw", database.OAuth2Login("analyst").Password);
        Assert.Equal("tm1", database.OAuth2Login("analyst").ClientId);
        Assert.Equal("", database.OAuth2Login("analyst").ClientSecret);
        Assert.Equal("tm1", database.OAuth2Login("analyst").Instance);
        Assert.Throws<ArgumentException>(() => engine.WithLogin("analyst", ""));
        Assert.Throws<ArgumentException>(() => engine.WithLogin("two words", "pw"));
        // A password for someone who isn't a user is a mistake, found when the realm is made
        var stray = new Tm1EngineContainer(Source).WithLogin("nobody", "pw");
        Assert.Contains("isn't a user of the engine", Assert.Throws<InvalidOperationException>(() => stray.Realm()).Message);
        // An OAuth 2.0 login has no session endpoint to ask, and that needs no engine to say
        var refused = await Assert.ThrowsAsync<InvalidOperationException>(() => database.OAuth2Login("analyst").SessionAsync("Bearer x"));
        Assert.Contains("An OAuth 2.0 login has no session to ask for", refused.Message);
        Assert.Contains("(AuthorizeWithTokenAsync)", refused.Message);
    }

    [Fact]
    public void ItsDatastoreIsOnItsNetworkBeforeAnythingStarts()
    {
        // Not only once the engine starts or is configured: an environment may start what the
        // engine depends on first, and TM1 couldn't reach a datastore left off the network
        var engine = new Tm1EngineContainer(Source);
        Assert.NotNull(engine.Network);
        Assert.Same(engine.Network, engine.Datastore.Network);
        var network = Network.NewNetwork();
        engine.WithNetwork(network);
        Assert.Same(network, engine.Datastore.Network);
    }

    [Fact]
    public void JoinsANetworkItIsGiven()
    {
        var network = Network.NewNetwork();
        var engine = new Tm1EngineContainer(Source).WithNetwork(network);
        engine.Configure();
        Assert.Same(network, engine.Network);
        Assert.Same(network, engine.Datastore.Network);
    }

    [Fact]
    public void JoinsItsDatastoreToTheNetworkBeforeItStarts()
    {
        // What it depends on starts before Configure(), so the datastore has to be on the network
        // by then: TM1 couldn't reach its MongoDB otherwise
        var engine = new Starting(Source);
        // Taken off its network, it gets one again as it starts
        engine.WithNetwork(null);
        Assert.Null(engine.Datastore.Network);
        engine.BeforeItsDependenciesStart();
        Assert.NotNull(engine.Network);
        Assert.Same(engine.Network, engine.Datastore.Network);

        var network = Network.NewNetwork();
        var given = new Starting(Source);
        given.WithNetwork(network);
        given.BeforeItsDependenciesStart();
        Assert.Same(network, given.Datastore.Network);
    }

    private sealed class Starting(Tm1EngineSource source) : Tm1EngineContainer(source)
    {
        public void BeforeItsDependenciesStart() => BeforeDependenciesStart();
    }

    [Fact]
    public async Task AStartThatFailsLeavesNothingBehind()
    {
        // Fails before anything reaches Docker, with the engine and its datastore already on the
        // network this made for them
        var engine = new FailsToStart(Source);
        Assert.Equal("can't start", (await Assert.ThrowsAsync<InvalidOperationException>(() => engine.StartAsync())).Message);
        // The network it made is gone, and it has another for the next start, its datastore with it
        Assert.NotNull(engine.Made);
        Assert.NotNull(engine.Network);
        Assert.NotSame(engine.Made, engine.Network);
        Assert.Same(engine.Network, engine.Datastore.Network);
        Assert.Equal(1, engine.Stopped);
        Assert.False(engine.IsRunning);
        Assert.False(engine.Datastore.IsRunning);

        // A network it was given is the caller's to remove, and stays
        var network = Network.NewNetwork();
        var given = new FailsToStart(Source);
        given.WithNetwork(network);
        await Assert.ThrowsAsync<InvalidOperationException>(() => given.StartAsync());
        Assert.Same(network, given.Network);
        Assert.Same(network, given.Datastore.Network);
        Assert.Equal(1, given.Stopped);
    }

    private sealed class FailsToStart(Tm1EngineSource source) : Tm1EngineContainer(source)
    {
        public Network? Made { get; private set; }

        public int Stopped { get; private set; }

        private protected override void BeforeDependenciesStart()
        {
            base.BeforeDependenciesStart();
            Made = Network;
            throw new InvalidOperationException("can't start");
        }

        private protected override async Task StoppedAsync(System.Threading.CancellationToken cancellationToken)
        {
            await base.StoppedAsync(cancellationToken);
            Stopped++;
        }
    }

    [Fact]
    public void LogsInThroughTheFirstApplicationWhoseSecretItHolds()
    {
        var credentials = Tm1EngineContainer.Credentials.Parse(
            """
            {"root":{"clientId":"root","clientSecret":"rootsecret"},"instances":[
              {"name":"tm1","applications":[{"name":"lost","clientId":"a","clientSecret":null},{"name":"tm1","clientId":"b","clientSecret":"s"},
                {"name":"later","clientId":"c","clientSecret":"t"}],"databases":[{"name":"tm1"}]},
              {"name":"other","applications":[{"name":"lost","clientId":"d","clientSecret":null},{"name":"none","clientId":"e"}],"databases":[]}]}
            """
        );
        Assert.Equal(("b", "s"), credentials.Application("tm1"));
        // None with a secret, or no such instance: Login then says there's no application to log in through
        Assert.Null(credentials.Application("other"));
        Assert.Null(credentials.Application("missing"));
    }

    [Fact]
    public void MountsEachSourceWhereTheImageLooks()
    {
        Touch(Path.Combine("install", "bin", "tm1sd.exe"));
        var install = Path.Combine(Temp, "install");
        Assert.Equal([$"{install}:/opt/ibm/tm1-engine:ro"], new Tm1EngineContainer(new Tm1EngineSource.InstallDir(install)).Binds);
        Assert.Equal(["tm1-engine:/opt/ibm/tm1-engine"], new Tm1EngineContainer(new Tm1EngineSource.InstallVolume("tm1-engine")).Binds);

        Touch("tm1-v12.6.7-standalone-linux.tgz");
        var bundle = new Tm1EngineContainer(new Tm1EngineSource.Bundle(Temp, "tm1-v12.6.7-standalone-linux.tgz"));
        Assert.Equal([$"{Temp}:/ibm:ro", "tm1-engine-testcontainers-cache:/tm1/cache"], bundle.Binds);
        Assert.Equal("tm1-v12.6.7-standalone-linux.tgz", bundle.EnvMap["TM1_ENGINE_BUNDLE"]);

        var url = new Tm1EngineContainer(new Tm1EngineSource.BundleUrl(new Uri("https://example.com/p.tgz"), "abc"));
        Assert.Equal(["tm1-engine-testcontainers-cache:/tm1/cache"], url.Binds);
        Assert.Equal("https://example.com/p.tgz", url.EnvMap["TM1_ENGINE_BUNDLE_URL"]);
        Assert.Equal("abc", url.EnvMap["TM1_ENGINE_BUNDLE_SHA256"]);

        Assert.Empty(new Tm1EngineContainer(Source).Binds);
        Assert.Equal("tm1-engine:test", new Tm1EngineContainer(Source).DockerImageName);
        // Unless the machine names another image for its own runs (TM1_ENGINE_IMAGE)
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(Tm1EngineContainer.EngineImage)))
        {
            Assert.StartsWith(Tm1EngineContainer.DefaultImage + ":", new Tm1EngineContainer(new Tm1EngineSource.InstallVolume("v")).DockerImageName);
        }
    }

    [Fact]
    public void NamesAClassesDatabaseAfterTheClass()
    {
        Assert.Equal("cubetest_k3f9", Tm1EngineFixture.DatabaseName("CubeTest", "k3f9"));
        // A short suffix is padded, so the name is always long enough for TM1
        Assert.Equal("t_0007", Tm1EngineFixture.DatabaseName("T", "7"));
        Assert.Equal("test_00ab", Tm1EngineFixture.DatabaseName("$$", "ab"));
        Assert.Equal("averylongtestclassnameth_zzzz", Tm1EngineFixture.DatabaseName("AVeryLongTestClassNameThatGoesOnAndOn", "zzzz"));
        // The random characters are a name the image takes too
        Assert.Matches("^cubetest_[0-9a-z]{4}$", Tm1EngineFixture.DatabaseName("CubeTest"));
    }
}

/// <summary>Java's Tm1EngineContainerTest: the engine against real TM1 v12, its databases and the
/// service-to-service logins. One engine for the class, with two databases in the default instance
/// and one in another. Skipped without Docker or a TM1 v12 source.</summary>
[Collection(OwnContainers.Name)]
public class Tm1EngineContainerTests(Tm1EngineContainerTests.OwnEngine own, ITestOutputHelper output) : IClassFixture<Tm1EngineContainerTests.OwnEngine>
{
    private Tm1EngineContainer Engine => own.Engine;

    private Tm1Database Planning => own.Engine.Database("planning");

    private Tm1Database Sales => own.Engine.Database("sales");

    private Tm1Database Elsewhere => own.Engine.Database("other", "elsewhere");

    [RequiresTm1EngineFact]
    public async Task StartsWithTheDatabasesItWasGiven()
    {
        var databases = await Engine.DatabasesAsync();
        Assert.Equal(3, databases.Count);
        Assert.Contains(Planning, databases);
        Assert.Contains(Sales, databases);
        Assert.Contains(Elsewhere, databases);
        Assert.Equal(Engine.Url + "tm1/api/v1/Databases('planning')/", Planning.RestUrl);
        Assert.Equal(Engine.Url + "other/api/v1/Databases('elsewhere')/", Elsewhere.RestUrl);
        Assert.True(Engine.Tm1Version.IsAtLeast(Tm1Version.Parse("12")));
        Assert.Contains("No database tm1/missing in this engine", Assert.Throws<ArgumentException>(() => Engine.Database("missing")).Message);
    }

    [RequiresTm1EngineFact]
    public async Task LogsInWithTheApplicationsCredentialsAsBasic()
    {
        var admin = Planning.Login();
        Assert.StartsWith("TM1SessionId=", await admin.CookieAsync(), StringComparison.Ordinal);
        // The one session serves every database of the instance
        foreach (var database in new[] { Planning, Sales })
        {
            var (status, body) = await Tm1EngineRest.GetAsync(database, admin, "ActiveUser?$select=Name,Type");
            Assert.Equal(HttpStatusCode.OK, status);
            Assert.Contains("\"Type\":\"Admin\"", body, StringComparison.Ordinal);
        }
    }

    [RequiresTm1EngineFact]
    public async Task LogsInWithABearerToken()
    {
        var analyst = Planning.Login("analyst");
        var token = await analyst.TokenAsync();
        Assert.Equal(3, token.Split('.').Length);

        var cookie = await analyst.SessionAsync("Bearer " + token);
        using var request = new HttpRequestMessage(HttpMethod.Get, Planning.RestUrl + "ActiveUser?$select=Name");
        request.Headers.TryAddWithoutValidation("Cookie", cookie);
        var (status, body) = await Tm1EngineRest.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Name\":\"analyst\"", body, StringComparison.OrdinalIgnoreCase);
    }

    [RequiresTm1EngineFact]
    public async Task AUserTm1DoesntKnowGetsASessionAndIsThenRefused()
    {
        var nobody = Planning.Login("nobody");
        Assert.StartsWith("TM1SessionId=", await nobody.CookieAsync(), StringComparison.Ordinal);
        Assert.Equal(HttpStatusCode.Forbidden, (await Tm1EngineRest.GetAsync(Planning, nobody, "ActiveUser")).Status);
    }

    [RequiresTm1EngineFact]
    public async Task AUserMissingFromOneDatabaseIsRefusedThereOnly()
    {
        // Users belong to a database: take the analyst out of one
        Assert.Equal(HttpStatusCode.NoContent, (await Tm1EngineRest.DeleteAsync(Sales, Sales.Login(), "Users('analyst')")).Status);

        var analyst = Planning.Login("analyst");
        Assert.Equal(HttpStatusCode.OK, (await Tm1EngineRest.GetAsync(Planning, analyst, "ActiveUser")).Status);
        Assert.Equal(HttpStatusCode.Forbidden, (await Tm1EngineRest.GetAsync(Sales, analyst, "ActiveUser")).Status);
    }

    [RequiresTm1EngineFact]
    public async Task AnotherInstancesCredentialsAreRefused()
    {
        // An application belongs to its instance: the other's can't log in here
        var other = Elsewhere.Login();
        Assert.Equal(HttpStatusCode.OK, (await Tm1EngineRest.GetAsync(Elsewhere, other, "ActiveUser")).Status);

        using var request = new HttpRequestMessage(HttpMethod.Post, Planning.Login().AuthUrl + "session")
        {
            Content = new StringContent("{\"User\":\"admin\"}", Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation(
            "Authorization",
            "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(other.ClientId + ":" + other.ClientSecret))
        );
        Assert.Equal(HttpStatusCode.Unauthorized, (await Tm1EngineRest.SendAsync(request)).Status);
    }

    [RequiresTm1EngineFact]
    public async Task CreatesAndDropsADatabase()
    {
        var creating = Stopwatch.StartNew();
        var made = await Engine.CreateDatabaseAsync("scratch");
        creating.Stop();
        Assert.Contains(made, await Engine.DatabasesAsync());
        Assert.Equal(made, Engine.Database("scratch"));
        // With the engine's users, like the databases it started with
        Assert.Equal(HttpStatusCode.OK, (await Tm1EngineRest.GetAsync(made, made.Login("analyst"), "ActiveUser")).Status);

        var dropping = Stopwatch.StartNew();
        await Engine.DropDatabaseAsync(made);
        dropping.Stop();
        Assert.DoesNotContain(made, await Engine.DatabasesAsync());
        output.WriteLine($"Created a database in {creating.Elapsed.TotalSeconds:F1} s and dropped it in {dropping.Elapsed.TotalSeconds:F1} s");
    }

    [RequiresTm1EngineFact]
    public async Task GivesTheRootCredentialsForTheManagementApi()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Engine.ManagementUrl + "Instances");
        request.Headers.TryAddWithoutValidation(
            "Authorization",
            "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(Engine.RootClientId + ":" + Engine.RootClientSecret))
        );
        var (status, body) = await Tm1EngineRest.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"tm1\"", body, StringComparison.Ordinal);
        Assert.Contains("\"other\"", body, StringComparison.Ordinal);
    }

    /// <summary>The class's engine, as Java's <c>@BeforeAll</c> and <c>@AfterAll</c>. Nothing starts
    /// when the tests are skipped.</summary>
    public sealed class OwnEngine : IAsyncLifetime
    {
        private Tm1EngineContainer? engine;

        public Tm1EngineContainer Engine =>
            engine ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.EngineSkipReason() ?? "The engine isn't started");

        public async Task InitializeAsync()
        {
            if (RequiresTm1EngineFactAttribute.EngineSkipReason() is not null)
            {
                return;
            }
            var source = Tm1EngineSource.Discover() ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);
            engine = new Tm1EngineContainer(source)
                .WithDatabase("planning")
                .WithDatabase("sales")
                .WithDatabase("other", "elsewhere")
                .WithUser("analyst", "Analysts");
            await engine.StartAsync();
        }

        public async Task DisposeAsync()
        {
            if (engine is not null)
            {
                await engine.DisposeAsync();
            }
        }
    }
}

/// <summary>Java's Tm1EngineOAuth2Test: OAuth 2.0 logins against real TM1 v12 and Keycloak. An engine of the
/// class's own, taking tokens from a Keycloak beside it. Skipped without Docker or a TM1 v12 source.</summary>
[Collection(OwnContainers.Name)]
public class Tm1EngineOAuth2Tests(Tm1EngineOAuth2Tests.OwnEngine own, ITestOutputHelper output) : IClassFixture<Tm1EngineOAuth2Tests.OwnEngine>
{
    private KeycloakContainer Keycloak => own.Keycloak;

    private Tm1Database Planning => own.Engine.Database("planning");

    [RequiresTm1EngineFact]
    public async Task AKeycloakTokenLogsTheUserIn()
    {
        var analyst = Planning.OAuth2Login("analyst");
        // The token on a request of its own: TM1 answers, and gives a session
        using var request = await analyst.AuthorizeWithTokenAsync(new HttpRequestMessage(HttpMethod.Get, Planning.RestUrl + "ActiveUser?$select=Name,Type"));
        var (status, body, cookies) = await Tm1EngineRest.SendForCookiesAsync(request);
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Name\":\"analyst\"", body, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("\"Type\":\"User\"", body, StringComparison.Ordinal);
        Assert.Contains(cookies, cookie => cookie.StartsWith("TM1SessionId=", StringComparison.Ordinal));
        // And the session that gives serves later requests
        Assert.StartsWith("TM1SessionId=", await analyst.CookieAsync(), StringComparison.Ordinal);
        Assert.Equal(HttpStatusCode.OK, (await Tm1EngineRest.GetAsync(Planning, analyst, "ActiveUser")).Status);
    }

    [RequiresTm1EngineFact]
    public async Task TheAdministratorLogsInThroughOAuth2Too()
    {
        var (status, body) = await Tm1EngineRest.GetAsync(Planning, Planning.OAuth2Login("admin"), "ActiveUser?$select=Name,Type");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Type\":\"Admin\"", body, StringComparison.Ordinal);
    }

    [RequiresTm1EngineFact]
    public async Task ATokenOfAnotherRealmIsRefused()
    {
        // Signed by the same Keycloak, but not by the issuer TM1 trusts, and without its audience
        var other = await Keycloak.AccessTokenAsync("master", "admin-cli", "admin", "admin");
        Assert.Equal(HttpStatusCode.Unauthorized, await ActiveUserAsync(other));
    }

    [RequiresTm1EngineFact]
    public async Task SomethingThatIsntATokenIsRefused() => Assert.Equal(HttpStatusCode.Unauthorized, await ActiveUserAsync("not.a.token"));

    [RequiresTm1EngineFact]
    public async Task AnExpiredTokenIsRefused()
    {
        // Tokens that live a second, for as long as it takes to get one
        var admin = await Keycloak.AccessTokenAsync("master", "admin-cli", "admin", "admin");
        await SetTokenLifeAsync(admin, 1);
        string token;
        try
        {
            token = await Planning.OAuth2Login("analyst").TokenAsync();
        }
        finally
        {
            await SetTokenLifeAsync(admin, 300);
        }
        // TM1 may allow a clock some slack: wait for it to refuse, for up to two minutes
        var waited = Stopwatch.StartNew();
        HttpStatusCode status = default;
        while (waited.Elapsed < TimeSpan.FromMinutes(2))
        {
            status = await ActiveUserAsync(token);
            if (status == HttpStatusCode.Unauthorized)
            {
                break;
            }
            await Task.Delay(TimeSpan.FromSeconds(2));
        }
        output.WriteLine($"TM1 refused the expired token after {waited.Elapsed.TotalSeconds:F0} s");
        Assert.Equal(HttpStatusCode.Unauthorized, status);
    }

    [RequiresTm1EngineFact]
    public async Task LoginsThroughTheApplicationStillWork()
    {
        var analyst = Planning.Login("analyst");
        Assert.False(analyst.IsOAuth2);
        Assert.Equal(HttpStatusCode.OK, (await Tm1EngineRest.GetAsync(Planning, analyst, "ActiveUser")).Status);
        // TM1's own token isn't one to send on a request
        using var request = new HttpRequestMessage(HttpMethod.Get, Planning.RestUrl + "ActiveUser");
        await Assert.ThrowsAsync<InvalidOperationException>(() => analyst.AuthorizeWithTokenAsync(request));
        // And an OAuth 2.0 login has no session endpoint to ask
        await Assert.ThrowsAsync<InvalidOperationException>(() => Planning.OAuth2Login("analyst").SessionAsync("Bearer x"));
    }

    [RequiresTm1EngineFact]
    public async Task TheLoginSaysWhereItsTokensComeFrom()
    {
        var analyst = Planning.OAuth2Login("analyst");
        Assert.Equal(Keycloak.Url + "/realms/tm1/protocol/openid-connect/token", analyst.TokenUrl);
        Assert.Equal("tm1", analyst.ClientId);
        Assert.Equal("analyst$pw", analyst.Password);
        Assert.Equal(3, (await analyst.TokenAsync()).Split('.').Length);
    }

    /// <summary>What TM1 answers a request that carries <paramref name="token"/> as its Bearer.</summary>
    private async Task<HttpStatusCode> ActiveUserAsync(string token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Planning.RestUrl + "ActiveUser");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
        return (await Tm1EngineRest.SendAsync(request)).Status;
    }

    /// <summary>Sets how long the realm's access tokens live, through Keycloak's admin API.</summary>
    private async Task SetTokenLifeAsync(string adminToken, int seconds)
    {
        using var request = new HttpRequestMessage(HttpMethod.Put, Keycloak.Url + "/admin/realms/tm1")
        {
            Content = new StringContent(
                "{\"accessTokenLifespan\":" + seconds.ToString(System.Globalization.CultureInfo.InvariantCulture) + "}",
                Encoding.UTF8,
                "application/json"
            ),
        };
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + adminToken);
        Assert.Equal(HttpStatusCode.NoContent, (await Tm1EngineRest.SendAsync(request)).Status);
    }

    /// <summary>The class's engine and its Keycloak, as Java's <c>@BeforeAll</c> and <c>@AfterAll</c>. Nothing starts
    /// when the tests are skipped.</summary>
    public sealed class OwnEngine : IAsyncLifetime
    {
        private KeycloakContainer? keycloak;
        private Tm1EngineContainer? engine;

        public Tm1EngineContainer Engine =>
            engine ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.EngineSkipReason() ?? "The engine isn't started");

        public KeycloakContainer Keycloak =>
            keycloak ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.EngineSkipReason() ?? "Keycloak isn't started");

        public async Task InitializeAsync()
        {
            if (RequiresTm1EngineFactAttribute.EngineSkipReason() is not null)
            {
                return;
            }
            var source = Tm1EngineSource.Discover() ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);
            keycloak = new KeycloakContainer();
            engine = new Tm1EngineContainer(source)
                .WithDatabase("planning")
                .WithUser("analyst", "Analysts")
                .WithLogin("analyst", "analyst$pw")
                .WithLogin("admin", "adminpw")
                .WithOAuth2(keycloak);
            await engine.StartAsync();
        }

        public async Task DisposeAsync()
        {
            if (engine is not null)
            {
                await engine.DisposeAsync();
            }
            // The Keycloak is the caller's to stop: the engine only started it first
            if (keycloak is not null)
            {
                await keycloak.DisposeAsync();
            }
        }
    }
}

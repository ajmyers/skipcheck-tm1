// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>Java's AdminServerWiringTest.</summary>
public class AdminServerWiringTests
{
    private readonly Tm1Source source = new Tm1Source.PrivateImage("tm1-server:test");

    [Fact]
    public void ServersRegisterWithTheAdminServerOnItsNetwork()
    {
        var admin = new Tm1AdminServerContainer(source);
        var planning = new Tm1ServerContainer(source).WithAdminServer(admin);
        var reporting = new Tm1ServerContainer(source).WithAdminServer(admin);
        Assert.Equal(["admin"], admin.CommandParts);
        Assert.Contains(Tm1AdminServerContainer.NetworkAlias, admin.NetworkAliases);
        Assert.Equal(Tm1AdminServerContainer.NetworkAlias, planning.EnvMap["TM1_ADMIN_HOST"]);
        Assert.NotNull(planning.Network);
        Assert.Same(admin.Network, planning.Network);
        Assert.Same(reporting.Network, planning.Network);
        Assert.Contains(admin, planning.Dependencies);
        Assert.Same(admin, planning.AdminServer);
    }

    [Fact]
    public void Tm1WebListsServersFromTheAdminServer()
    {
        var admin = new Tm1AdminServerContainer(source);
        var web = new Tm1WebContainer(new Tm1WebSource.PrivateImage("tm1-web:test")).WithAdminServer(admin);
        web.Configure();
        Assert.Equal(Tm1AdminServerContainer.NetworkAlias, web.EnvMap["TM1_ADMIN_HOST"]);
        Assert.Same(admin.Network, web.Network);
        Assert.Same(admin, web.AdminServer);
    }

    [Fact]
    public void JoinsANetworkEitherSideAlreadyHas()
    {
        var network = Network.NewNetwork();
        var admin = new Tm1AdminServerContainer(source);
        var tm1 = new Tm1ServerContainer(source).WithNetwork(network).WithAdminServer(admin);
        Assert.Same(network, admin.Network);
        Assert.Same(network, tm1.Network);
    }

    [Fact]
    public void RefusesContainersOnDifferentNetworks()
    {
        var admin = new Tm1AdminServerContainer(source).WithNetwork(Network.NewNetwork());
        var tm1 = new Tm1ServerContainer(source).WithNetwork(Network.NewNetwork());
        Assert.Contains("share one Network", Assert.Throws<InvalidOperationException>(() => tm1.WithAdminServer(admin)).Message);
    }

    [Fact]
    public void Tm1WebNeedsItsTm1ServerRunning()
    {
        var tm1 = new Tm1ServerContainer(source);
        var web = new Tm1WebContainer(new Tm1WebSource.PrivateImage("tm1-web:test")).WithTm1Server(tm1);
        Assert.NotNull(tm1.Network);
        Assert.Contains(tm1, web.Dependencies);
        Assert.Contains("isn't running", Assert.Throws<InvalidOperationException>(web.Configure).Message);
        Assert.Contains("WithTm1Server", Assert.Throws<InvalidOperationException>(() => web.AdminHost).Message);
    }
}

/// <summary>Java's CognosContainerTest.</summary>
public class CognosContainerTests
{
    private readonly CognosContainer cognos = new CognosContainer(new CognosSource.PrivateImage("cognos-server:test"))
        .WithUser("admin", "adminpw", "Admins")
        .WithUser("analyst", "analyst#pw", "Analysts", "Readers")
        .WithUser("viewer", "viewerpw", "Readers");

    [Fact]
    public void PassesTheUsersToTheImage()
    {
        Assert.Equal("admin:adminpw:Admins analyst:analyst#pw:Analysts,Readers viewer:viewerpw:Readers", cognos.UsersSpec());
        Assert.Equal(["Admins", "Analysts", "Readers"], cognos.Groups);
        cognos.Configure();
        Assert.Equal(cognos.UsersSpec(), cognos.EnvMap["COGNOS_USERS"]);
    }

    [Fact]
    public void BuildsCamLogons()
    {
        var auth = cognos.CamAuthorization("analyst");
        Assert.StartsWith("CAMNamespace ", auth, StringComparison.Ordinal);
        Assert.Equal("analyst:analyst#pw:LDAP", Encoding.UTF8.GetString(Convert.FromBase64String(auth["CAMNamespace ".Length..])));
        Assert.Contains("nobody", Assert.Throws<ArgumentException>(() => cognos.CamAuthorization("nobody")).Message);
    }

    [Fact]
    public void RefusesUsersTheImageCantTakeApart()
    {
        Assert.Throws<ArgumentException>(() => cognos.WithUser("two words", "pw"));
        Assert.Throws<ArgumentException>(() => cognos.WithUser("x", "pa:ss"));
        Assert.Throws<ArgumentException>(() => cognos.WithUser("x", "pa ss"));
        Assert.Throws<ArgumentException>(() => cognos.WithUser("x", "pw", "Bad Group"));
        Assert.Contains("already", Assert.Throws<ArgumentException>(() => cognos.WithUser("viewer", "again")).Message);
    }

    [Fact]
    public void Tm1InCamSecurityLogsOnAsTheFirstAdmin()
    {
        var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test")).WithCamSecurity(cognos, "admin");
        tm1.Configure();
        Assert.Equal("admin", tm1.Username);
        Assert.Equal(cognos.CamAuthorization("admin"), tm1.Authorization);
        Assert.Equal("http://cognos:9300", tm1.EnvMap["TM1_CAM_URI"]);
        Assert.Equal("admin", tm1.EnvMap["TM1_CAM_ADMINS"]);
        Assert.Equal("Admins Analysts Readers", tm1.EnvMap["TM1_CAM_GROUPS"]);
        Assert.Same(cognos.Network, tm1.Network);
        Assert.Same(cognos, tm1.Cognos);
    }

    [Fact]
    public void Tm1ThroughOidcUsesTheOidcNamespace()
    {
        var keycloak = new KeycloakContainer();
        var withOidc = new CognosContainer(new CognosSource.PrivateImage("cognos-server:test")).WithUser("admin", "adminpw", "Admins").WithKeycloak(keycloak);
        Assert.Equal("http://keycloak:8080", withOidc.EnvMap["COGNOS_KEYCLOAK_URL"]);
        Assert.Same(keycloak.Network, withOidc.Network);
        var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test")).WithCamSecurity(withOidc, CognosContainer.Namespace.Oidc, "admin");
        tm1.Configure();
        Assert.Equal("OIDC", tm1.EnvMap["TM1_CAM_NAMESPACE"]);
        Assert.Equal("oidc", tm1.EnvMap["TM1_CAM_NAMESPACE_TYPE"]);
        Assert.Equal(withOidc.CamAuthorization("admin", CognosContainer.Namespace.Oidc), tm1.Authorization);
    }

    [Fact]
    public void OidcNeedsKeycloak()
    {
        var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test")).WithCamSecurity(cognos, CognosContainer.Namespace.Oidc, "admin");
        Assert.Contains("WithKeycloak", Assert.Throws<InvalidOperationException>(tm1.Configure).Message);
    }

    [Fact]
    public void NeedsAnAdminWhoIsATestUser()
    {
        var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"));
        Assert.Throws<ArgumentException>(() => tm1.WithCamSecurity(cognos));
        tm1.WithCamSecurity(cognos, "nobody");
        Assert.Contains("nobody", Assert.Throws<ArgumentException>(tm1.Configure).Message);
    }

    [Fact]
    public void NativeSecurityKeepsBasicLogons()
    {
        var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"));
        Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("admin:")), tm1.Authorization);
        Assert.Null(tm1.CamNamespace);
    }

    [Fact]
    public void KeycloakServesTheCognosRealm()
    {
        var keycloak = new KeycloakContainer();
        Assert.Equal(["start-dev"], keycloak.CommandParts);
        Assert.Equal("http://keycloak:8080", keycloak.EnvMap["KC_HOSTNAME"]);
        Assert.Contains(KeycloakContainer.NetworkAlias, keycloak.NetworkAliases);
    }
}

/// <summary>Java's PlanningAnalyticsEnvironmentTest.</summary>
public class PlanningAnalyticsEnvironmentTests
{
    [Fact]
    public void OneServerUsesItsOwnAdminServer()
    {
        var env = Builder().Tm1("planning").Build();
        var planning = env.Tm1("planning");
        Assert.Null(env.AdminServer);
        Assert.Equal("planning", planning.EnvMap["TM1_SERVER_NAME"]);
        Assert.False(planning.EnvMap.ContainsKey("TM1_ADMIN_HOST"));
        Assert.Contains("planning", planning.NetworkAliases);
        Assert.Same(env.Network, planning.Network);
    }

    [Fact]
    public void SeveralServersShareAnAdminServerThatTm1WebListsFrom()
    {
        var env = Builder().Tm1("planning").Tm1("reporting").Tm1Web().Build();
        var admin = env.AdminServer!;
        Assert.NotNull(admin);
        Assert.Equal(["planning", "reporting"], env.Tm1Servers.Keys);
        foreach (var server in env.Tm1Servers.Values)
        {
            Assert.Same(admin, server.AdminServer);
            Assert.Same(env.Network, server.Network);
        }
        Assert.Same(admin, env.Tm1Web().AdminServer);
        Assert.Contains(env.Tm1("planning"), env.Tm1Web().Dependencies);
        Assert.Contains(env.Tm1("reporting"), env.Tm1Web().Dependencies);
        Assert.Same(admin, env.Containers()[0]);
        Assert.Same(env.Tm1Web(), env.Containers()[^1]);
    }

    [Fact]
    public void CognosPutsEveryServerInCamSecurity()
    {
        var env = Builder()
            .Tm1("planning")
            .Tm1("reporting")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw", "Admins").WithUser("viewer", "viewerpw", "Readers"))
            .Build();
        foreach (var server in env.Tm1Servers.Values)
        {
            server.Configure();
            Assert.Same(env.Cognos(), server.Cognos);
            Assert.Equal("admin", server.EnvMap["TM1_CAM_ADMINS"]);
            Assert.Equal("Admins Readers", server.EnvMap["TM1_CAM_GROUPS"]);
            Assert.Equal("LDAP", server.EnvMap["TM1_CAM_NAMESPACE"]);
        }
    }

    [Fact]
    public void KeycloakAddsOidc()
    {
        var env = Builder()
            .Tm1("planning")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw").WithUser("boss", "bosspw"))
            .Keycloak()
            .CamSecurity(CognosContainer.Namespace.Oidc, "boss")
            .Build();
        var planning = env.Tm1("planning");
        planning.Configure();
        Assert.Same(env.Keycloak(), env.Cognos().Keycloak);
        Assert.Equal("OIDC", planning.EnvMap["TM1_CAM_NAMESPACE"]);
        Assert.Equal("boss", planning.EnvMap["TM1_CAM_ADMINS"]);
        Assert.Equal<IStartable>([env.Keycloak(), env.Cognos()], env.Containers().Take(2));
    }

    [Fact]
    public void CustomizersSeeTheWiredContainerAndCanChangeIt()
    {
        var env = Builder().Tm1("planning", tm1 => tm1.WithSeed(Tm1Seed.Sdata).WithEnv("TM1_CFG_MaxThreads", "4")).Build();
        var planning = env.Tm1("planning");
        Assert.Equal("sdata", planning.EnvMap["TM1_SEED"]);
        Assert.Equal("4", planning.EnvMap["TM1_CFG_MaxThreads"]);
        Assert.Equal("planning", planning.EnvMap["TM1_SERVER_NAME"]);
        Assert.Equal("admin", planning.Username);
        Assert.Equal("apple", planning.Password);
    }

    [Fact]
    public void RefusesMissingOrUnsupportedParts()
    {
        Assert.Contains("at least one", Assert.Throws<InvalidOperationException>(() => Builder().Build()).Message);
        Assert.Contains("Cognos", Assert.Throws<InvalidOperationException>(() => Builder().Tm1("a").Keycloak().Build()).Message);
        Assert.Contains(
            "Keycloak",
            Assert
                .Throws<InvalidOperationException>(() =>
                    Builder().Tm1("a").Cognos(c => c.WithUser("u", "p")).CamSecurity(CognosContainer.Namespace.Oidc).Build()
                )
                .Message
        );
        Assert.Contains("test users", Assert.Throws<InvalidOperationException>(() => Builder().Tm1("a").Cognos(_ => { }).Build()).Message);
        Assert.Contains("already", Assert.Throws<ArgumentException>(() => Builder().Tm1("a").Tm1("a")).Message);
        Assert.Throws<ArgumentException>(() => Builder().Tm1("two words"));
        var env = Builder().Tm1("a").Build();
        Assert.Contains("Tm1Web()", Assert.Throws<InvalidOperationException>(env.Tm1Web).Message);
        Assert.Contains("[a]", Assert.Throws<ArgumentException>(() => env.Tm1("b")).Message);
    }

    private static string Fixture(string name) => Path.Combine(Repo.Root, "spec", "conformance", "pa-environment", name);

    [Fact]
    public void FromConfigBuildsTheExampleWithEverything()
    {
        var env = PlanningAnalyticsEnvironment
            .FromConfig(Path.Combine(Repo.Root, "docker", "pa-setup", "examples", "everything.yaml"))
            .Tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
            .Tm1WebSource(new Tm1WebSource.PrivateImage("tm1-web:test"))
            .CognosSource(new CognosSource.PrivateImage("cognos-server:test"))
            .Build();
        Assert.Equal(["planning", "reporting"], env.Tm1Servers.Keys);
        Assert.Equal("sdata", env.Tm1("planning").EnvMap["TM1_SEED"]);
        Assert.Equal("empty", env.Tm1("reporting").EnvMap["TM1_SEED"]);
        Assert.NotNull(env.AdminServer);
        Assert.Equal("1g", env.Tm1Web().EnvMap["TM1_WEB_HEAP"]);
        Assert.Equal(["admin", "analyst", "viewer"], env.Cognos().Users.Select(u => u.Name));
        Assert.Equal(["Analysts", "Readers"], env.Cognos().GetUser("analyst").Groups);
        Assert.Equal("6144", env.Cognos().EnvMap["COGNOS_MAX_MEMORY"]);
        Assert.Same(env.Keycloak(), env.Cognos().Keycloak);
        // The sources set on the builder win over the file's ibm folder
        Assert.Equal(new Tm1Source.PrivateImage("tm1-server:test"), env.Tm1("planning").Source);
        var planning = env.Tm1("planning");
        planning.Configure();
        Assert.Equal("LDAP", planning.EnvMap["TM1_CAM_NAMESPACE"]);
        Assert.Equal("admin", planning.EnvMap["TM1_CAM_ADMINS"]);
        Assert.Equal("Admins Analysts Readers", planning.EnvMap["TM1_CAM_GROUPS"]);
    }

    [Fact]
    public void FromConfigMapsEverySetting()
    {
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("settings.yaml")).Build();
        var ibm = Path.GetFullPath(Fixture("ibm"));
        var planning = env.Tm1("planning");
        Assert.Equal("sdata", planning.EnvMap["TM1_SEED"]);
        Assert.Equal("4", planning.EnvMap["TM1_CFG_MaxThreads"]);
        Assert.Equal("True", planning.EnvMap["TM1_CFG_EnableNewHierarchyCreation"]);
        Assert.Equal("Measures", planning.EnvMap["TM1_CFG_DefaultMeasuresDimension"]);
        // adminServer: true, with one server
        Assert.NotNull(env.AdminServer);
        Assert.Same(env.AdminServer, planning.AdminServer);
        Assert.Equal("2g", env.Tm1Web().EnvMap["TM1_WEB_HEAP"]);
        Assert.Equal(["Admins", "Planners"], env.Cognos().GetUser("boss").Groups);
        Assert.Equal("8192", env.Cognos().EnvMap["COGNOS_MAX_MEMORY"]);
        planning.Configure();
        Assert.Equal("OIDC", planning.EnvMap["TM1_CAM_NAMESPACE"]);
        Assert.Equal("boss", planning.EnvMap["TM1_CAM_ADMINS"]);
        // ibm is every part's source; images are what they run
        Assert.Equal(new Tm1Source.Bundle(ibm), planning.Source);
        Assert.Equal(new Tm1WebSource.Bundle(ibm), env.Tm1Web().Source);
        Assert.Equal(new CognosSource.Zip(ibm), env.Cognos().Source);
        Assert.Equal("registry.example.com/tm1-server:local", planning.DockerImageName);
        Assert.Equal("registry.example.com/tm1-server:local", env.AdminServer.DockerImageName);
        Assert.Equal("registry.example.com/tm1-web:local", env.Tm1Web().DockerImageName);
        Assert.Equal("registry.example.com/cognos-server:local", env.Cognos().DockerImageName);
        Assert.Equal("registry.example.com/keycloak:26", env.Keycloak().DockerImageName);
    }

    [Fact]
    public void FromConfigRunsPawFromTheIbmFolder()
    {
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("paw.yaml")).Build();
        var ibm = Path.GetFullPath(Fixture("ibm"));
        Assert.Equal(new PawSource(Path.Combine(ibm, "ipa_workspace_local_0.0.0.zip")), env.Paw().Source);
        Assert.True(env.Paw().LicenseAccepted);
        Assert.Same(env.Tm1("tm1"), env.Paw().Tm1Server);
        Assert.Null(env.AdminServer);
    }

    [Fact]
    public void FromConfigRunsPawWithCognos()
    {
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("paw-cognos.yaml")).Build();
        Assert.Same(env.Tm1("tm1"), env.Paw().Tm1Server);
        Assert.Same(env.Cognos(), env.Tm1("tm1").Cognos);
        var settings = env.Paw().ScriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/");
        Assert.Equal("cam", settings["PAW_AUTH_MODE"]);
        Assert.Equal("LDAP", settings["PAW_CAM_NAMESPACE"]);
    }

    [Fact]
    public void PawLogsOnToTm1WithoutCognos()
    {
        var env = Builder().Tm1("planning").Paw(paw => paw.AcceptLicense()).PawSource(TestPawSource()).Build();
        var settings = env.Paw().ScriptSettings("10.0.0.2", "10.0.0.3", null);
        Assert.Equal("tm1", settings["PAW_AUTH_MODE"]);
        Assert.Equal("http://10.0.0.2:8010", settings["PAW_TM1_LOGIN_URL"]);
        Assert.Equal("http://10.0.0.3:5895", settings["PAW_TM1_ADMIN_URL"]);
        Assert.DoesNotContain(settings.Keys, key => key.StartsWith("PAW_CAM_", StringComparison.Ordinal));
    }

    [Fact]
    public void PawWithCognosLogsOnThroughCam()
    {
        var env = Builder()
            .Tm1("planning")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw"))
            .Paw(paw => paw.AcceptLicense())
            .PawSource(TestPawSource())
            .Build();
        Assert.Same(env.Tm1("planning"), env.Paw().Tm1Server);
        // The dispatcher on the network checks logons; browsers go to Cognos on the host
        var settings = env.Paw().ScriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/");
        Assert.Equal("cam", settings["PAW_AUTH_MODE"]);
        Assert.Equal("http://cognos:9300/p2pd/servlet/dispatch", settings["PAW_CAM_SERVLET"]);
        Assert.Equal("http://localhost:9300/bi/v1/disp", settings["PAW_CAM_GATEWAY"]);
        Assert.Equal("LDAP", settings["PAW_CAM_NAMESPACE"]);
        // The TM1 server's namespace
        var oidc = Builder()
            .Tm1("planning")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw"))
            .Keycloak()
            .CamSecurity(CognosContainer.Namespace.Oidc)
            .Paw(paw => paw.AcceptLicense())
            .PawSource(TestPawSource())
            .Build();
        Assert.Equal("OIDC", oidc.Paw().ScriptSettings("10.0.0.2", "10.0.0.2", "http://localhost:9300/bi/")["PAW_CAM_NAMESPACE"]);
        Assert.Contains("Cognos", Assert.Throws<InvalidOperationException>(() => env.Paw().ScriptSettings("10.0.0.2", "10.0.0.2", null)).Message);
    }

    private static PawSource TestPawSource() => new(Path.Combine(Fixture("ibm"), "ipa_workspace_local_0.0.0.zip"));

    [Fact]
    public void AnEngineRunsBesideTheTm1Servers()
    {
        var env = EngineBuilder().Tm1("legacy").Tm1Engine(engine => engine.WithDatabase("planning").WithUser("analyst", "Analysts")).Build();
        var engine = env.Tm1Engine();
        Assert.Same(env.Network, engine.Network);
        Assert.Same(env.Network, env.Tm1("legacy").Network);
        Assert.Equal(new Tm1EngineSource.PrivateImage("tm1-engine:test"), engine.Source);
        // Before anything configures or starts it: its datastore must be there when it starts
        Assert.Same(env.Network, engine.Datastore.Network);
        // Its name on the network, as in pa-setup's compose project
        Assert.Contains("tm1-engine", engine.NetworkAliases);
        engine.Configure();
        // Its datastore is the engine's own, on the environment's network with it
        Assert.Same(env.Network, engine.Datastore.Network);
        Assert.Equal("tm1/planning", engine.EnvMap["TM1_DATABASES"]);
        Assert.Equal("analyst:Analysts", engine.EnvMap["TM1_USERS"]);
        Assert.Equal("planning", env.Tm1Database("planning").Name);
        // Started with TM1 11's parts, and before PAW
        Assert.Equal<IStartable>([env.Tm1("legacy"), engine], env.Containers());
        var withPaw = EngineBuilder().Tm1("legacy").Tm1Engine().Paw(paw => paw.AcceptLicense()).PawSource(TestPawSource()).Build();
        Assert.Equal<IStartable>([withPaw.Tm1("legacy"), withPaw.Tm1Engine(), withPaw.Paw()], withPaw.Containers());
        // Its label, like every part's
        Assert.Equal("dotnet", ((IGenericContainer)engine).Labels["io.skipcheck.tm1.testcontainers"]);
    }

    [Fact]
    public void AnEngineAloneIsAnEnvironment()
    {
        var env = EngineBuilder().Tm1Engine().Build();
        Assert.Empty(env.Tm1Servers);
        Assert.Null(env.AdminServer);
        Assert.Equal<IStartable>([env.Tm1Engine()], env.Containers());
        Assert.Equal("tm1", env.Tm1Database("tm1").Instance);
        Assert.Contains(
            "no TM1 v12 engine; add it with Tm1Engine()",
            Assert.Throws<InvalidOperationException>(() => Builder().Tm1("planning").Build().Tm1Engine()).Message
        );
        Assert.Contains(
            "Tm1(name), or a TM1 v12 engine with Tm1Engine()",
            Assert.Throws<InvalidOperationException>(() => PlanningAnalyticsEnvironment.Builder().Build()).Message
        );
    }

    [Fact]
    public void TheAdminServerTm1WebPawAndCognosNeedATm1ServerNotAnEngine()
    {
        PlanningAnalyticsEnvironment.EnvironmentBuilder[] incomplete =
        [
            EngineBuilder().Tm1Engine().Tm1Web(),
            EngineBuilder().Tm1Engine().AdminServer(),
            EngineBuilder().Tm1Engine().Cognos(cognos => cognos.WithUser("admin", "adminpw")),
            EngineBuilder().Tm1Engine().PawSource(TestPawSource()).Paw(paw => paw.AcceptLicense()),
        ];
        foreach (var builder in incomplete)
        {
            Assert.Contains("are TM1 11's: add a TM1 server with Tm1(name)", Assert.Throws<InvalidOperationException>(() => builder.Build()).Message);
        }
    }

    [Fact]
    public void AServerCantTakeTheEnginesNamesOnTheNetwork()
    {
        foreach (var name in new[] { "tm1-datastore", "TM1-Engine" })
        {
            Assert.Contains(
                "A TM1 server can't be named " + name + " beside a TM1 v12 engine",
                Assert.Throws<InvalidOperationException>(() => EngineBuilder().Tm1(name).Tm1Engine().Build()).Message
            );
        }
        // Without an engine the names are free
        Assert.Contains("tm1-datastore", Builder().Tm1("tm1-datastore").Build().Tm1Servers.Keys);
    }

    [Fact]
    public void FromConfigKeepsTheFilesEngineUnderACustomizerAndItsImageWithoutASection()
    {
        // A customizer adds to the file's databases and users; it doesn't replace them
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("tm1-engine.yaml")).Tm1Engine(engine => engine.WithUser("extra")).Build();
        env.Tm1Engine().Configure();
        Assert.Equal("tm1/planning,tm1/Sales,other/sales", env.Tm1Engine().EnvMap["TM1_DATABASES"]);
        Assert.Equal("analyst:Analysts:Planners,viewer,boss:ADMIN,planner:Planners,extra", env.Tm1Engine().EnvMap["TM1_USERS"]);

        // images.tm1Engine is the image of an engine added in code too
        var added = PlanningAnalyticsEnvironment.FromConfig(Fixture("engine-image-only.yaml")).Tm1Engine().Build();
        Assert.Equal("registry.example.com/tm1-engine:local", added.Tm1Engine().DockerImageName);
        Assert.Equal(new Tm1EngineSource.Bundle(Path.GetFullPath(Fixture("ibm"))), added.Tm1Engine().Source);
    }

    [Fact]
    public void FromConfigRefusesAUserOrGroupNameThatEndsInANewline()
    {
        // Only .NET's reader could take one: its $ matches before a last newline
        var dir = Directory.CreateTempSubdirectory("tm1-tc-").FullName;
        try
        {
            (string Yaml, string Message)[] files =
            [
                ("tm1Engine: { users: [\"analyst\\n\"] }", "tm1Engine.users[0]: user name"),
                ("tm1Engine: { users: [{ name: analyst, groups: [\"Analysts\\n\"] }] }", "tm1Engine.users[0]: group"),
                ("tm1: { servers: { tm1: {} } }\ncognos: { users: [{ name: \"admin\\n\", password: pw }] }", "cognos.users[0]: user name"),
                ("tm1: { servers: { \"tm1\\n\": {} } }", "server names are letters, digits, _ and -"),
            ];
            foreach (var (yaml, message) in files)
            {
                var file = Path.Combine(dir, "pa-environment.yaml");
                File.WriteAllText(file, "version: 1\n" + yaml + "\n");
                Assert.Contains(message, Assert.Throws<ArgumentException>(() => PlanningAnalyticsEnvironment.FromConfig(file)).Message);
            }
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void AnEngineTakesOAuth2TokensFromTheEnvironmentsKeycloak()
    {
        var env = EngineBuilder().Tm1Engine(engine => engine.WithUser("analyst").WithLogin("analyst", "analystpw")).Tm1EngineOAuth2().Build();
        // Added for the engine, without Cognos
        var keycloak = env.Keycloak();
        Assert.Same(keycloak, env.Tm1Engine().Keycloak);
        Assert.Same(env.Network, keycloak.Network);
        Assert.Same(env.Network, env.Tm1Engine().Network);
        Assert.Same(env.Network, env.Tm1Engine().Datastore.Network);
        Assert.Equal("http://keycloak:8080/realms/tm1", env.Tm1Engine().EnvMap["TM1_OAUTH2_ISSUER_URL"]);
        Assert.Equal<IStartable>([keycloak, env.Tm1Engine()], env.Containers());
        Assert.Equal("analystpw", env.Tm1Database("tm1").OAuth2Login("analyst").Password);
        // The engine starts its Keycloak first, with the realm of the users the customizer gave a password
        Assert.Contains(keycloak, env.Tm1Engine().Dependencies);
        keycloak.Configure();
        Assert.Equal(["start-dev", "--import-realm"], keycloak.CommandParts);
        Assert.Contains("\"analyst\"", Encoding.UTF8.GetString(keycloak.Contents["/opt/keycloak/data/import/tm1-realm.json"]), StringComparison.Ordinal);

        // A Keycloak that's there for the engine alone gives Cognos no OIDC namespace
        var beside = EngineBuilder()
            .Tm1("legacy")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw"))
            .Tm1Engine(engine => engine.WithLogin("admin", "adminpw"))
            .Tm1EngineOAuth2()
            .Build();
        Assert.Null(beside.Cognos().Keycloak);
        Assert.Same(beside.Keycloak(), beside.Tm1Engine().Keycloak);

        Assert.Contains(
            "OAuth 2.0 logins are a TM1 v12 engine's: add Tm1Engine() too",
            Assert.Throws<InvalidOperationException>(() => Builder().Tm1("legacy").Tm1EngineOAuth2().Build()).Message
        );
        Assert.Contains(
            "add Cognos(...) or Tm1EngineOAuth2() too",
            Assert.Throws<InvalidOperationException>(() => Builder().Tm1("legacy").Keycloak().Build()).Message
        );
    }

    [Fact]
    public void FromConfigTurnsOAuth2OnWithTheUsersWhoHaveAPassword()
    {
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("engine-oauth2.yaml")).Build();
        var engine = env.Tm1Engine();
        Assert.Same(env.Keycloak(), engine.Keycloak);
        engine.Configure();
        // TM1 gets names and groups; Keycloak the passwords
        Assert.Equal("analyst:Analysts,boss,viewer,planner:Planners", engine.EnvMap["TM1_USERS"]);
        var db = env.Tm1Database("tm1");
        Assert.Equal("analyst$pw", db.OAuth2Login("analyst").Password);
        Assert.Equal("bosspw", db.OAuth2Login("boss").Password);
        Assert.Equal("plannerpw", db.OAuth2Login("planner").Password);
        Assert.Throws<ArgumentException>(() => db.OAuth2Login("viewer"));

        // oauth2: false is off, with the passwords in the file left readable
        var off = PlanningAnalyticsEnvironment.FromConfig(Fixture("engine-oauth2-off.yaml")).Build();
        Assert.Null(off.Tm1Engine().Keycloak);
        Assert.False(off.Tm1Engine().EnvMap.ContainsKey("TM1_OAUTH2_ISSUER_URL"));
        Assert.Throws<InvalidOperationException>(off.Keycloak);
    }

    [Fact]
    public void AnEnvironmentWithAnEngineCantBeReused()
    {
        // Whatever the developer's switch says: the same code must do the same everywhere
        foreach (var enabled in new[] { true, false })
        {
            Assert.Contains(
                "An environment with a TM1 v12 engine can't be reused",
                Assert.Throws<InvalidOperationException>(() => EngineBuilder().Tm1("legacy").Tm1Engine().Reuse(true).Build(enabled)).Message
            );
        }
    }

    [Fact]
    public void FromConfigAddsTheEngineWithItsDatabasesAndUsers()
    {
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("tm1-engine.yaml")).Build();
        var ibm = Path.GetFullPath(Fixture("ibm"));
        var engine = env.Tm1Engine();
        engine.Configure();
        // Sales and sales are two databases to TM1 v12, and each is in its instance
        Assert.Equal("tm1/planning,tm1/Sales,other/sales", engine.EnvMap["TM1_DATABASES"]);
        // A user's password isn't TM1 v12's to keep
        Assert.Equal("analyst:Analysts:Planners,viewer,boss:ADMIN,planner:Planners", engine.EnvMap["TM1_USERS"]);
        Assert.Equal("tm1", env.Tm1Database("Sales").Instance);
        Assert.Equal("sales", engine.Database("other", "sales").Name);
        // ibm is every part's source; images are what they run
        Assert.Equal(new Tm1EngineSource.Bundle(ibm), engine.Source);
        Assert.Equal("registry.example.com/tm1-engine:local", engine.DockerImageName);
        Assert.Equal(new Tm1Source.Bundle(ibm), env.Tm1("legacy").Source);
        // images.mongo is checked and not used: the datastore is the engine's own
        Assert.Equal(Tm1EngineContainer.DatastoreImage, engine.Datastore.DockerImageName);
        Assert.Same(env.Network, engine.Datastore.Network);
    }

    [Fact]
    public void FromConfigRunsAnEngineAlone()
    {
        var env = PlanningAnalyticsEnvironment
            .FromConfig(Path.Combine(Repo.Root, "testcontainers-conformance", "scenarios", "engine.yaml"))
            .Tm1EngineSource(new Tm1EngineSource.PrivateImage("tm1-engine:test"))
            .Build();
        Assert.Empty(env.Tm1Servers);
        env.Tm1Engine().Configure();
        Assert.Equal("tm1/planning,tm1/sales", env.Tm1Engine().EnvMap["TM1_DATABASES"]);
        Assert.Equal("analyst:Analysts", env.Tm1Engine().EnvMap["TM1_USERS"]);
    }

    [Fact]
    public void FromConfigTakesOidcTrueAsOn()
    {
        var env = PlanningAnalyticsEnvironment.FromConfig(Fixture("oidc-true.yaml")).Build();
        Assert.NotNull(env.Keycloak());
        var planning = env.Tm1("planning");
        planning.Configure();
        Assert.Equal("OIDC", planning.EnvMap["TM1_CAM_NAMESPACE"]);
    }

    private static PlanningAnalyticsEnvironment.EnvironmentBuilder EngineBuilder() =>
        Builder().Tm1EngineSource(new Tm1EngineSource.PrivateImage("tm1-engine:test"));

    [Fact]
    public void FromConfigRefusesWhatItCannotRun()
    {
        var invalid = Directory.GetFiles(Fixture("invalid"), "*.yaml").Order(StringComparer.Ordinal).ToList();
        Assert.True(invalid.Count > 40, invalid.Count + " invalid files");
        foreach (var file in invalid)
        {
            var first = File.ReadLines(file).First();
            Assert.True(first.StartsWith("# error: ", StringComparison.Ordinal), file);
            var message = Assert.Throws<ArgumentException>(() => PlanningAnalyticsEnvironment.FromConfig(file)).Message;
            Assert.True(message.StartsWith(Path.GetFileName(file) + ": ", StringComparison.Ordinal), file + ": " + message);
            Assert.True(message.Contains(first["# error: ".Length..], StringComparison.Ordinal), file + ": " + message);
        }
        Assert.Contains("not found", Assert.Throws<ArgumentException>(() => PlanningAnalyticsEnvironment.FromConfig(Fixture("missing.yaml"))).Message);
    }

    [Fact]
    public void ReuseNeedsTheBuilderAndTheDevelopersSwitch()
    {
        Assert.False(Builder().Tm1("a").Build(true).Reused);
        var notSwitchedOn = Builder().Tm1("a").Reuse(true).Build(false);
        Assert.False(notSwitchedOn.Reused);
        Assert.False(notSwitchedOn.Network.IsReusable);
        var reused = Builder().Tm1("a").Reuse(true).Build(true);
        Assert.True(reused.Reused);
        // Not started, so not yet marked: StartAsync does that
        Assert.False(((IGenericContainer)reused.Tm1("a")).ShouldBeReused);
    }

    [Fact]
    public void AReusedEnvironmentsNetworkIsNamedAfterItsIdentity()
    {
        var env = Builder().Tm1("planning").Tm1Web().Reuse(true).Build(true);
        Assert.Matches("^[0-9a-f]{16}$", env.Identity());
        Assert.True(env.Network.IsReusable);
        Assert.Equal("tm1-environment-" + env.Identity(), env.Network.Name);
        Assert.IsType<PlanningAnalyticsEnvironment.ReusableNetwork>(env.Network.Resource);
        foreach (var server in env.Tm1Servers.Values)
        {
            Assert.Same(env.Network, server.Network);
        }
        // The developer's own network is used as it is
        var own = Network.Reusable(new PlanningAnalyticsEnvironment.ReusableNetwork(() => "mine"));
        Assert.Same(own, Builder().Tm1("planning").Network(own).Reuse(true).Build(true).Network);
    }

    [Fact]
    public void TheIdentityIsTheSameForTheSameConfigurationAndDiffersOtherwise()
    {
        Assert.Equal(
            Builder().Tm1("planning").Tm1Web().Reuse(true).Build(true).Identity(),
            Builder().Tm1("planning").Tm1Web().Reuse(true).Build(true).Identity()
        );
        PlanningAnalyticsEnvironment.EnvironmentBuilder[] configurations =
        [
            Builder().Tm1("planning"),
            Builder().Tm1("reporting"),
            Builder().Tm1("planning", tm1 => tm1.WithSeed(Tm1Seed.Sdata)),
            Builder().Tm1("planning", tm1 => tm1.WithEnv("TM1_CFG_MaxThreads", "4")),
            Builder().Tm1("planning").Tm1("reporting"),
            Builder().Tm1("planning").AdminServer(),
            Builder().Tm1("planning").Tm1Web(),
            Builder().Tm1("planning").Tm1Web(web => web.WithEnv("TM1_WEB_HEAP", "2g")),
            Builder().Tm1("planning").Tm1Source(new Tm1Source.PrivateImage("tm1-server:other")),
            Builder().Tm1("planning").Cognos(c => c.WithUser("admin", "adminpw")),
            Builder().Tm1("planning").Cognos(c => c.WithUser("admin", "otherpw")),
            Builder().Tm1("planning").Cognos(c => c.WithUser("admin", "adminpw").WithUser("boss", "bosspw")),
            Builder()
                .Tm1("planning")
                .Cognos(c => c.WithUser("admin", "adminpw").WithUser("boss", "bosspw"))
                .CamSecurity(CognosContainer.Namespace.Ldap, "boss"),
            Builder().Tm1("planning").Cognos(c => c.WithUser("admin", "adminpw")).Keycloak(),
        ];
        var identities = configurations.Select(b => b.Reuse(true).Build(true).Identity()).ToList();
        Assert.Equal(identities.Count, identities.Distinct().Count());
    }

    [Fact]
    public void EveryContainerNamesTheLanguageThatStartedIt()
    {
        var env = Builder().Tm1("planning").Tm1("reporting").Tm1Web().Cognos(cognos => cognos.WithUser("admin", "adminpw")).Keycloak().Build();
        Assert.Equal(6, env.Containers().Count);
        foreach (var part in env.Containers())
        {
            // The same label in every language, so docker ps finds what this library started
            Assert.Equal("dotnet", ((IGenericContainer)part).Labels["io.skipcheck.tm1.testcontainers"]);
        }
    }

    [Fact]
    public void StartingAReusedEnvironmentMarksEveryContainerWithItsIdentity()
    {
        var env = Builder().Tm1("planning").Tm1("reporting").Tm1Web().Reuse(true).Build(true);
        env.PrepareForReuse();
        foreach (var part in env.Containers())
        {
            var container = (IGenericContainer)part;
            Assert.True(container.ShouldBeReused, part.ToString());
            Assert.Equal(env.Identity(), container.Labels["io.skipcheck.tm1.environment"]);
        }
        // Testcontainers for .NET adds no random alias of its own (Java's tc-…), so the aliases stay as they were
        Assert.Equal(["planning"], env.Tm1("planning").NetworkAliases);
        Assert.Equal([Tm1AdminServerContainer.NetworkAlias], env.AdminServer!.NetworkAliases);
    }

    private static PlanningAnalyticsEnvironment.EnvironmentBuilder Builder() =>
        PlanningAnalyticsEnvironment
            .Builder()
            .Tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
            .Tm1WebSource(new Tm1WebSource.PrivateImage("tm1-web:test"))
            .CognosSource(new CognosSource.PrivateImage("cognos-server:test"));
}

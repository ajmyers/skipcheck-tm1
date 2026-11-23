// SPDX-License-Identifier: Apache-2.0

using System;
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
        Assert.Contains(
            "PAW",
            Assert.Throws<InvalidOperationException>(() => Builder().Tm1("a").Cognos(c => c.WithUser("u", "p")).Paw(_ => { }).Build()).Message
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
    public void FromConfigRefusesWhatItCannotRun()
    {
        var invalid = Directory.GetFiles(Fixture("invalid"), "*.yaml").Order(StringComparer.Ordinal).ToList();
        Assert.NotEmpty(invalid);
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

    private static PlanningAnalyticsEnvironment.EnvironmentBuilder Builder() =>
        PlanningAnalyticsEnvironment
            .Builder()
            .Tm1Source(new Tm1Source.PrivateImage("tm1-server:test"))
            .Tm1WebSource(new Tm1WebSource.PrivateImage("tm1-web:test"))
            .CognosSource(new CognosSource.PrivateImage("cognos-server:test"));
}

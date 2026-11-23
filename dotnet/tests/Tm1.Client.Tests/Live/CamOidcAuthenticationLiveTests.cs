using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Logging on to a TM1 in CAM security through Cognos's OpenID Connect namespace, with
/// Keycloak as the provider: a password Cognos checks with Keycloak, and an ID token the application
/// holds, exchanged at Cognos for a passport. A port of the Java client's
/// CamOidcAuthenticationTm1Test.</summary>
public sealed class CamOidcAuthenticationLiveTests(CamOidcAuthenticationLiveTests.Containers containers)
    : IClassFixture<CamOidcAuthenticationLiveTests.Containers>
{
    /// <summary>Keycloak, Cognos with two test users and an OIDC namespace, and a TM1 of its own in CAM
    /// security: started only when the tests run (<see cref="RequiresCognosFactAttribute"/>).</summary>
    public sealed class Containers : IAsyncLifetime
    {
        private KeycloakContainer? _keycloak;
        private CognosContainer? _cognos;
        private Tm1ServerContainer? _tm1;

        public KeycloakContainer Keycloak => _keycloak ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage);

        public CognosContainer Cognos => _cognos ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage);

        public Tm1Client Client(Tm1Authenticator authenticator) =>
            Tm1Client
                .Builder()
                .BaseUrl((_tm1 ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage)).RestUrl)
                .Authenticator(authenticator)
                .Build();

        public async Task InitializeAsync()
        {
            if (new RequiresCognosFactAttribute().Skip is not null)
            {
                return;
            }
            _keycloak = new KeycloakContainer();
            _cognos = new CognosContainer(CognosSource.Discover()!)
                .WithUser("admin", "adminpw", "Admins")
                .WithUser("viewer", "viewerpw", "Readers")
                .WithKeycloak(_keycloak);
            _tm1 = new Tm1ServerContainer(Tm1Source.Discover()!).WithCamSecurity(_cognos, CognosContainer.Namespace.Oidc, "admin");
            await _tm1.StartAsync();
        }

        public async Task DisposeAsync()
        {
            if (_tm1 is not null)
            {
                await _tm1.DisposeAsync();
            }
            if (_cognos is not null)
            {
                await _cognos.DisposeAsync();
            }
            if (_keycloak is not null)
            {
                await _keycloak.DisposeAsync();
            }
        }
    }

    [RequiresCognosFact]
    public async Task LogsOnWithAPasswordCognosChecksWithTheProvider()
    {
        await using var admin = containers.Client(Tm1Authenticator.CamNamespace("admin", "adminpw", "OIDC"));
        Assert.Equal("CAMID(\"OIDC:u:admin\")", (await admin.Users.ActiveUserAsync()).Name);
    }

    [RequiresCognosFact]
    public async Task ExchangesAnIdTokenForAPassport()
    {
        await using var viewer = containers.Client(
            Tm1Authenticator.CognosIdToken(containers.Cognos.Url, "OIDC", async ct => await containers.Keycloak.IdTokenAsync("viewer", "viewerpw", ct))
        );
        var groups = await viewer.Users.GroupsAsync("CAMID(\"OIDC:u:viewer\")").Select(g => g.Name).ToListAsync();
        Assert.Contains("CAMID(\"OIDC:g:readers\")", groups);
    }
}

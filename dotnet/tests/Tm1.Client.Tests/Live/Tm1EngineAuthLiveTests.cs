// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Logging on to a TM1 v12 engine through its application (service-to-service) and through
/// OAuth 2.0 bearer tokens from Keycloak. A port of the Java client's
/// Tm1EngineAuthTm1Test.</summary>
public sealed class Tm1EngineAuthLiveTests(Tm1EngineAuthLiveTests.Containers containers) : IClassFixture<Tm1EngineAuthLiveTests.Containers>
{
    /// <summary>Keycloak and a TM1 v12 engine taking OAuth 2.0 tokens from it, started only when the
    /// tests run (<see cref="RequiresTm1EngineFactAttribute"/>).</summary>
    public sealed class Containers : IAsyncLifetime
    {
        private KeycloakContainer? _keycloak;
        private Tm1EngineContainer? _engine;
        private Tm1Database? _database;

        public Tm1Database Database => _database ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);

        public Tm1Client Client(Tm1Authenticator authenticator) =>
            Tm1Client.Builder().BaseUrl(Database.RestUrl).Dialect(Tm1Dialect.V12(Database.Instance, Database.Name)).Authenticator(authenticator).Build();

        public async Task InitializeAsync()
        {
            if (new RequiresTm1EngineFactAttribute().Skip is not null)
            {
                return;
            }
            _keycloak = new KeycloakContainer();
            _engine = new Tm1EngineContainer(Tm1EngineSource.Discover()!)
                .WithUser("analyst", "Analysts")
                .WithLogin("analyst", "analystpw")
                .WithOAuth2(_keycloak);
            await _engine.StartAsync();
            _database = _engine.Database(Tm1EngineContainer.DefaultDatabase);
        }

        public async Task DisposeAsync()
        {
            try
            {
                if (_engine is not null)
                {
                    await _engine.DisposeAsync();
                }
            }
            finally
            {
                if (_keycloak is not null)
                {
                    await _keycloak.DisposeAsync();
                }
            }
        }
    }

    [RequiresTm1EngineFact]
    public async Task LogsOnThroughApplicationCredentials()
    {
        var login = containers.Database.Login("analyst");
        await using var analyst = containers.Client(Tm1Authenticator.Application(login.ClientId, login.ClientSecret, login.User));
        Assert.Equal("analyst", (await analyst.Users.ActiveUserAsync()).Name, ignoreCase: true);
    }

    [RequiresTm1EngineFact]
    public async Task LogsOnWithABearerToken()
    {
        var oauth2 = containers.Database.OAuth2Login("analyst");
        await using var analyst = containers.Client(Tm1Authenticator.Bearer(async ct => await oauth2.TokenAsync(ct)));
        Assert.Equal("analyst", (await analyst.Users.ActiveUserAsync()).Name, ignoreCase: true);
    }
}

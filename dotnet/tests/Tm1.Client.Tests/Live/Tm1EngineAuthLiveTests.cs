// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Logging on to a TM1 v12 engine through its application (service-to-service) and through
/// OAuth 2.0 bearer tokens from Keycloak, including real error classification and refusal caching.
/// A port of the Java client's Tm1EngineAuthTm1Test.</summary>
public sealed class Tm1EngineAuthLiveTests(Tm1EngineAuthLiveTests.Containers containers) : IClassFixture<Tm1EngineAuthLiveTests.Containers>
{
    /// <summary>Keycloak and a TM1 v12 engine taking OAuth 2.0 tokens from it, started only when the
    /// tests run (<see cref="RequiresTm1EngineFactAttribute"/>).</summary>
    public sealed class Containers : IAsyncLifetime
    {
        private KeycloakContainer? _keycloak;
        private Tm1EngineContainer? _engine;
        private Tm1Database? _database;

        public KeycloakContainer Keycloak => _keycloak ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);

        public Tm1Database Database => _database ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);

        public Tm1Client Client(Tm1Authenticator authenticator) =>
            Tm1Client.Builder().BaseUrl(Database.RestUrl).Dialect(Tm1Dialect.V12(Database.Instance, Database.Name)).Authenticator(authenticator).Build();

        /// <summary>A real JWT signed by Keycloak's master realm, not the realm TM1 trusts.</summary>
        public async Task<string> MasterRealmTokenAsync(CancellationToken ct = default)
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
            using var form = new FormUrlEncodedContent(
                new Dictionary<string, string>
                {
                    ["grant_type"] = "password",
                    ["client_id"] = "admin-cli",
                    ["username"] = "admin",
                    ["password"] = "admin",
                }
            );
            using var response = await http.PostAsync(Keycloak.Url + "/realms/master/protocol/openid-connect/token", form, ct);
            var body = await response.Content.ReadAsStringAsync(ct);
            Assert.True(response.IsSuccessStatusCode, body);
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.GetProperty("access_token").GetString()!;
        }

        public async Task InitializeAsync()
        {
            if (new RequiresTm1EngineFactAttribute().Skip is not null)
            {
                return;
            }
            try
            {
                _keycloak = new KeycloakContainer();
                _engine = new Tm1EngineContainer(Tm1EngineSource.Discover()!)
                    .WithUser("analyst", "Analysts")
                    .WithLogin("analyst", "analystpw")
                    .WithOAuth2(_keycloak);
                await _engine.StartAsync();
                _database = _engine.Database(Tm1EngineContainer.DefaultDatabase);
            }
            catch
            {
                await DisposeAsync();
                throw;
            }
        }

        public async Task DisposeAsync()
        {
            var engine = _engine;
            var keycloak = _keycloak;
            _engine = null;
            _keycloak = null;
            try
            {
                if (engine is not null)
                {
                    await engine.DisposeAsync();
                }
            }
            finally
            {
                if (keycloak is not null)
                {
                    await keycloak.DisposeAsync();
                }
            }
        }
    }

    [RequiresTm1EngineFact]
    public async Task LogsOnThroughApplicationCredentials()
    {
        var login = containers.Database.Login("analyst");
        await using var analyst = containers.Client(Tm1Authenticator.Application(login.ClientId, login.ClientSecret, login.User));
        var user = await analyst.Users.ActiveUserAsync(ODataQuery.None().Expand("Groups"));
        Assert.Equal("analyst", user.Name, ignoreCase: true);
        Assert.Contains("Analysts", user.Groups!.Select(g => g.Name));
    }

    [RequiresTm1EngineFact]
    public async Task RefusesWrongApplicationSecretAndUnknownUser()
    {
        var login = containers.Database.Login("analyst");
        var wrongSecret = containers.Client(Tm1Authenticator.Application(login.ClientId, "wrong-secret", login.User));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongSecret.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
            Assert.Equal("invalid_client", e.Error?.Code);
            await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongSecret.Users.ActiveUserAsync());
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrongSecret.DisposeAsync());
        }

        // The gateway creates a session for the unknown user; the database refuses it (403)
        var unknownUser = containers.Client(Tm1Authenticator.Application(login.ClientId, login.ClientSecret, "nobody_here"));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1ForbiddenException>(() => unknownUser.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.Forbidden, e.Kind);
        }
        finally
        {
            await LiveModel.Quietly(async () => await unknownUser.DisposeAsync());
        }
    }

    [RequiresTm1EngineFact]
    public async Task LogsOnWithABearerToken()
    {
        var oauth2 = containers.Database.OAuth2Login("analyst");
        await using (var supplied = containers.Client(Tm1Authenticator.Bearer(async ct => await oauth2.TokenAsync(ct))))
        {
            var user = await supplied.Users.ActiveUserAsync(ODataQuery.None().Expand("Groups"));
            Assert.Equal("analyst", user.Name, ignoreCase: true);
            Assert.Contains("Analysts", user.Groups!.Select(g => g.Name));
        }
        await using (var fixedClient = containers.Client(Tm1Authenticator.Bearer(await oauth2.TokenAsync())))
        {
            Assert.Equal("analyst", (await fixedClient.Users.ActiveUserAsync()).Name, ignoreCase: true);
        }
    }

    [RequiresTm1EngineFact]
    public async Task RefusesInvalidAndWrongRealmBearerTokensOnceWithoutRetrying()
    {
        var asked = 0;
        var wrongRealm = containers.Client(
            Tm1Authenticator.Bearer(async ct =>
            {
                Interlocked.Increment(ref asked);
                return await containers.MasterRealmTokenAsync(ct);
            })
        );
        try
        {
            var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongRealm.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
            await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongRealm.Users.ActiveUserAsync());
            Assert.Equal(1, asked); // supplier asked once: a refusal isn't retried at once
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrongRealm.DisposeAsync());
        }

        var malformed = containers.Client(Tm1Authenticator.Bearer("not.a.token"));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => malformed.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
        }
        finally
        {
            await LiveModel.Quietly(async () => await malformed.DisposeAsync());
        }
    }
}

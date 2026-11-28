// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Pass-through Basic authentication on a TM1 v12 engine backed by a TM1 11 server. A port
/// of the Java client's Tm1EnginePassThroughAuthTm1Test.</summary>
public sealed class Tm1EnginePassThroughAuthLiveTests(Tm1EnginePassThroughAuthLiveTests.Containers containers)
    : IClassFixture<Tm1EnginePassThroughAuthLiveTests.Containers>
{
    public sealed class Containers : IAsyncLifetime
    {
        private Tm1ServerContainer? _server;
        private Tm1EngineContainer? _engine;
        private Tm1Database? _database;

        public Tm1ServerContainer Server => _server ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);

        public Tm1Database Database => _database ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);

        public Tm1Client Client(Tm1Authenticator authenticator) =>
            Tm1Client.Builder().BaseUrl(Database.RestUrl).Dialect(Tm1Dialect.V12(Database.Instance, Database.Name)).Authenticator(authenticator).Build();

        public async Task InitializeAsync()
        {
            if (new RequiresTm1PassThroughFactAttribute().Skip is not null)
            {
                return;
            }
            try
            {
                _server = new Tm1ServerContainer(Tm1Source.Discover()!).WithSeed(Tm1Seed.Sdata);
                _engine = new Tm1EngineContainer(Tm1EngineSource.Discover()!).WithDatabase("planning").WithUser("analyst", "Analysts").WithPassThrough(_server);
                await _engine.StartAsync();
                _database = _engine.Database("planning");
                await using var tm11 = Tm1Client.Builder().BaseUrl(_server.RestUrl).BasicAuth(_server.Username, _server.Password).Build();
                await tm11.Users.CreateAsync(UserCreate.Builder().Name("analyst").Password("analystpw").Build());
                await tm11.Users.CreateAsync(UserCreate.Builder().Name("stranger").Password("strangerpw").Build());
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
            var server = _server;
            _engine = null;
            _server = null;
            try
            {
                if (engine is not null)
                {
                    await engine.DisposeAsync();
                }
            }
            finally
            {
                if (server is not null)
                {
                    await server.DisposeAsync();
                }
            }
        }
    }

    [RequiresTm1PassThroughFact]
    public async Task LogsOnAsAnAdministratorAndAUserThroughPassThrough()
    {
        await using (var admin = containers.Client(Tm1Authenticator.Basic(containers.Server.Username, containers.Server.Password)))
        {
            var user = await admin.Users.ActiveUserAsync();
            Assert.Equal(UserType.Admin, user.Type);
        }

        await using (var analyst = containers.Client(Tm1Authenticator.Basic("analyst", "analystpw")))
        {
            var user = await analyst.Users.ActiveUserAsync(ODataQuery.None().Expand("Groups"));
            Assert.Equal("analyst", user.Name, ignoreCase: true);
            Assert.Equal(UserType.User, user.Type);
            Assert.Contains("Analysts", user.Groups!.Select(g => g.Name));
        }
    }

    [RequiresTm1PassThroughFact]
    public async Task RefusesAWrongPasswordAndAUserTheDatabaseLacks()
    {
        var wrongPassword = containers.Client(Tm1Authenticator.Basic("analyst", "not-it"));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongPassword.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrongPassword.DisposeAsync());
        }

        // TM1 11 accepts stranger's password; the TM1 v12 database has no such user
        var stranger = containers.Client(Tm1Authenticator.Basic("stranger", "strangerpw"));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1ForbiddenException>(() => stranger.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.Forbidden, e.Kind);
        }
        finally
        {
            await LiveModel.Quietly(async () => await stranger.DisposeAsync());
        }
    }
}

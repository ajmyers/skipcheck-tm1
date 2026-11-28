// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Pass-through CAM authentication on a TM1 v12 engine backed by a TM1 11 server in Cognos
/// (CAM) security: <c>CAMNamespace</c> and <c>CAMPassport</c> (via Cognos). A port of the Java
/// client's Tm1EnginePassThroughCamAuthTm1Test.</summary>
public sealed class Tm1EnginePassThroughCamAuthLiveTests(Tm1EnginePassThroughCamAuthLiveTests.Containers containers)
    : IClassFixture<Tm1EnginePassThroughCamAuthLiveTests.Containers>
{
    private const string Analyst = "CAMID(\"LDAP:u:uid=analyst,ou=people\")";

    public sealed class Containers : IAsyncLifetime
    {
        private CognosContainer? _cognos;
        private Tm1ServerContainer? _server;
        private Tm1EngineContainer? _engine;
        private Tm1Database? _database;

        public CognosContainer Cognos => _cognos ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage);

        public Tm1Database Database => _database ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);

        public Tm1Client Client(Tm1Authenticator authenticator) =>
            Tm1Client.Builder().BaseUrl(Database.RestUrl).Dialect(Tm1Dialect.V12(Database.Instance, Database.Name)).Authenticator(authenticator).Build();

        public async Task InitializeAsync()
        {
            if (new RequiresTm1PassThroughCamFactAttribute().Skip is not null)
            {
                return;
            }
            try
            {
                _cognos = new CognosContainer(CognosSource.Discover()!)
                    .WithUser("admin", "adminpw", "Admins")
                    .WithUser("analyst", "analystpw", "Analysts")
                    .WithUser("viewer", "viewerpw", "Viewers");
                _server = new Tm1ServerContainer(Tm1Source.Discover()!).WithCamSecurity(_cognos, "admin");
                _engine = new Tm1EngineContainer(Tm1EngineSource.Discover()!).WithUser("analyst", "Analysts").WithPassThrough(_server);
                await _engine.StartAsync();
                _database = _engine.Database("tm1");
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
            var cognos = _cognos;
            _engine = null;
            _server = null;
            _cognos = null;
            try
            {
                if (engine is not null)
                {
                    await engine.DisposeAsync();
                }
            }
            finally
            {
                try
                {
                    if (server is not null)
                    {
                        await server.DisposeAsync();
                    }
                }
                finally
                {
                    if (cognos is not null)
                    {
                        await cognos.DisposeAsync();
                    }
                }
            }
        }
    }

    private sealed class StrongBox
    {
        public int Value;
    }

    private static Func<CancellationToken, ValueTask<string>> Counting(Func<CancellationToken, ValueTask<string>> passports, StrongBox asked) =>
        ct =>
        {
            Interlocked.Increment(ref asked.Value);
            return passports(ct);
        };

    [RequiresTm1PassThroughCamFact]
    public async Task LogsOnWithCamNamespaceThroughPassThrough()
    {
        await using (var analyst = containers.Client(Tm1Authenticator.CamNamespace("analyst", "analystpw", "LDAP")))
        {
            var user = await analyst.Users.ActiveUserAsync(ODataQuery.None().Expand("Groups"));
            Assert.Equal(Analyst, user.Name);
            Assert.Equal(UserType.User, user.Type);
            Assert.Contains("Analysts", user.Groups!.Select(g => g.Name));
        }

        await using (var admin = containers.Client(Tm1Authenticator.CamNamespace("admin", "adminpw", "LDAP")))
        {
            var user = await admin.Users.ActiveUserAsync();
            Assert.Contains("uid=admin,", user.Name);
            Assert.Equal(UserType.Admin, user.Type);
        }
    }

    [RequiresTm1PassThroughCamFact]
    public async Task LogsOnWithACognosPassportThroughPassThrough()
    {
        await using var analyst = containers.Client(Tm1Authenticator.CognosLogon(containers.Cognos.Url, "LDAP", "analyst", "analystpw"));
        var user = await analyst.Users.ActiveUserAsync(ODataQuery.None().Expand("Groups"));
        Assert.Equal(Analyst, user.Name);
        Assert.Equal(UserType.User, user.Type);
        Assert.Contains("Analysts", user.Groups!.Select(g => g.Name));
    }

    [RequiresTm1PassThroughCamFact]
    public async Task RefusesAWrongPasswordAndACognosUserTheDatabaseLacks()
    {
        var asked = new StrongBox();
        var wrongPassport = containers.Client(
            Tm1Authenticator.CamPassport(Counting(CognosPassports.Password(containers.Cognos.Url, "LDAP", "analyst", "not-it"), asked))
        );
        try
        {
            var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongPassport.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
            await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongPassport.Users.ActiveUserAsync());
            Assert.Equal(1, asked.Value); // Cognos asked once: a refusal isn't retried at once
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrongPassport.DisposeAsync());
        }

        var wrongNamespace = containers.Client(Tm1Authenticator.CamNamespace("analyst", "not-it", "LDAP"));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongNamespace.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrongNamespace.DisposeAsync());
        }

        // Cognos and TM1 11 know viewer; the TM1 v12 database has no such user
        var viewer = containers.Client(Tm1Authenticator.CamNamespace("viewer", "viewerpw", "LDAP"));
        try
        {
            var e = await Assert.ThrowsAsync<Tm1ForbiddenException>(() => viewer.Users.ActiveUserAsync());
            Assert.Equal(Tm1ErrorKind.Forbidden, e.Kind);
        }
        finally
        {
            await LiveModel.Quietly(async () => await viewer.DisposeAsync());
        }
    }
}

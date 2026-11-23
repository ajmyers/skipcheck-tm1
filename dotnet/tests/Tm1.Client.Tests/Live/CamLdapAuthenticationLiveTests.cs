// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Logging on to a TM1 in CAM security through Cognos's LDAP namespace: with the password
/// (<c>CAMNamespace</c>), and with a passport the client gets from Cognos. Cognos takes a couple of
/// minutes to start, so the containers are shared by the class. A port of the Java client's
/// CamLdapAuthenticationTm1Test.</summary>
public sealed class CamLdapAuthenticationLiveTests(CamLdapAuthenticationLiveTests.Containers containers)
    : IClassFixture<CamLdapAuthenticationLiveTests.Containers>
{
    private const string Viewer = "CAMID(\"LDAP:u:uid=viewer,ou=people\")";

    /// <summary>Cognos with two test users, and a TM1 of its own in CAM security: started only when
    /// the tests run (<see cref="RequiresCognosFactAttribute"/>).</summary>
    public sealed class Containers : IAsyncLifetime
    {
        private CognosContainer? _cognos;
        private Tm1ServerContainer? _tm1;
        private Tm1Client? _admin;

        public CognosContainer Cognos => _cognos ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage);

        public Tm1Client Admin => _admin ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage);

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
            _cognos = new CognosContainer(CognosSource.Discover()!).WithUser("admin", "adminpw", "Admins").WithUser("viewer", "viewerpw", "Readers");
            _tm1 = new Tm1ServerContainer(Tm1Source.Discover()!).WithCamSecurity(_cognos, "admin");
            await _tm1.StartAsync();
            _admin = Client(Tm1Authenticator.CamNamespace("admin", "adminpw", "LDAP"));
        }

        public async Task DisposeAsync()
        {
            if (_admin is not null)
            {
                await _admin.DisposeAsync();
            }
            if (_tm1 is not null)
            {
                await _tm1.DisposeAsync();
            }
            if (_cognos is not null)
            {
                await _cognos.DisposeAsync();
            }
        }
    }

    /// <summary>The passports of <paramref name="passports"/>, counting how often one is asked for.</summary>
    private static Func<CancellationToken, ValueTask<string>> Counting(Func<CancellationToken, ValueTask<string>> passports, StrongBox asked) =>
        ct =>
        {
            Interlocked.Increment(ref asked.Value);
            return passports(ct);
        };

    private sealed class StrongBox
    {
        public int Value;
    }

    [RequiresCognosFact]
    public async Task LogsOnWithThePassword()
    {
        await using var viewer = containers.Client(Tm1Authenticator.CamNamespace("viewer", "viewerpw", "LDAP"));
        Assert.Equal(Viewer, (await viewer.Users.ActiveUserAsync()).Name);
    }

    [RequiresCognosFact]
    public async Task LogsOnWithAPassportFromCognosAndAFreshOneAfterTm1DropsTheSession()
    {
        var asked = new StrongBox();
        await using var viewer = containers.Client(
            Tm1Authenticator.CamPassport(Counting(CognosPassports.Password(containers.Cognos.Url, "LDAP", "viewer", "viewerpw"), asked))
        );
        Assert.Equal(Viewer, (await viewer.Users.ActiveUserAsync()).Name);
        var first = await viewer.Session.SessionIdAsync();
        await containers.Admin.Sessions.CloseAsync(first);

        Assert.Equal(Viewer, (await viewer.Users.ActiveUserAsync()).Name); // logged on again
        Assert.NotEqual(first, await viewer.Session.SessionIdAsync());
        Assert.Equal(2, asked.Value); // a fresh passport for the new login
    }

    [RequiresCognosFact]
    public async Task RefusesAWrongPasswordOnceWithoutRetrying()
    {
        var asked = new StrongBox();
        var wrong = containers.Client(
            Tm1Authenticator.CamPassport(Counting(CognosPassports.Password(containers.Cognos.Url, "LDAP", "viewer", "wrong"), asked))
        );
        try
        {
            await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrong.Users.ActiveUserAsync());
            await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrong.Users.ActiveUserAsync());
            Assert.Equal(1, asked.Value); // Cognos asked once: a refusal isn't retried at once
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrong.DisposeAsync());
        }
        var wrongNamespace = containers.Client(Tm1Authenticator.CamNamespace("viewer", "wrong", "LDAP"));
        try
        {
            await Assert.ThrowsAsync<Tm1AuthenticationException>(() => wrongNamespace.Users.ActiveUserAsync());
        }
        finally
        {
            await LiveModel.Quietly(async () => await wrongNamespace.DisposeAsync());
        }
    }
}

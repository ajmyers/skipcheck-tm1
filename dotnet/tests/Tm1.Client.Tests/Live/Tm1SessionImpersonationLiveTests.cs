// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1SessionImpersonationTm1Test</c>.</summary>
public sealed class Tm1SessionImpersonationLiveTests : IDisposable
{
    private static readonly Tm1Request<JsonElement> ActiveUser = Tm1Request.Get<JsonElement>(ODataPath.Of("ActiveUser"), ODataQuery.None().Select("Name"));

    private readonly Lazy<(RawTm1 Raw, string Admin, string Alice)> _setUp = new(() =>
    {
        var raw = new RawTm1();
        var admin = raw.Login();
        var alice = "zz_alice_" + Guid.NewGuid().ToString("N")[..6];
        raw.CreateUser(admin, alice, "a");
        return (raw, admin, alice);
    });

    private RawTm1 Raw => _setUp.Value.Raw;

    private string Admin => _setUp.Value.Admin;

    private string Alice => _setUp.Value.Alice;

    public void Dispose()
    {
        if (_setUp.IsValueCreated)
        {
            Raw.DeleteUser(Admin, Alice);
            Raw.Close(Admin);
            Raw.Dispose();
        }
    }

    [Tm1Fact(V11Only = true)]
    public async Task RunsAsTheImpersonatedUser()
    {
        var session = Session(Alice.ToUpperInvariant());
        try
        {
            Assert.Equal(Alice, (await session.ExecuteAsync(ActiveUser)).GetProperty("Name").GetString());
        }
        finally
        {
            await session.CloseAsync();
        }
    }

    [Tm1Fact(V11Only = true)]
    public async Task RunsAsAUserWhoseNameIsOutsideAscii()
    {
        foreach (var name in new[] { Alice + "_ë", Alice + "_日本" })
        {
            Raw.CreateUser(Admin, name, "a");
            var session = Session(name);
            try
            {
                Assert.Equal(name, (await session.ExecuteAsync(ActiveUser)).GetProperty("Name").GetString());
            }
            finally
            {
                await session.CloseAsync();
                Raw.DeleteUser(Admin, name);
            }
        }
    }

    [Tm1Fact(V11Only = true)]
    public async Task ReportsAnUnknownUser()
    {
        Assert.Equal(Tm1ErrorKind.UnknownUser, await KindOf(Session("zz_nobody")));
    }

    [Tm1Fact(V11Only = true)]
    public async Task ReportsThatAdministratorsCantBeImpersonated()
    {
        Assert.Equal(Tm1ErrorKind.ImpersonationRefused, await KindOf(Session("Admin")));
    }

    [Tm1Fact(V11Only = true)]
    public async Task ReportsADisabledUserAsRefusedCredentials()
    {
        Raw.Send(Raw.Request("PATCH", $"Users('{Alice}')").WithCookie(Admin).WithBody("{\"Enabled\":false}"));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, await KindOf(Session(Alice)));
    }

    /// <summary>Each refused login leaves a session on TM1; a remembered refusal doesn't add more.</summary>
    [Tm1Fact(V11Only = true)]
    public async Task DoesntLogInAgainWhileARefusalIsRemembered()
    {
        var session = Session("zz_nobody");
        Assert.Equal(Tm1ErrorKind.UnknownUser, await KindOf(session));
        var after = Raw.AllSessionCount(Admin);
        for (int i = 0; i < 5; i++)
        {
            Assert.Equal(Tm1ErrorKind.UnknownUser, await KindOf(session));
        }
        Assert.Equal(after, Raw.AllSessionCount(Admin));
    }

    private static Tm1Session Session(string user) => RawTm1.Sessions().Impersonate(user).Build();

    private static async Task<Tm1ErrorKind> KindOf(Tm1Session session) =>
        (await Assert.ThrowsAnyAsync<Tm1ApiException>(() => session.ExecuteAsync(ActiveUser))).Kind;
}

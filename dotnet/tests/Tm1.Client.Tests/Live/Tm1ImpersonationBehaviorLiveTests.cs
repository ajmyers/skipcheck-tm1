using System;
using System.Linq;
using System.Net;
using System.Text;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The Java client's <c>Tm1ImpersonationBehaviorTest</c>: how TM1 itself treats
/// <c>TM1-Impersonate</c>, which the session and pool rely on.</summary>
public sealed class Tm1ImpersonationBehaviorLiveTests : IDisposable
{
    private readonly Lazy<(RawTm1 Raw, string Admin, string Alice, string Bob)> _setUp = new(() =>
    {
        var raw = new RawTm1();
        var admin = raw.Login();
        var run = Guid.NewGuid().ToString("N")[..6];
        var alice = "zz_alice_" + run;
        var bob = "zz_bob_" + run;
        raw.CreateUser(admin, alice, "a");
        raw.CreateUser(admin, bob, "b");
        return (raw, admin, alice, bob);
    });

    private RawTm1 Tm1 => _setUp.Value.Raw;

    private string Admin => _setUp.Value.Admin;

    private string Alice => _setUp.Value.Alice;

    private string Bob => _setUp.Value.Bob;

    public void Dispose()
    {
        if (_setUp.IsValueCreated)
        {
            Tm1.DeleteUser(Admin, Alice);
            Tm1.DeleteUser(Admin, Bob);
            Tm1.Close(Admin);
            Tm1.Dispose();
        }
    }

    /// <summary>Impersonation is decided at login, and the header on later requests is ignored. So
    /// it's a session setting, and one identity means one session.</summary>
    [Tm1Fact]
    public void DecidesTheIdentityAtLoginAndIgnoresTheHeaderAfterwards()
    {
        var login = Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithBasic().WithHeader("TM1-Impersonate", Alice));
        Assert.Equal(HttpStatusCode.OK, login.Status);
        Assert.Equal(Alice, RawTm1.Json(login).GetProperty("Name").GetString());
        var session = RawTm1.CookieOf(login)!;
        try
        {
            Assert.Equal(Alice, Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(session)));
            Assert.Equal(Alice, Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(session).WithHeader("TM1-Impersonate", Bob))); // a different user on a later request
            var plain = Tm1.Login();
            Assert.Equal("Admin", Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(plain).WithHeader("TM1-Impersonate", Alice)), ignoreCase: true);
            Tm1.Close(plain);
        }
        finally
        {
            Tm1.Close(session);
        }
    }

    /// <summary>It also works when logging in the session a 401 opened, as the client's re-login does.</summary>
    [Tm1Fact]
    public void ImpersonatesOnALoginThatReusesThe401sSession()
    {
        var seed = RawTm1.CookieOf(Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name")))!;
        try
        {
            Assert.Equal(Alice, Name(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(seed).WithBasic().WithHeader("TM1-Impersonate", Alice)));
        }
        finally
        {
            Tm1.Close(seed);
        }
    }

    /// <summary>Impersonating a user that doesn't exist is a 400, and a non-administrator trying is a
    /// 403, both with plain-text bodies, which the client reads as the error message.</summary>
    [Tm1Fact]
    public void RefusesUnknownUsersAndNonAdministratorsWithPlainTextErrors()
    {
        var unknown = Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithBasic().WithHeader("TM1-Impersonate", "zz_nobody"));
        Assert.Equal(HttpStatusCode.BadRequest, unknown.Status);
        Assert.StartsWith("Error: Bad Request", unknown.Body);
        Assert.Contains("The user specified in the impersonation request does not exist.", unknown.Body);
        var left = RawTm1.CookieOf(unknown)!;
        Assert.Equal(HttpStatusCode.Unauthorized, Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithCookie(left)).Status); // the session a refused impersonation leaves
        var nonAdmin = Impersonate(Alice, "a", Bob);
        Assert.Equal(HttpStatusCode.Forbidden, nonAdmin.Status);
        Assert.StartsWith("Error: Forbidden", nonAdmin.Body);
        Assert.Contains("You need to be a (data-)administrator to be able to impersonate another user.", nonAdmin.Body);
    }

    /// <summary>Nobody can impersonate an administrator, not even another administrator.</summary>
    [Tm1Fact]
    public void RefusesToImpersonateAnyAdministrator()
    {
        var otherAdmin = Alice + "_adm";
        var dataAdmin = Alice + "_data";
        CreateUser(otherAdmin, "ADMIN");
        CreateUser(dataAdmin, "DataAdmin");
        try
        {
            foreach (
                var (user, password, target) in new[]
                {
                    (Tm1.User, Tm1.Password, otherAdmin),
                    (Tm1.User, Tm1.Password, "Admin"),
                    (dataAdmin, "p", otherAdmin),
                    (dataAdmin, "p", "Admin"),
                }
            )
            {
                var refused = Impersonate(user, password, target);
                Assert.True(refused.Status == HttpStatusCode.Forbidden, $"{user} impersonating {target}: {refused.Status}");
                Assert.Contains("You have insufficient rights to impersonate the user specified!", refused.Body);
            }
        }
        finally
        {
            Tm1.DeleteUser(Admin, otherAdmin);
            Tm1.DeleteUser(Admin, dataAdmin);
        }
    }

    /// <summary>Data administrators can impersonate ordinary users; security and operations
    /// administrators can't, with a different 403 message.</summary>
    [Tm1Fact]
    public void LetsDataAdministratorsImpersonateButNotOtherAdministratorRoles()
    {
        var dataAdmin = Alice + "_data";
        var securityAdmin = Alice + "_sec";
        var operationsAdmin = Alice + "_ops";
        CreateUser(dataAdmin, "DataAdmin");
        CreateUser(securityAdmin, "SecurityAdmin");
        CreateUser(operationsAdmin, "OperationsAdmin");
        try
        {
            var allowed = Impersonate(dataAdmin, "p", Bob);
            Assert.Equal(HttpStatusCode.OK, allowed.Status);
            if (RawTm1.CookieOf(allowed) is { } cookie)
            {
                Tm1.Close(cookie);
            }
            foreach (var role in new[] { securityAdmin, operationsAdmin })
            {
                var refused = Impersonate(role, "p", Bob);
                Assert.True(refused.Status == HttpStatusCode.Forbidden, role);
                Assert.Contains("You need to be a (data-)administrator to be able to impersonate another user.", refused.Body);
            }
        }
        finally
        {
            Tm1.DeleteUser(Admin, dataAdmin);
            Tm1.DeleteUser(Admin, securityAdmin);
            Tm1.DeleteUser(Admin, operationsAdmin);
        }
    }

    /// <summary>Impersonating a disabled user is a 401 with an empty body: exactly what wrong
    /// credentials look like. The client can't tell the two apart, so it reports both.</summary>
    [Tm1Fact]
    public void RefusesADisabledUserLikeWrongCredentials()
    {
        Tm1.Send(Tm1.Request("PATCH", $"Users('{Bob}')").WithCookie(Admin).WithBody("{\"Enabled\":false}"));
        var refused = Impersonate(Tm1.User, Tm1.Password, Bob);
        Assert.Equal(HttpStatusCode.Unauthorized, refused.Status);
        Assert.Empty(refused.Body);
    }

    /// <summary>An empty <c>TM1-Impersonate</c> means no impersonation. So the client must never send a blank name.</summary>
    [Tm1Fact]
    public void TreatsAnEmptyHeaderAsNoImpersonation()
    {
        var login = Impersonate(Tm1.User, Tm1.Password, "");
        try
        {
            Assert.Equal(HttpStatusCode.OK, login.Status);
            Assert.Equal("Admin", RawTm1.Json(login).GetProperty("Name").GetString(), ignoreCase: true);
        }
        finally
        {
            if (RawTm1.CookieOf(login) is { } cookie)
            {
                Tm1.Close(cookie);
            }
        }
    }

    /// <summary>User names match ignoring case and spaces, so the pool compares identities the same way.</summary>
    [Tm1Fact]
    public void MatchesUserNamesIgnoringCaseAndSpaces()
    {
        foreach (var written in new[] { Alice.ToUpperInvariant(), Alice.Replace("_", "_ ", StringComparison.Ordinal) })
        {
            var login = Impersonate(Tm1.User, Tm1.Password, written);
            try
            {
                Assert.True(login.Status == HttpStatusCode.OK, written);
                Assert.Equal(Alice, RawTm1.Json(login).GetProperty("Name").GetString());
            }
            finally
            {
                if (RawTm1.CookieOf(login) is { } cookie)
                {
                    Tm1.Close(cookie);
                }
            }
        }
    }

    /// <summary>A user whose name has characters outside ASCII is impersonated when the header carries
    /// the name as UTF-8, and only then: as ISO 8859-1 bytes or percent-encoded, TM1 reports the user
    /// unknown. Sent over a bare socket, so the bytes are exactly these. The client sends UTF-8
    /// (<c>Tm1Session.Utf8Headers</c>).</summary>
    [Tm1Fact]
    public void ImpersonatesUsersWithNonAsciiNamesOnlyAsUtf8()
    {
        var accented = Alice + "_ë";
        Tm1.CreateUser(Admin, accented, "p");
        try
        {
            var impersonated = Impersonate(Encoding.UTF8.GetBytes(accented));
            if (impersonated.Cookie is { } cookie)
            {
                Tm1.Close(cookie);
            }
            Assert.Equal(200, impersonated.Status);
            Assert.Contains($"\"Name\":\"{accented}\"", impersonated.Text);

            var percentEncoded = Encoding.ASCII.GetBytes(Uri.EscapeDataString(accented));
            foreach (var written in new[] { Encoding.Latin1.GetBytes(accented), percentEncoded })
            {
                var refused = Impersonate(written);
                Assert.Equal(400, refused.Status);
                Assert.Contains("The user specified in the impersonation request does not exist.", refused.Text);
            }
        }
        finally
        {
            Tm1.DeleteUser(Admin, accented);
        }
    }

    private RawTm1.Bytes Impersonate(byte[] target) =>
        Tm1.GetWithHeaderBytes("ActiveUser?$select=Name", RawTm1.Basic(Tm1.User, Tm1.Password), "TM1-Impersonate", target);

    private RawTm1.RawResponse Impersonate(string user, string password, string target) =>
        Tm1.Send(Tm1.Request("GET", "ActiveUser?$select=Name").WithHeader("Authorization", RawTm1.Basic(user, password)).WithHeader("TM1-Impersonate", target));

    private void CreateUser(string name, string group) =>
        Tm1.Send(
            Tm1.Request("POST", "Users").WithCookie(Admin).WithBody($"{{\"Name\":\"{name}\",\"Password\":\"p\",\"Groups@odata.bind\":[\"Groups('{group}')\"]}}")
        );

    private string? Name(RawTm1.RawRequest request) => RawTm1.Json(Tm1.Send(request)).GetProperty("Name").GetString();
}

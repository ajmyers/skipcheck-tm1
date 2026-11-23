using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>CognosContainer</c>, shown in the guide.</summary>
public static class CognosContainerExamples
{
    public static async Task<string?> CamSecurity(CognosSource cognosSource, Tm1Source tm1Source)
    {
        // @start region = cam-security
        await using var cognos = new CognosContainer(cognosSource).WithUser("admin", "adminpw", "Admins").WithUser("viewer", "viewerpw", "Readers");
        await using var server = new Tm1ServerContainer(tm1Source).WithCamSecurity(cognos, "admin");
        await server.StartAsync(); // starts Cognos first
        // Each test user logs in with their own identity, through the LDAP namespace
        await using var viewer = Tm1Client
            .Builder()
            .BaseUrl(server.RestUrl)
            .Authenticator(Tm1Authenticator.CamNamespace("viewer", "viewerpw", CognosContainer.NamespaceId))
            .Build();
        // TM1 knows the user by their CAMID
        return (await viewer.Users.ActiveUserAsync(ODataQuery.None().Select(User.Fields.Name))).Name;
        // @end
    }
}

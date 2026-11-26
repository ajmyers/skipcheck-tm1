// SPDX-License-Identifier: Apache-2.0

using System.Net;
using System.Net.Http;
using System.Threading.Tasks;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;
using Xunit;

namespace Examples;

/// <summary>Examples for <c>[RequiresTm1EngineFact]</c> and <c>Tm1EngineContainer</c>, shown in the guide.</summary>
public static class Tm1EngineExamples
{
    // @start region = test-class
    // A database of this class's own, dropped when the class is done
    public sealed class CubeTests(Tm1DatabaseFixture<CubeTests> tm1) : IClassFixture<Tm1DatabaseFixture<CubeTests>>
    {
        [RequiresTm1EngineFact]
        public async Task ListsCubes()
        {
            var db = tm1.Database;
            // The login's session cookie goes on the request, so the client keeps none of its own
            using var http = new HttpClient(new HttpClientHandler { UseCookies = false });
            using var request = new HttpRequestMessage(HttpMethod.Get, db.RestUrl + "Cubes?$select=Name");
            using var response = await http.SendAsync(await db.Login().AuthorizeAsync(request));
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }
    }

    // @end

    // @start region = shared-database
    // The engine's one shared database instead: nothing to create, but give what you make a unique name
    public sealed class ServerNameTests(Tm1EngineFixture tm1) : IClassFixture<Tm1EngineFixture>
    {
        [RequiresTm1EngineFact]
        public async Task ReadsTheServersName()
        {
            var db = tm1.Database;
            using var http = new HttpClient(new HttpClientHandler { UseCookies = false });
            using var request = new HttpRequestMessage(HttpMethod.Get, db.RestUrl + "Configuration/ServerName/$value");
            using var response = await http.SendAsync(await db.Login().AuthorizeAsync(request));
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }
    }

    // @end

    public static async Task<HttpStatusCode> OAuth2(Tm1EngineSource source)
    {
        // @start region = oauth2
        // Starting the engine starts Keycloak first; the Keycloak is yours to stop
        await using var keycloak = new KeycloakContainer();
        await using var engine = new Tm1EngineContainer(source).WithUser("analyst", "Analysts").WithLogin("analyst", "analystpw").WithOAuth2(keycloak);
        await engine.StartAsync();
        var db = engine.Database("tm1");
        // A token from Keycloak, with the analyst's password, sent to TM1 as Bearer
        using var request = new HttpRequestMessage(HttpMethod.Get, db.RestUrl + "Cubes?$select=Name");
        await db.OAuth2Login("analyst").AuthorizeWithTokenAsync(request);
        // @end
        using var http = new HttpClient(new HttpClientHandler { UseCookies = false });
        using var response = await http.SendAsync(request);
        return response.StatusCode;
    }

    public static async Task<string> Container(Tm1EngineSource source)
    {
        // @start region = container
        await using var engine = new Tm1EngineContainer(source).WithDatabase("planning").WithUser("analyst", "Analysts");
        await engine.StartAsync();
        var planning = engine.Database("planning");
        // http://localhost:54321/tm1/api/v1/Databases('planning')/
        var url = planning.RestUrl;
        var analyst = planning.Login("analyst");
        // ...
        // @end
        return analyst.User + " at " + url;
    }
}

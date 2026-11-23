// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>PlanningAnalyticsEnvironment</c>, shown in the guide.</summary>
public static class PlanningAnalyticsEnvironmentExamples
{
    public static async Task<Dictionary<string, List<string>>> Servers()
    {
        // @start region = servers
        // Two servers on one Admin Server, and TM1 Web listing both
        await using var env = PlanningAnalyticsEnvironment.Builder().Tm1("planning", tm1 => tm1.WithSeed(Tm1Seed.Sdata)).Tm1("reporting").Tm1Web().Build();
        await env.StartAsync(); // TM1 Web at env.Tm1Web().Url, log on with AdminHost
        var cubes = new Dictionary<string, List<string>>();
        foreach (var (name, server) in env.Tm1Servers)
        {
            await using var tm1 = Tm1Client.Builder().BaseUrl(server.RestUrl).BasicAuth(server.Username, server.Password).Build();
            cubes[name] = await tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)).Select(cube => cube.Name!).ToListAsync();
        }
        return cubes; // planning: SData's cubes; reporting: none
        // @end
    }

    public static async Task<List<string>> CamSecurity()
    {
        // @start region = cam-security
        // Every server in CAM security, with Cognos's test users as its users
        await using var env = PlanningAnalyticsEnvironment
            .Builder()
            .Tm1("planning")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw", "Admins").WithUser("viewer", "viewerpw", "Readers"))
            .Build();
        await env.StartAsync();
        await using var viewer = Tm1Client
            .Builder()
            .BaseUrl(env.Tm1("planning").RestUrl)
            .Authenticator(Tm1Authenticator.CamNamespace("viewer", "viewerpw", CognosContainer.NamespaceId))
            .Build();
        // The cubes the viewer's groups may see
        return await viewer.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)).Select(cube => cube.Name!).ToListAsync();
        // @end
    }

    public static async Task<string> Reuse()
    {
        // @start region = reuse
        // Cognos and the server keep running after the run, for the next one, when
        // testcontainers.reuse.enable=true is set too
        await using var env = PlanningAnalyticsEnvironment
            .Builder()
            .Tm1("planning")
            .Cognos(cognos => cognos.WithUser("admin", "adminpw", "Admins"))
            .Reuse(true)
            .Build();
        await env.StartAsync(); // in seconds, once a run with this configuration has started it
        return env.Tm1("planning").RestUrl;
        // disposing leaves it running
        // @end
    }

    public static async Task<string> FromConfig()
    {
        // @start region = file
        // The environment pa-setup runs from the same file, with random host ports
        await using var env = PlanningAnalyticsEnvironment.FromConfig("pa-environment.yaml").Build();
        await env.StartAsync();
        return env.Tm1("planning").RestUrl;
        // @end
    }
}

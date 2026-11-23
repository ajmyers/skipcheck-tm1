// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>Tm1ServerContainer</c>, shown in the guide.</summary>
public static class Tm1ServerContainerExamples
{
    public static Tm1Client Client(Tm1ServerContainer server)
    {
        // @start region = client
        // The container's REST URL and login, in TM1's own security
        return Tm1Client.Builder().BaseUrl(server.RestUrl).BasicAuth(server.Username, server.Password).Build();
        // @end
    }

    public static async Task<List<string>> Sample()
    {
        // @start region = sample
        var source = Tm1Source.Discover() ?? throw new InvalidOperationException("Set TM1_HOME_DIR or TM1_BUNDLE_DIR");
        // SData's login is admin / apple, and the server must be this version
        await using var server = new Tm1ServerContainer(source).WithSeed(Tm1Seed.Sdata).WithExpectedVersion(Tm1Version.Parse("11.8.03500.6"));
        await server.StartAsync();
        // Declared after the server, so the client closes before the container stops
        await using var tm1 = Tm1Client.Builder().BaseUrl(server.RestUrl).BasicAuth(server.Username, server.Password).Build();
        // SData's cubes, such as SalesCube
        return await tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)).Select(cube => cube.Name!).ToListAsync();
        // @end
    }

    public static async Task<string> OwnDatabase(Tm1Source source)
    {
        // @start region = own-database
        // A fixture model (.cub, .dim, .pro files and so on), copied in before TM1 starts
        await using var server = new Tm1ServerContainer(source).WithData("TestData/tm1/sales").WithCredentials("admin", "apple"); // a login the database has
        await server.StartAsync();
        return server.RestUrl; // http://localhost:54321/api/v1/
        // @end
    }
}

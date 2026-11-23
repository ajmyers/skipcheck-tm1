// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>The Getting started example of the guide's overview page.</summary>
public static class HelloWorld
{
    // @start region = hello-world
    public static async Task Main()
    {
        await using var tm1 = Tm1Client
            .Builder()
            .BaseUrl("http://localhost:8010/api/v1/")
            .BasicAuth("admin", "") // tm1-server's default login
            .Build();

        Console.WriteLine($"Hello from TM1 {await tm1.Session.VersionAsync()}");
        await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)))
        {
            Console.WriteLine($"  {cube.Name}");
        }
    }
    // @end
}

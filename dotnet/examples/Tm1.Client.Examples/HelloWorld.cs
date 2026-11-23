// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>The quick start's example on tm1-client's landing page. The live tests run <see cref="Hello"/> against
/// a new tm1-server and record what it prints (HelloWorldLiveTests), which the page shows under the code.</summary>
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
        await Hello(tm1);
    }

    public static async Task Hello(Tm1Client tm1)
    {
        Console.WriteLine($"Hello from TM1 {await tm1.Session.VersionAsync()}");
        await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)))
        {
            Console.WriteLine($"  {cube.Name}");
        }
    }
    // @end
}

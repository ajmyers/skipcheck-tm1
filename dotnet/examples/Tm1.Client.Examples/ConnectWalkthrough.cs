// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>The walkthrough "Connect and list your cubes" (api-site/content/walkthroughs/tm1-client). The live
/// tests run its steps against IBM's SData sample and record what they print (WalkthroughExamplesLiveTests).</summary>
public static class ConnectWalkthrough
{
    public static async Task Main()
    {
        var tm1 = Connect();
        try
        {
            await PrintVersion(tm1);
            await ListCubes(tm1);
        }
        finally
        {
            await Close(tm1);
        }
    }

    public static Tm1Client Connect()
    {
        // @start region = connect
        // The SData sample's address and login
        var tm1 = Tm1Client.Builder().BaseUrl("http://localhost:8011/api/v1/").BasicAuth("admin", "apple").Build();
        // @end
        return tm1;
    }

    public static async Task PrintVersion(Tm1Client tm1)
    {
        // @start region = version
        // Each call returns a Task, or an IAsyncEnumerable for a collection, which you await for its result
        var version = await tm1.Session.VersionAsync();
        Console.WriteLine($"Connected to TM1 {version}");
        // @end
    }

    public static async Task ListCubes(Tm1Client tm1)
    {
        // @start region = list-cubes
        // The cubes you made, without TM1's control cubes; Select asks for their names alone
        await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)))
        {
            Console.WriteLine(cube.Name);
        }
        // @end
    }

    public static async Task Close(Tm1Client tm1)
    {
        // @start region = close
        // Ends the session on the server
        await tm1.CloseAsync();
        // @end
    }
}

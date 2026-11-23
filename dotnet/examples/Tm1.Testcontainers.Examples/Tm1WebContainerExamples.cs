// SPDX-License-Identifier: Apache-2.0

using System.Threading.Tasks;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>Tm1WebContainer</c>, shown in the guide. Test code, never run here.</summary>
public static class Tm1WebContainerExamples
{
    public static async Task<string> Start(Tm1Source tm1Source, Tm1WebSource webSource)
    {
        // @start region = start
        await using var tm1 = new Tm1ServerContainer(tm1Source).WithSeed(Tm1Seed.Sdata);
        await using var web = new Tm1WebContainer(webSource).WithTm1Server(tm1);
        await web.StartAsync(); // starts tm1 first
        return web.Url; // http://localhost:54321/tm1web/
        // @end
    }
}

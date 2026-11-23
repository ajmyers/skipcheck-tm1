// SPDX-License-Identifier: Apache-2.0

using System.Threading.Tasks;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>PawContainer</c>, shown in the guide. Test code, never run here.</summary>
public static class PawContainerExamples
{
    public static async Task<string> Start(Tm1Source tm1Source, PawSource pawSource)
    {
        // @start region = start
        await using var tm1 = new Tm1ServerContainer(tm1Source);
        await using var paw = new PawContainer(pawSource).WithTm1Server(tm1).AcceptLicense();
        await paw.StartAsync(); // starts tm1 first
        return paw.Url; // http://localhost:54321/
        // @end
    }
}

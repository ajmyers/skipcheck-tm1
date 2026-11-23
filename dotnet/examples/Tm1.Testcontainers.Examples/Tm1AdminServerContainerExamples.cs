// SPDX-License-Identifier: Apache-2.0

using System.Threading.Tasks;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>Tm1AdminServerContainer</c>, shown in the guide. Test code, never run here.</summary>
public static class Tm1AdminServerContainerExamples
{
    public static async Task<string> SeveralServers(Tm1Source tm1Source)
    {
        // @start region = several-servers
        await using var admin = new Tm1AdminServerContainer(tm1Source);
        await using var planning = new Tm1ServerContainer(tm1Source).WithEnv("TM1_SERVER_NAME", "planning").WithAdminServer(admin);
        await using var reporting = new Tm1ServerContainer(tm1Source).WithEnv("TM1_SERVER_NAME", "reporting").WithAdminServer(admin);
        await planning.StartAsync();
        await reporting.StartAsync();
        return admin.RestUrl + "Servers"; // lists planning and reporting
        // @end
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Examples;

/// <summary>Examples for <c>ServerService</c>, shown in the guide.</summary>
public static class ServerServiceExamples
{
    public static async Task<string> Version(Tm1Client tm1)
    {
        // @start region = version
        var server = await tm1.Server.ServerAsync(ODataQuery.None().Select(Server.Fields.Name, Server.Fields.ProductVersion));
        return $"{server.Name} runs TM1 {server.ProductVersion}";
        // @end
    }

    public static async Task<string?> DataDirectory(Tm1Client tm1)
    {
        // @start region = data-directory
        // What the server runs with now, which can differ from tm1s.cfg until it restarts
        var settings = await tm1.Server.ActiveConfigurationAsync();
        return settings.Administration?.DataBaseDirectory;
        // @end
    }

    public static async Task LoggerLevel(Tm1Client tm1)
    {
        // @start region = logger-level
        // Takes effect at once, without a restart
        await tm1.Server.SetLoggerLevelAsync("TM1.Process", LogLevel.Debug);
        // @end
    }
}

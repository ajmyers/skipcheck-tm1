// SPDX-License-Identifier: Apache-2.0

using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Session;
using Skipcheck.Tm1.Client.Version;

namespace Examples;

/// <summary>Examples for <c>Tm1Capability</c> and <c>Tm1Capabilities</c>, shown in the guide.</summary>
public static class Tm1CapabilitiesExamples
{
    public static async Task<bool> Supports(Tm1Session session)
    {
        // @start region = supports
        // Does the server's $metadata define the cube's tm1.Reserve action?
        var reserve = Tm1Capability.BoundOperation("tm1.Cube", "tm1.Reserve").Since("11.8.34");
        return (await session.CapabilitiesAsync()).Supports(reserve);
        // @end
    }
}

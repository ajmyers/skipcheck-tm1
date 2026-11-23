// SPDX-License-Identifier: Apache-2.0

using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;

namespace Examples;

/// <summary>Examples for <c>OidcKeyService</c>, shown in the guide.</summary>
public static class OidcKeyServiceExamples
{
    public static async Task Refresh(Tm1Client tm1)
    {
        // @start region = refresh
        // After the provider rotates its signing keys: drop every cached key, so TM1
        // fetches them again
        var keys = await tm1.OidcKeys.KeysAsync().ToListAsync();
        await tm1.OidcKeys.RemoveAsync(keys);
        // @end
    }
}

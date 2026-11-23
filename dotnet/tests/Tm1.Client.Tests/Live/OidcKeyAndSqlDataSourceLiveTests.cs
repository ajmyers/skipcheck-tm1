// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using ServerThread = Skipcheck.Tm1.Client.Model.Thread;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>OidcKeyService and SqlDataSourceService against a real TM1: the test server checks no
/// OIDC tokens and its host has no ODBC sources, so both are empty; the test pins how TM1 says so.
/// Java's <c>OidcKeyAndSqlDataSourceTm1Test</c>.</summary>
public sealed class OidcKeyAndSqlDataSourceLiveTests
{
    [Tm1Fact]
    public async Task ReadsAndClearsTheOidcKeyCache()
    {
        await using var tm1 = Live.Client();
        Assert.Empty(await tm1.OidcKeys.KeysAsync().ToListAsync());
        await tm1.OidcKeys.RemoveAsync([]);
        // TM1 answers 400 ObjectNotFound, read as not found
        var e = await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.OidcKeys.RemoveAsync(["not-cached"]));
        Assert.Contains("ObjectNotFound", e.Message, StringComparison.Ordinal);
    }

    [Tm1Fact]
    public async Task ListsTheHostsOdbcSources()
    {
        await using var tm1 = Live.Client();
        Assert.Empty(await tm1.SqlDataSources.ListAsync().ToListAsync());
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.SqlDataSources.GetAsync("none"));
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Member sets, on the server's own control dimension }Clients: Java's MemberSetServiceTm1Test.</summary>
public sealed class MemberSetServiceLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync() => await _tm1.DisposeAsync();

    [Tm1Fact]
    public async Task KeepsASetExpressionsResultByOrdinalButListsNone()
    {
        var set = await _tm1.MemberSets.ExecuteAsync(
            "{TM1SUBSETALL([}Clients].[}Clients])}",
            ODataQuery.None().Expand("Tuples", t => t.Expand("Members", m => m.Select("Name")))
        );
        Assert.Contains(set.Tuples!, t => t.Members!.Select(m => m.Name).SequenceEqual(["Admin"]));
        var ordinal = set.Ordinal!.Value;
        Assert.Equal(set.Cardinality, (await _tm1.MemberSets.GetAsync(ordinal)).Cardinality);
        Assert.Empty(await _tm1.MemberSets.ListAsync().ToListAsync()); // TM1 lists none
        await _tm1.MemberSets.DeleteAsync(ordinal);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.MemberSets.GetAsync(ordinal));
    }
}

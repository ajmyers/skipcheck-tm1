// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>MemberService against a real TM1, on a dimension of three levels: a port of the Java client's
/// MemberServiceTm1Test.</summary>
public class MemberServiceLiveTests
{
    private static Task<LiveDimension.Made> World() =>
        LiveDimension.Made.Of(n =>
            LiveDimension.DimensionOf(
                n,
                [
                    LiveDimension.Consolidated("World"),
                    LiveDimension.Consolidated("Europe"),
                    LiveDimension.Consolidated("Asia"),
                    LiveDimension.Numeric("France"),
                    LiveDimension.Numeric("Spain"),
                    LiveDimension.Numeric("Japan"),
                ],
                [
                    LiveDimension.Edge("World", "Europe"),
                    LiveDimension.Edge("World", "Asia"),
                    LiveDimension.Edge("Europe", "France"),
                    LiveDimension.Edge("Europe", "Spain"),
                    LiveDimension.Edge("Asia", "Japan"),
                ]
            )
        );

    [Tm1Fact]
    public async Task NavigatesMembers()
    {
        await using var made = await World();
        var (members, d) = (made.Tm1.Members, made.Name);
        Assert.Equal(6, (await members.ListAsync(d, d).ToListAsync()).Count);
        Assert.Equal($"[{d}].[{d}].[Europe]", (await members.GetAsync(d, d, "Europe")).UniqueName);
        Assert.Equal("Europe", (await members.ParentAsync(d, d, "France"))!.Name);
        Assert.Null(await members.ParentAsync(d, d, "World")); // a root has none
        Assert.Equal(["France", "Spain"], LiveDimension.Names(await members.ChildrenAsync(d, d, "Europe").ToListAsync()));
        Assert.Equal(ElementType.Numeric, (await members.ElementAsync(d, d, "Japan")).Type);
        Assert.Equal(2, (await members.LevelAsync(d, d, "Japan")).Number);
        Assert.Equal(d, (await members.HierarchyAsync(d, d, "Japan")).Name);
    }
}

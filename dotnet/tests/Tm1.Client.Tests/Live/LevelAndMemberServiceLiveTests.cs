// SPDX-License-Identifier: Apache-2.0

using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>LevelService against a real TM1, on a dimension of three levels: a port of the Java client's
/// LevelAndMemberServiceTm1Test.</summary>
public class LevelAndMemberServiceLiveTests
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
    public async Task ListsLevelsAndTheirMembers()
    {
        await using var made = await World();
        var (tm1, d) = (made.Tm1, made.Name);
        var levels = await tm1.Levels.ListAsync(d, d).ToListAsync();
        Assert.Equal([0, 1, 2], levels.Select(l => l.Number!.Value));
        Assert.Equal([1, 2, 3], levels.Select(l => l.Cardinality!.Value));
        Assert.Equal(3, (await tm1.Levels.GetAsync(d, d, 2)).Cardinality);
        Assert.Equal(
            ["France", "Japan", "Spain"],
            LiveDimension.Names(await tm1.Levels.MembersAsync(d, d, 2, ODataQuery.None().Select(Member.Fields.Name)).ToListAsync()).Order()
        );
        Assert.Equal(d, (await tm1.Levels.HierarchyAsync(d, d, 0)).Name);
    }
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>ElementService against a real TM1: a port of the Java client's ElementServiceTm1Test.</summary>
public class ElementServiceLiveTests
{
    private static Task<LiveDimension.Made> ElementsDimension() =>
        LiveDimension.Made.Of(n =>
            LiveDimension.DimensionOf(
                n,
                [
                    LiveDimension.Consolidated("Total"),
                    LiveDimension.Numeric("North"),
                    LiveDimension.Numeric("South"),
                    LiveDimension.Numeric("East"),
                    LiveDimension.Text("Label"),
                ],
                [LiveDimension.Edge("Total", "North"), LiveDimension.Edge("Total", "South")]
            )
        );

    private static async Task<List<string>> Components(Tm1Client tm1, string d, string parent) =>
        LiveDimension.Names(await tm1.Elements.ComponentsAsync(d, d, parent).ToListAsync());

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesAnElement()
    {
        await using var made = await ElementsDimension();
        var (elements, d) = (made.Tm1.Elements, made.Name);
        Assert.Equal("West", (await elements.CreateAsync(d, d, LiveDimension.Numeric("West"))).Name);
        Assert.Equal(
            new[] { "Total", "North", "South", "East", "Label", "West" }.Order(),
            LiveDimension.Names(await elements.ListAsync(d, d, ODataQuery.None().Select(Element.Fields.Name)).ToListAsync()).Order()
        );
        var etag = (await elements.GetAsync(d, d, "West")).ETag!;
        await elements.UpdateAsync(d, d, "West", ElementPatch.Builder().Type(ElementType.String).Build(), etag);
        Assert.Equal(ElementType.String, (await elements.GetAsync(d, d, "West")).Type);
        await Assert.ThrowsAsync<Tm1PreconditionFailedException>(() =>
            elements.UpdateAsync(d, d, "West", ElementPatch.Builder().Type(ElementType.Numeric).Build(), etag)
        );
        await elements.DeleteAsync(d, d, "West");
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => elements.GetAsync(d, d, "West"));
    }

    [Tm1Fact]
    public async Task RefusesNameAndAttributesInAnUpdateAsTm1IgnoresOrRejectsThem()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Contains(
            "renamed",
            Assert
                .Throws<ArgumentException>(() =>
                {
                    _ = tm1.Elements.UpdateAsync(d, d, "East", ElementPatch.Builder().Name("Est").Build());
                })
                .Message
        );
        Assert.Throws<ArgumentException>(() =>
        {
            _ = tm1.Elements.UpdateAsync(d, d, "East", ElementPatch.Builder().Name("Est").Build(), "W/\"x\"");
        });
        var east = ODataPath.Of("Dimensions").Key(d).Nav("Hierarchies").Key(d).Nav("Elements").Key("East");
        await tm1.Session.ExecuteAsync(Tm1Request.Patch(east, new Dictionary<string, object?> { ["Name"] = "Est" }));
        Assert.Equal("East", (await tm1.Elements.GetAsync(d, d, "East")).Name); // TM1 ignores the new name
        await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
            tm1.Session.ExecuteAsync(
                Tm1Request.Patch(east, new Dictionary<string, object?> { ["Attributes"] = new Dictionary<string, object?> { ["Caption"] = "E" } })
            )
        );
    }

    [Tm1Fact]
    public async Task NavigatesParentsComponentsEdgesAndTheHierarchy()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        Assert.Equal(["Total"], LiveDimension.Names(await tm1.Elements.ParentsAsync(d, d, "North").ToListAsync()));
        Assert.Equal(["North", "South"], await Components(tm1, d, "Total"));
        Assert.Equal(
            [("Total", "North"), ("Total", "South")],
            (await tm1.Elements.EdgesAsync(d, d, "Total").ToListAsync()).Select(e => (e.ParentName, e.ComponentName))
        );
        Assert.Equal(d, (await tm1.Elements.HierarchyAsync(d, d, "North")).Name);
    }

    [Tm1Fact]
    public async Task SetsComponentsLastOrBeforeAnotherWithAWeight()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Elements.SetComponentAsync(d, d, "Total", "East");
        Assert.Equal(["North", "South", "East"], await Components(tm1, d, "Total")); // no Before puts it last
        await tm1.Elements.CreateAsync(d, d, LiveDimension.Numeric("West"));
        await tm1.Elements.SetComponentAsync(d, d, "Total", "West", "North", 2.0);
        Assert.Equal(["West", "North", "South", "East"], await Components(tm1, d, "Total"));
        Assert.Equal(2.0, (await tm1.Edges.GetAsync(d, d, "Total", "West")).Weight);
        await tm1.Elements.CreateAsync(d, d, LiveDimension.Numeric("Far"));
        await tm1.Elements.SetComponentAsync(d, d, "Total", "Far", -1.0);
        Assert.Equal(-1.0, (await tm1.Edges.GetAsync(d, d, "Total", "Far")).Weight);
    }

    [Tm1Fact]
    public async Task LocksAndReservesAnElement()
    {
        await using var made = await ElementsDimension();
        var (tm1, d) = (made.Tm1, made.Name);
        await tm1.Elements.LockAsync(d, d, "North");
        await tm1.Elements.UnlockAsync(d, d, "North");
        await tm1.Elements.ReserveAsync(d, d, "North");
        await tm1.Elements.ReleaseAsync(d, d, "North");
    }
}

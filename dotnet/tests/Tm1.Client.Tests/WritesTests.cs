// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text.Json;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Xunit;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The create types and <c>Builder(read)</c>: a write made from a read entity holds only
/// what TM1 applies, by the probe (<c>tm1-probe-&lt;schema&gt;.json</c>'s writes). As the Java
/// WritesTest.</summary>
public class WritesTests
{
    private static string[] Names(Tm1Patch write)
    {
        using var doc = JsonDocument.Parse(JsonSerializer.Serialize(write, Tm1Json.Options));
        return doc.RootElement.EnumerateObject().Select(p => p.Name).Order().ToArray();
    }

    [Fact]
    public void CreatesFromAReadEntityWhatAPostApplies()
    {
        var read = JsonSerializer.Deserialize<Element>(
            """{"Name": "North", "Type": "Consolidated", "Index": 4, "Level": 1, "Attributes": {"Caption": "North"}}""",
            Tm1Json.Options
        )!;
        // TM1 ignores Index, Level and Attributes in a create: they aren't offered, nor copied
        Assert.Equal(["Name", "Type"], Names(ElementCreate.Builder(read).Build()));
        Assert.Contains("\"Type\":\"Consolidated\"", JsonSerializer.Serialize<Tm1Patch>(ElementCreate.Builder(read).Build(), Tm1Json.Options));
        // A builder, to change what's copied
        Assert.Contains("\"Name\":\"South\"", JsonSerializer.Serialize<Tm1Patch>(ElementCreate.Builder(read).Name("South").Build(), Tm1Json.Options));
    }

    [Fact]
    public void PatchesFromAReadEntityLeavingOutWhatAPatchRejectsOrAppends()
    {
        var read = JsonSerializer.Deserialize<Chore>(
            """{"Name": "Nightly", "Active": true, "Frequency": "P1DT00H00M00S", "Attributes": {"Caption": "Nightly"}, "Tasks": [{"Step": 0, "Parameters": [{"Name": "pValue", "Value": "1"}]}]}""",
            Tm1Json.Options
        )!;
        // A PATCH refuses Attributes, and appends Tasks: neither is copied
        Assert.Equal(["Active", "Frequency", "Name"], Names(ChorePatch.Builder(read).Build()));
        // A create ignores Attributes too (they're written through the attribute cubes). Tasks is a
        // navigation: none is copied, as a task's Process would have to be bound again
        var create = Names(ChoreCreate.Builder(read).Build());
        Assert.Contains("Frequency", create);
        Assert.DoesNotContain("Attributes", create);
        Assert.DoesNotContain("Tasks", create);
    }

    [Fact]
    public void RefusesACreateWithoutWhatTm1Needs()
    {
        var error = Assert.Throws<InvalidOperationException>(() => CubeCreate.Builder().Name("Sales").Build());
        Assert.Contains("Dimensions", error.Message);
        var dimensions = ODataPath.Of("Dimensions");
        var create = CubeCreate.Builder().Name("Sales").BindDimensions(dimensions.Key("Region"), dimensions.Key("Month")).Build();
        Assert.Contains("Dimensions@odata.bind", Names(create));
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.IO;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The Java client's <c>Tm1CapabilitiesTest</c>: capability checks against the committed
/// snapshot, and against an older schema. Java makes the older schema from the snapshot with its
/// &lt;c&gt;ApproximateSchemas&lt;/c&gt; tooling, which this client doesn't have: here it's written out, with
/// what the test needs of schema 11.8.20 (cubes, without &lt;c&gt;Cube.Reserve&lt;/c&gt;).</summary>
public class Tm1CapabilitiesTests
{
    private static readonly Tm1Capability Reserve = Tm1Capability.BoundOperation("tm1.Cube", "tm1.Reserve").Since("11.8.34");

    private static readonly Tm1Capabilities Current = Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot), Tm1Version.Parse("11.8.03500.6"));

    private static readonly Tm1Capabilities Older = Tm1Capabilities.Parse(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx"><edmx:DataServices>
          <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
            <Annotation Term="Core.SchemaVersion"><String>11.8.20</String></Annotation>
            <EntityType Name="Cube"><Key><PropertyRef Name="Name"/></Key><Property Name="Name" Type="Edm.String"/></EntityType>
            <Action Name="Load" IsBound="true"><Parameter Name="Cube" Type="tm1.Cube"/></Action>
            <EntityContainer Name="API"><EntitySet Name="Cubes" EntityType="tm1.Cube"/></EntityContainer>
          </Schema></edmx:DataServices></edmx:Edmx>
        """
    );

    [Fact]
    public void FindsEachKindOfElement()
    {
        Assert.True(Current.Supports(Tm1Capability.EntitySet("Cubes")));
        Assert.False(Current.Supports(Tm1Capability.EntitySet("Bananas")));
        Assert.True(Current.Supports(Tm1Capability.Singleton("ActiveSession")));
        Assert.False(Current.Supports(Tm1Capability.Singleton("Cubes"))); // an entity set isn't a singleton
        Assert.True(Current.Supports(Tm1Capability.UnboundOperation("ExecuteMDX")));
        Assert.True(Current.Supports(Tm1Capability.UnboundOperation("ControlCubes"))); // a function import
        Assert.True(Current.Supports(Reserve));
        Assert.True(Current.Supports(Tm1Capability.BoundOperation("ibm.tm1.api.v1.Cube", "ibm.tm1.api.v1.Reserve"))); // by namespace rather than alias
        Assert.False(Current.Supports(Tm1Capability.BoundOperation("tm1.Dimension", "tm1.CheckRules"))); // bound elsewhere
        Assert.True(Current.Supports(Tm1Capability.Property("tm1.Cube", "Rules")));
        Assert.True(Current.Supports(Tm1Capability.Property("tm1.Cube", "Views"))); // a navigation property
        Assert.False(Current.Supports(Tm1Capability.Property("tm1.Cube", "Colour")));
    }

    [Fact]
    public void FindsOperationsAndPropertiesOfBaseTypes()
    {
        Assert.True(Current.Supports(Tm1Capability.BoundOperation("tm1.Folder", "tm1.Copy"))); // Entry.Copy on a Folder
        Assert.True(Current.Supports(Tm1Capability.Property("tm1.NativeView", "Name"))); // View.Name on a NativeView
    }

    [Fact]
    public void ReportsWhatAnOlderServerLacksComparingSchemaVersions()
    {
        Assert.Equal("11.8.20", Older.SchemaVersion);
        Assert.False(Older.Supports(Reserve));
        Assert.True(Older.Supports(Tm1Capability.EntitySet("Cubes")));

        var e = Older.Check(Reserve, "http://tm1:8010/api/v1/");
        Assert.NotNull(e);
        Assert.Equal("Cube.Reserve", e.Missing);
        Assert.Equal(
            "Cube.Reserve isn't defined by the TM1 server at http://tm1:8010/api/v1/: it was added in schema 11.8.34, and the server reports schema 11.8.20",
            e.Message
        );
        Assert.False(e.MayHaveApplied());
        Assert.Null(Current.Check(Reserve, "x"));
    }

    [Fact]
    public void SaysWhatItKnowsWhenTheAddedVersionIsntRecorded() =>
        Assert.Equal(
            "entity set Bananas isn't defined by the TM1 server at http://tm1:8010/api/v1/ (the server reports schema 11.8.35, product version 11.8.03500.6)",
            Current.Check(Tm1Capability.EntitySet("Bananas"), "http://tm1:8010/api/v1/")?.Message
        );
}

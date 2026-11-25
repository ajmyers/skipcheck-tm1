// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>A scenario's <c>$patch</c> goes through the generated setters (#354): what a caller can't
/// build, a scenario can't send. As the Java client's PatchBuildersTest.</summary>
public class PatchBuildersTests
{
    private static JsonObject Json(string text) => JsonNode.Parse(text)!.AsObject();

    private static JsonNode Sent(object patch) => JsonNode.Parse(JsonSerializer.Serialize(patch, patch.GetType(), Tm1Json.Options))!;

    [Fact]
    public void BuildsAWriteWithItsSettersAndBindsEachPathOnce()
    {
        var built = PatchBuilders.Build(typeof(CubeCreate), Json("""{"Name": "c", "Rules": null, "Dimensions@odata.bind": ["Dimensions('%7DClients')"]}"""));
        var made = CubeCreate.Builder().Name("c").Rules(null).BindDimensions(ODataPath.Of("Dimensions").Key("}Clients")).Build();
        Assert.True(JsonNode.DeepEquals(Sent(made), Sent(built)));
        Assert.Equal("Dimensions('%7DClients')", Sent(built)["Dimensions@odata.bind"]![0]!.GetValue<string>());
    }

    [Fact]
    public void RefusesAKeyNoSetterWrites()
    {
        var e = Assert.Throws<ArgumentException>(() => PatchBuilders.Build(typeof(CubeCreate), Json("""{"Name": "c", "Nope": 1}""")));
        Assert.Equal("CubeCreate has no setter for Nope", e.Message);
    }

    [Fact]
    public void BuildsANestedValueWithItsOwnBuilderAndARootsBySubtype()
    {
        var built = PatchBuilders.Build(
            typeof(ServerSettingsPatch),
            Json("""{"EncryptionAtRest": {"@odata.type": "#ibm.tm1.api.v1.VaultSettings", "KeyRef": "k"}}""")
        );
        var value = ((ServerSettingsPatch)built).Values.Single(v => v.Key == "EncryptionAtRest").Value;
        Assert.IsType<VaultSettingsPatch>(value);
    }

    [Fact]
    public void SetsAnOpenTypesUndeclaredPropertyByName()
    {
        var built = PatchBuilders.Build(typeof(EncryptionAtRestProviderSettingsPatch), Json("""{"KeyRef": "k", "Extra": "x"}"""));
        Assert.Equal("x", ((EncryptionAtRestProviderSettingsPatch)built).Values.Single(v => v.Key == "Extra").Value);
    }

    [Fact]
    public void TakesABindingsPathAsWrittenEncoded()
    {
        // A key's %2F is a slash in the key, not between segments
        var built = PatchBuilders.Build(typeof(CubeCreate), Json("""{"Name": "c", "Dimensions@odata.bind": ["Dimensions('a%2Fb')"]}"""));
        Assert.Equal("Dimensions('a%2Fb')", Sent(built)["Dimensions@odata.bind"]![0]!.GetValue<string>());
        var e = Assert.Throws<ArgumentException>(() =>
            PatchBuilders.Build(typeof(CubeCreate), Json("""{"Name": "c", "Dimensions@odata.bind": "Dimensions('d')"}"""))
        );
        Assert.Contains("binds a list of paths", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void RefusesADeclaredKeyNoSetterWritesOnAnOpenType()
    {
        // NativeView is open, but declares Name, which its patch doesn't offer
        var e = Assert.Throws<ArgumentException>(() =>
            PatchBuilders.Build(typeof(NativeViewPatch), Json("""{"@odata.type": "#ibm.tm1.api.v1.NativeView", "Name": "renamed"}"""))
        );
        Assert.Equal("NativeViewPatch has no setter for Name", e.Message);
        // Nor a declared navigation's binding
        var bound = Assert.Throws<ArgumentException>(() =>
            PatchBuilders.Build(typeof(NativeViewPatch), Json("""{"@odata.type": "#ibm.tm1.api.v1.NativeView", "Cube@odata.bind": "Cubes('x')"}"""))
        );
        Assert.Equal("NativeViewPatch has no setter for Cube@odata.bind", bound.Message);
    }

    [Fact]
    public void RefusesAValueThatDoesntReadBackAsWritten()
    {
        var mode = Assert.Throws<ArgumentException>(() => PatchBuilders.Build(typeof(ChoreCreate), Json("""{"Name": "c", "ExecutionMode": "Nope"}""")));
        Assert.Contains("doesn't read back as written", mode.Message, StringComparison.Ordinal);
        // A flags enum's setter takes text, so its members are checked by the spec
        var flags = Assert.Throws<ArgumentException>(() => PatchBuilders.Build(typeof(NetworkSettingsPatch), Json("""{"IPVersion": "Nope"}""")));
        Assert.Contains("doesn't read back as written", flags.Message, StringComparison.Ordinal);
        var type = Assert.Throws<ArgumentException>(() =>
            PatchBuilders.Build(typeof(CubeCreate), Json("""{"@odata.type": "#ibm.tm1.api.v1.Cube", "Name": "c"}"""))
        );
        Assert.Contains("doesn't send @odata.type", type.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void KeepsADateTimesOffset()
    {
        var built = PatchBuilders.Build(typeof(ChoreCreate), Json("""{"Name": "c", "StartTime": "2026-03-04T05:06:07.5+02:00"}"""));
        Assert.Equal("2026-03-04T05:06:07.5+02:00", Sent(built)["StartTime"]!.GetValue<string>());
    }

    [Fact]
    public void RefusesABindingPathThatIsntEncoded()
    {
        // As written, it goes out as it is: so it must be as TM1 gets it, as a caller's path is
        foreach (var path in new[] { "Dimensions('a b')", "Dimensions('100%')", "ApplicationContextFacets('}Cubes')" })
        {
            var json = new JsonObject { ["Name"] = "c", ["Dimensions@odata.bind"] = new JsonArray(path) };
            var e = Assert.Throws<ArgumentException>(() => PatchBuilders.Build(typeof(CubeCreate), json));
            Assert.Contains("isn't encoded as TM1 gets it", e.Message, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void RefusesAValueOfTheWrongShape()
    {
        // One value where the setter takes a list, and a list where it takes one
        var list = Assert.Throws<ArgumentException>(() =>
            PatchBuilders.Build(typeof(AnnotationCreate), Json("""{"Text": "t", "ApplicationContext": {"Value": "x"}}"""))
        );
        Assert.Contains("takes a list", list.Message, StringComparison.Ordinal);
        var one = Assert.Throws<ArgumentException>(() =>
            PatchBuilders.Build(typeof(ChoreCreate), Json("""{"Name": "c", "ExecutionMode": ["SingleCommit"]}"""))
        );
        Assert.Contains("takes one value", one.Message, StringComparison.Ordinal);
    }
}

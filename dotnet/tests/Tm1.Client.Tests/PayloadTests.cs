// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Xml;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using ModelThread = Skipcheck.Tm1.Client.Model.Thread;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The payloads the Java client captured from a real TM1 read into the model and write back
/// the same, as its PayloadRoundTripTest: control information other than <c>@odata.etag</c> and
/// <c>@odata.type</c> aside, nulls aside, values compared by meaning.</summary>
public class PayloadTests
{
    public static IEnumerable<object[]> Payloads() =>
        new (string, Type)[]
        {
            ("server", typeof(Server)),
            ("configuration", typeof(Configuration)),
            ("active-session", typeof(Model.Session)),
            ("users", typeof(User)),
            ("groups", typeof(Group)),
            ("dimension", typeof(Dimension)),
            ("members", typeof(Member)),
            ("cube", typeof(Cube)),
            ("views", typeof(View)),
            ("process", typeof(Process)),
            ("chore", typeof(Chore)),
            ("threads", typeof(ModelThread)),
            ("cellset", typeof(Cellset)),
        }.Select(p => new object[] { p.Item1, p.Item2 });

    [Theory]
    [MemberData(nameof(Payloads))]
    public void DecodesAndEncodesBackToTheSameJson(string payload, Type type)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(Repo.Payloads, payload + ".json")));
        var items = doc.RootElement.TryGetProperty("value", out var v) && v.ValueKind == JsonValueKind.Array ? v.EnumerateArray().ToList() : [doc.RootElement];
        Assert.NotEmpty(items);
        var differences = new List<string>();
        var unknown = new SortedSet<string>();
        for (int i = 0; i < items.Count; i++)
        {
            var value = Tm1Json.Read(items[i], type);
            Assert.IsAssignableFrom(type, value);
            var written = JsonSerializer.SerializeToElement(value, value!.GetType(), Tm1Json.Options);
            Compare(items[i], written, $"[{i}]", differences, unknown);
        }
        Assert.True(differences.Count == 0, string.Join("\n", differences.Take(15)));
        Assert.True(unknown.Count == 0, "unknown: " + string.Join(", ", unknown.Take(15)));
    }

    [Fact]
    public void ChoosesASubtypeByOdataType()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(Repo.Payloads, "views.json")));
        var views = doc.RootElement.GetProperty("value").EnumerateArray().Select(Tm1Json.Read<View>).ToList();
        Assert.All(views, v => Assert.True(v is NativeView or MDXView));
        Assert.IsType<MDXView>(Tm1Json.Read<View>(JsonDocument.Parse("""{"@odata.type": "#tm1.MDXView", "Name": "a"}""").RootElement));
        var unknown = Tm1Json.Read<View>(JsonDocument.Parse("""{"@odata.type": "#tm1.FutureView", "Name": "a"}""").RootElement);
        Assert.Equal(typeof(View), unknown!.GetType());
        Assert.Equal("a", unknown.Name);
    }

    [Fact]
    public void ReadsAnUnknownEnumValueAsTheFallback()
    {
        var user = Tm1Json.Read<User>(JsonDocument.Parse("""{"Type": "SomethingNew"}""").RootElement);
        Assert.Equal(UserType.Unknown, user!.Type);
        Assert.Equal("ibm.tm1.api.v1.AccessPrivilege'Read'", OData.ODataLiterals.Of(AccessPrivilege.Read));
        Assert.Throws<InvalidOperationException>(() => OData.ODataLiterals.Of(AccessPrivilege.Unknown));
    }

    [Fact]
    public void ReadsDeparturesAndLenientDatesFromTheOverlay()
    {
        Assert.Equal(
            JsonValueKind.String,
            Tm1Json.Read<CAMSettings>(JsonDocument.Parse("""{"ClientPingCAMPassport": "PT15M"}""").RootElement)!.ClientPingCAMPassport!.Value.ValueKind
        );
        Assert.Equal(
            JsonValueKind.Number,
            Tm1Json.Read<CAMSettings>(JsonDocument.Parse("""{"ClientPingCAMPassport": 900}""").RootElement)!.ClientPingCAMPassport!.Value.ValueKind
        );
        Assert.Null(Tm1Json.Read<MessageLogEntry>(JsonDocument.Parse("""{"TimeStamp": "0000-00-00T00:00Z"}""").RootElement)!.TimeStamp);
    }

    [Fact]
    public void WritesPatchesAsSet()
    {
        var dimensions = OData.ODataPath.Of("Dimensions");
        var patch = CubeCreate.Builder().Name("Sales").BindDimensions(dimensions.Key("Region"), dimensions.Key("Month")).Rules(null).Build();
        Assert.Equal(
            """{"Name":"Sales","Dimensions@odata.bind":["Dimensions('Region')","Dimensions('Month')"],"Rules":null}""",
            JsonSerializer.Serialize<Tm1Patch>(patch, Tm1Json.Options)
        );
        Assert.Equal(
            """{"@odata.type":"#ibm.tm1.api.v1.NativeView","SuppressEmptyRows":true}""",
            JsonSerializer.Serialize<Tm1Patch>(NativeViewPatch.Builder().SuppressEmptyRows(true).Build(), Tm1Json.Options)
        );
        Assert.Equal(
            """{"Frequency":"PT24H"}""",
            JsonSerializer.Serialize<Tm1Patch>(ChorePatch.Builder().Frequency(TimeSpan.FromDays(1)).Build(), Tm1Json.Options)
        );
        Assert.Equal("""{"Caption":"x"}""", JsonSerializer.Serialize<Tm1Patch>(AttributesPatch.Builder().Property("Caption", "x").Build(), Tm1Json.Options));
    }

    [Fact]
    public void HasFieldConstantsForQueries()
    {
        Assert.Equal("Name", Cube.Fields.Name.Path);
        Assert.Equal("DrillthroughRules", Cube.Fields.DrillthroughRules.Path);
        Assert.Equal("Name", NativeView.Fields.Name.Path);
        Assert.Equal("$select=Name&$filter=(Name eq 'x')", OData.ODataQuery.None().Select(Cube.Fields.Name).Filter(Cube.Fields.Name.Eq("x")).ToString());
    }

    private static void Compare(JsonElement original, JsonElement written, string path, List<string> differences, SortedSet<string> unknown)
    {
        if (original.ValueKind == JsonValueKind.Object)
        {
            if (written.ValueKind != JsonValueKind.Object)
            {
                differences.Add($"{path}: {original} became {written}");
                return;
            }
            foreach (var p in original.EnumerateObject())
            {
                var at = path + "/" + p.Name;
                if (p.Name.StartsWith("@odata.", StringComparison.Ordinal) && p.Name is not ("@odata.etag" or "@odata.type"))
                {
                    continue;
                }
                var has = written.TryGetProperty(p.Name, out var back);
                if (p.Value.ValueKind == JsonValueKind.Null)
                {
                    if (has && back.ValueKind != JsonValueKind.Null)
                    {
                        differences.Add($"{at}: null became {back}");
                    }
                }
                else if (!has)
                {
                    if (p.Name != "@odata.type")
                    {
                        unknown.Add(at);
                    }
                }
                else
                {
                    Compare(p.Value, back, at, differences, unknown);
                }
            }
            foreach (var p in written.EnumerateObject())
            {
                if (!original.TryGetProperty(p.Name, out _) && p.Name != "@odata.type")
                {
                    differences.Add($"{path}/{p.Name}: written, but TM1 didn't send it");
                }
            }
        }
        else if (original.ValueKind == JsonValueKind.Array)
        {
            var a = original.EnumerateArray().ToList();
            var b = written.ValueKind == JsonValueKind.Array ? written.EnumerateArray().ToList() : [];
            if (a.Count != b.Count)
            {
                differences.Add($"{path}: {original} became {written}");
                return;
            }
            for (int i = 0; i < a.Count; i++)
            {
                Compare(a[i], b[i], $"{path}[{i}]", differences, unknown);
            }
        }
        else if (!Same(original, written))
        {
            differences.Add($"{path}: {original} became {written}");
        }
    }

    private static bool Same(JsonElement a, JsonElement b)
    {
        if (a.GetRawText() == b.GetRawText())
        {
            return true;
        }
        if (a.ValueKind == JsonValueKind.Number && b.ValueKind == JsonValueKind.Number)
        {
            return a.GetDecimal() == b.GetDecimal();
        }
        if (a.ValueKind == JsonValueKind.String && b.ValueKind == JsonValueKind.String)
        {
            if (DateTimeOffset.TryParse(a.GetString(), out var x) && DateTimeOffset.TryParse(b.GetString(), out var y))
            {
                return x == y;
            }
            try
            {
                return XmlConvert.ToTimeSpan(a.GetString()!) == XmlConvert.ToTimeSpan(b.GetString()!);
            }
            catch (FormatException)
            {
                return false;
            }
        }
        return false;
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Version;
using Xunit;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The properties of the model that depart from TM1's <c>$metadata</c>: ones it doesn't
/// declare (found by probing TM1, <c>tm1-probe-&lt;schema&gt;.json</c>), and the overlay's departures
/// and lenient dates. As the Java DeparturesTest.</summary>
public class DeparturesTests
{
    [Fact]
    public void ReadsAndWritesBackThePropertiesTm1DoesNotDeclare()
    {
        var process = JsonSerializer.Deserialize<Process>(
            """{"Name": "Load", "UIData": "CubeAction=1511\f", "VariablesUIData": ["VarType=32\f"], "Code": "#region Prolog\nx = 1;\n#endregion"}""",
            Tm1Json.Options
        )!;
        Assert.Equal("CubeAction=1511\f", process.UiData);
        Assert.Equal(["VarType=32\f"], process.VariablesUIData!);
        Assert.StartsWith("#region Prolog", process.Code);
        Assert.Equal("Caption", JsonSerializer.Deserialize<Subset>("""{"Name": "Top", "Alias": "Caption"}""", Tm1Json.Options)!.Alias);

        var patch = ProcessPatch.Builder().Name("Load").UiData(process.UiData).VariablesUIData(process.VariablesUIData).Build();
        Assert.Equal(
            """{"Name":"Load","UIData":"CubeAction=1511\f","VariablesUIData":["VarType=32\f"]}""",
            JsonSerializer.Serialize<Tm1Patch>(patch, Tm1Json.Options)
        );
        Assert.Equal("""{"Alias":"Caption"}""", JsonSerializer.Serialize<Tm1Patch>(SubsetPatch.Builder().Alias("Caption").Build(), Tm1Json.Options));
    }

    [Fact]
    public void TagsEveryDepartureTheSpecLists()
    {
        using var spec = JsonDocument.Parse(File.ReadAllText(Repo.Spec));
        var listed = new SortedDictionary<string, string>();
        foreach (var t in spec.RootElement.GetProperty("types").EnumerateArray())
        {
            foreach (var p in t.GetProperty("properties").EnumerateArray())
            {
                string? kind =
                    p.TryGetProperty("undeclared", out _) ? "UNDECLARED"
                    : p.TryGetProperty("departure", out _) ? "TYPE"
                    : p.TryGetProperty("lenientDate", out _) ? "LENIENT_DATE"
                    : null;
                if (kind is not null)
                {
                    listed[$"{t.GetProperty("name").GetString()}/{p.GetProperty("name").GetString()}"] = kind;
                }
            }
        }
        Assert.Equal("UNDECLARED", listed["Process/UIData"]);
        Assert.Equal("UNDECLARED", listed["Subset/Alias"]);
        Assert.Equal("TYPE", listed["CAMSettings/ClientPingCAMPassport"]);
        Assert.Equal("LENIENT_DATE", listed["MessageLogEntry/TimeStamp"]);

        var tagged = new SortedDictionary<string, string>();
        var model = typeof(Process).Assembly;
        foreach (var t in spec.RootElement.GetProperty("types").EnumerateArray())
        {
            var name = t.GetProperty("name").GetString()!;
            var own = t.GetProperty("properties").EnumerateArray().Select(p => p.GetProperty("name").GetString()).ToHashSet();
            var type = model.GetType($"{typeof(Process).Namespace}.{name}")!;
            foreach (var property in type.GetProperties(BindingFlags.Public | BindingFlags.Instance))
            {
                var departure = property.GetCustomAttribute<Tm1DepartureAttribute>();
                var json = property.GetCustomAttribute<JsonPropertyNameAttribute>();
                if (departure is not null && json is not null && own.Contains(json.Name))
                {
                    tagged[$"{name}/{json.Name}"] = departure.Value switch
                    {
                        Tm1DepartureKind.Undeclared => "UNDECLARED",
                        Tm1DepartureKind.Type => "TYPE",
                        _ => "LENIENT_DATE",
                    };
                }
            }
        }
        Assert.Equal(listed, tagged);
    }
}

using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests;

/// <summary>The OData encoding every client must agree on (spec/conformance/odata-encoding.json),
/// which the Java and Python clients' tests read too.</summary>
public class ConformanceTests
{
    private static readonly JsonElement Cases = JsonDocument.Parse(File.ReadAllText(Path.Combine(Repo.Conformance, "odata-encoding.json"))).RootElement;

    public static IEnumerable<object[]> Paths() => Cases.GetProperty("paths").EnumerateArray().Select(c => new object[] { c.GetProperty("expect").GetString()!, c.GetProperty("steps").GetRawText() });

    public static IEnumerable<object[]> Literals() => Cases.GetProperty("literals").EnumerateArray().Select(c => new object[] { c.GetProperty("expect").GetString()!, c.GetRawText() });

    [Theory]
    [MemberData(nameof(Paths))]
    public void BuildsPaths(string expect, string steps)
    {
        ODataPath? path = null;
        foreach (var step in JsonDocument.Parse(steps).RootElement.EnumerateArray())
        {
            var op = step[0].GetString();
            path = op switch
            {
                "of" => ODataPath.Of(step[1].GetString()!),
                "functionImport" => ODataPath.FunctionImport(step[1].GetString()!, Map(step[2])),
                "key" => path!.Key(Value(step[1], null)!),
                "compoundKey" => path!.Key(Map(step[1])),
                "nav" => path!.Nav(step[1].GetString()!),
                "cast" => path!.Cast(step[1].GetString()!),
                "action" => path!.Action(step[1].GetString()!),
                "function" => path!.Function(step[1].GetString()!, Map(step[2])),
                "value" => path!.Value(),
                "count" => path!.Count(),
                "ref" => path!.Ref(),
                _ => throw new System.ArgumentException(op),
            };
        }
        Assert.Equal(expect, path!.ToString());
    }

    [Theory]
    [MemberData(nameof(Literals))]
    public void WritesLiterals(string expect, string json)
    {
        var c = JsonDocument.Parse(json).RootElement;
        var @as = c.TryGetProperty("as", out var a) ? a.GetString() : null;
        Assert.Equal(expect, ODataLiterals.Of(Value(c.GetProperty("value"), @as)));
    }

    private static Dictionary<string, object?> Map(JsonElement e) => e.EnumerateObject().ToDictionary(p => p.Name, p => Value(p.Value, null));

    private static object? Value(JsonElement e, string? @as) => e.ValueKind switch
    {
        JsonValueKind.Null => null,
        _ when @as == "double" => e.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Number when e.TryGetInt64(out var l) => l,
        JsonValueKind.Number => e.GetDouble(),
        _ => e.GetString(),
    };
}

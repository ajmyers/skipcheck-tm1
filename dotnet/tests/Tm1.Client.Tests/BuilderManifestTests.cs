// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json.Nodes;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Xunit;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>What every generated create and patch builder offers, which every client's builders must
/// match (<c>spec/conformance/builders.json</c>), as the Java BuilderManifestTest. Each builder is
/// driven to find what it offers: a setter on a builder of its own gives the name it writes, and a
/// property is required when a builder with every other setter called refuses to build.</summary>
public class BuilderManifestTests
{
    private const string BindSuffix = "@odata.bind";

    [Fact]
    public void EveryBuilderOffersWhatTheManifestLists()
    {
        var spec = JsonNode.Parse(File.ReadAllText(Repo.Spec))!;
        var manifest = JsonNode.Parse(File.ReadAllText(Path.Combine(Repo.Conformance, "builders.json")))!["builders"]!.AsObject();
        var model = typeof(Cube).Assembly;
        var names = spec["types"]!
            .AsArray()
            .SelectMany(t => new[] { "Patch", "Create" }.Select(s => t!["name"]!.GetValue<string>() + s))
            .Where(n => model.GetType($"{typeof(Cube).Namespace}.{n}") is not null)
            .Order(StringComparer.Ordinal)
            .ToList();
        Assert.Equal(manifest.Select(b => b.Key).Order(StringComparer.Ordinal), names);
        var wrong = new List<string>();
        foreach (var name in names)
        {
            var got = Describe(model.GetType($"{typeof(Cube).Namespace}.{name}")!);
            if (!JsonNode.DeepEquals(got, manifest[name]))
            {
                wrong.Add($"{name}: {got.ToJsonString()} != {manifest[name]!.ToJsonString()}");
            }
        }
        Assert.Empty(wrong);
    }

    private static JsonObject Describe(Type patch)
    {
        var factory = patch.GetMethod("Builder", BindingFlags.Public | BindingFlags.Static, Type.EmptyTypes)!;
        object Fresh() => factory.Invoke(null, null)!;
        var builderType = Fresh().GetType();
        var initial = Values(Fresh());
        var setters = builderType
            .GetMethods(BindingFlags.Public | BindingFlags.Instance)
            .Where(m => m.ReturnType == builderType && m.Name is not ("Build" or "Property"))
            .OrderBy(m => m.Name, StringComparer.Ordinal)
            .ToList();
        // Each setter on a builder of its own: the name it writes
        var wrote = new Dictionary<MethodInfo, string>();
        foreach (var m in setters)
        {
            var b = Fresh();
            m.Invoke(b, Arguments(m));
            var added = Values(b).Keys.Where(k => !initial.ContainsKey(k)).ToList();
            Assert.True(added.Count == 1, $"{patch.Name}.{m.Name} writes one property: {string.Join(",", added)}");
            wrote[m] = added[0];
        }
        // A property is required when a builder with every other setter called refuses to build
        var required = new List<string>();
        foreach (var group in wrote.Values.Select(Bare).Distinct().Order(StringComparer.Ordinal))
        {
            var b = Fresh();
            foreach (var (m, key) in wrote)
            {
                if (Bare(key) != group)
                {
                    m.Invoke(b, Arguments(m));
                }
            }
            try
            {
                builderType.GetMethod("Build", Type.EmptyTypes)!.Invoke(b, null);
            }
            catch (TargetInvocationException e) when (e.InnerException is InvalidOperationException)
            {
                required.Add(group);
            }
        }
        var open = builderType.GetMethods(BindingFlags.Public | BindingFlags.Instance).Any(m => m.Name == "Property" && m.GetParameters().Length == 2);
        return new JsonObject
        {
            ["odataType"] = initial.TryGetValue("@odata.type", out var type) ? JsonValue.Create(type?.ToString()) : null,
            ["open"] = open,
            ["required"] = new JsonArray(required.Select(r => (JsonNode?)JsonValue.Create(r)).ToArray()),
            ["setters"] = new JsonArray(wrote.Values.Order(StringComparer.Ordinal).Select(s => (JsonNode?)JsonValue.Create(s)).ToArray()),
        };
    }

    private static object?[] Arguments(MethodInfo m) =>
        m.GetParameters()
            .Select(p =>
                p.ParameterType == typeof(ODataPath[]) ? Array.Empty<ODataPath>()
                : p.ParameterType == typeof(ODataPath) ? ODataPath.Of("x")
                : p.ParameterType.IsValueType && Nullable.GetUnderlyingType(p.ParameterType) is null ? Activator.CreateInstance(p.ParameterType)
                : (object?)null
            )
            .ToArray();

    private static Dictionary<string, object?> Values(object builder)
    {
        for (var t = builder.GetType(); t is not null; t = t.BaseType)
        {
            var field = t.GetField("_values", BindingFlags.NonPublic | BindingFlags.Instance);
            if (field is not null)
            {
                return ((IEnumerable<KeyValuePair<string, object?>>)field.GetValue(builder)!).ToDictionary(kv => kv.Key, kv => kv.Value);
            }
        }
        throw new InvalidOperationException("No values in " + builder.GetType());
    }

    private static string Bare(string name) => name.EndsWith(BindSuffix, StringComparison.Ordinal) ? name[..^BindSuffix.Length] : name;
}

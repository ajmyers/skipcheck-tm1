// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The endpoint coverage gate, as the Java client's CoverageGateTest: every
/// endpoint of the spec is implemented (a <see cref="Tm1EndpointAttribute"/>), excluded with a
/// reason, or pending, under the same rules, against the same inventory.</summary>
public class CoverageGateTests
{
    private static readonly string Coverage = Path.Combine(Repo.Root, "dotnet", "coverage");

    public static IReadOnlySet<string> Endpoints()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Repo.Spec));
        return doc.RootElement.GetProperty("endpoints").EnumerateArray().Select(e => e.GetProperty("id").GetString()!).ToHashSet();
    }

    public static IReadOnlySet<string> Implemented() =>
        typeof(Tm1Client)
            .Assembly.GetTypes()
            .SelectMany(t => t.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
            .SelectMany(m => m.GetCustomAttributes<Tm1EndpointAttribute>())
            .Select(a => a.Value)
            .ToHashSet();

    private static List<string> Lines(string file) =>
        File.ReadAllLines(Path.Combine(Coverage, file)).Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')).ToList();

    [Fact]
    public void EveryEndpointIsAccountedFor()
    {
        var endpoints = Endpoints();
        var implemented = Implemented();
        var excluded = new Dictionary<string, string>();
        foreach (var line in Lines("excluded.txt"))
        {
            var bar = line.IndexOf('|');
            Assert.True(bar > 0 && line[(bar + 1)..].Trim().Length > 0, $"'{line}' needs a reason: <endpoint> | <reason>");
            Assert.True(excluded.TryAdd(line[..bar].Trim(), line[(bar + 1)..].Trim()), "duplicate exclusion " + line);
        }
        var pending = Lines("pending.txt");

        var untriaged = endpoints.Except(implemented).Except(excluded.Keys).Except(pending).Order().ToList();
        Assert.True(untriaged.Count == 0, "endpoints not implemented, excluded or pending: " + string.Join(", ", untriaged));
        var unknown = pending.Concat(excluded.Keys).Concat(implemented).Except(endpoints).Order().ToList();
        Assert.True(unknown.Count == 0, "endpoints the spec doesn't define: " + string.Join(", ", unknown));
        var stillPending = implemented.Intersect(pending).Order().ToList();
        Assert.True(stillPending.Count == 0, "implemented endpoints still in pending.txt: take them off: " + string.Join(", ", stillPending));
        Assert.Empty(pending.Intersect(excluded.Keys));
        Assert.Equal(pending.Count, pending.Distinct().Count());
    }

    [Fact]
    public void CountsEveryKindOfEndpoint()
    {
        var endpoints = Endpoints();
        Assert.Contains("EntitySet Cubes", endpoints);
        Assert.Contains("Navigation ViewTitle/Subset", endpoints);
        // The same count as the Java client's CoverageGateTest
        Assert.Equal(242, endpoints.Count);
    }
}

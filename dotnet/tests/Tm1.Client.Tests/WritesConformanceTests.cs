// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Xunit;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The writes made from what was read that every client must produce identically
/// (<c>spec/conformance/writes.json</c>), as the Java WritesConformanceTest.</summary>
public class WritesConformanceTests
{
    [Fact]
    public void EveryCaseWritesWhatItExpects()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(Repo.Conformance, "writes.json")));
        var cases = doc.RootElement.GetProperty("cases").EnumerateArray().ToList();
        var wrong = new List<string>();
        var model = typeof(Process).Assembly;
        foreach (var c in cases)
        {
            var id = c.GetProperty("id").GetString();
            var typeName = c.GetProperty("type").GetString();
            var type = model.GetType($"{typeof(Process).Namespace}.{typeName}")!;
            var write = model.GetType($"{typeof(Process).Namespace}.{typeName}{(c.GetProperty("write").GetString() == "create" ? "Create" : "Patch")}")!;
            var read = c.GetProperty("read").Deserialize(type, Tm1Json.Options)!;
            var builder = write.GetMethod("Builder", [type])!.Invoke(null, [read])!;
            var hasError = c.TryGetProperty("error", out var error);
            try
            {
                var built = (Tm1Patch)builder.GetType().GetMethod("Build", Type.EmptyTypes)!.Invoke(builder, null)!;
                var got = JsonNode.Parse(JsonSerializer.Serialize(built, Tm1Json.Options));
                if (hasError)
                {
                    wrong.Add($"{id}: built {got?.ToJsonString()}, but should fail with {error.GetString()}");
                }
                else if (!JsonNode.DeepEquals(got, JsonNode.Parse(c.GetProperty("expect").GetRawText())))
                {
                    wrong.Add($"{id}: wrote {got?.ToJsonString()}, expected {c.GetProperty("expect").GetRawText()}");
                }
            }
            catch (TargetInvocationException e)
            {
                if (!hasError || !e.InnerException!.Message.Contains(error.GetString()!, StringComparison.Ordinal))
                {
                    wrong.Add($"{id}: failed with {e.InnerException!.Message}");
                }
            }
        }
        Assert.NotEmpty(cases);
        Assert.Empty(wrong);
    }
}

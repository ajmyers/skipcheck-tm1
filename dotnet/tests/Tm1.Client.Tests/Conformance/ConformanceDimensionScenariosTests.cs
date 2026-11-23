// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text.Json.Nodes;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>The dimension scenarios (dimensions, hierarchies, elements, levels), offline: against a
/// fake TM1 that answers each step with what the step expects, so every step's call is made, sends
/// one request, and reads its answer.</summary>
public class ConformanceDimensionScenariosTests
{
    /// <summary>What TM1 would answer for the call to meet the expectation.</summary>
    private static object? Answer(JsonNode? expect)
    {
        if (expect is null || expect["none"] is not null)
        {
            return null;
        }
        if (expect["error"] is JsonObject error)
        {
            var kind = error["kind"]?.GetValue<string>() ?? "";
            var status = error["status"]?.GetValue<int>()
                ?? kind switch
                {
                    "NOT_FOUND" => 404,
                    "CONFLICT" or "ALREADY_EXISTS" => 409,
                    "PRECONDITION_FAILED" => 412,
                    _ => 400,
                };
            var message = kind switch
            {
                "NOT_FOUND" => "'x' can not be found in collection",
                "ALREADY_EXISTS" => "'x' already exists",
                _ => "refused",
            };
            return FakeTm1.Json(new { error = new { code = "278", message } }, (HttpStatusCode)status);
        }
        if (expect["count"] is JsonValue count)
        {
            return FakeTm1.Collection(Enumerable.Range(0, count.GetValue<int>()).Select(_ => (object)new JsonObject()).ToArray());
        }
        if (expect["items"] is JsonObject fields)
        {
            var items = new List<JsonObject>();
            foreach (var (field, values) in fields)
            {
                var list = values!.AsArray();
                for (var i = 0; i < list.Count; i++)
                {
                    if (items.Count <= i)
                    {
                        items.Add(new JsonObject());
                    }
                    items[i][field] = list[i]?.DeepClone();
                }
            }
            return FakeTm1.Collection(items.Cast<object>().ToArray());
        }
        return expect["fields"]!.DeepClone();
    }

    [Theory]
    [InlineData("dimensions")]
    [InlineData("hierarchies")]
    [InlineData("elements")]
    [InlineData("levels")]
    public async Task RunsEachStepOnce(string name)
    {
        var scenario = ConformanceLive.Load(name);
        var names = ConformanceLive.NamesFor(scenario);
        ConformanceLive.Traffic? traffic = null;
        var fake = new FakeTm1
        {
            Respond = _ =>
            {
                var step = traffic!.Step;
                JsonNode? expect = null;
                if (step.StartsWith("steps[", System.StringComparison.Ordinal))
                {
                    expect = ConformanceLive.Substitute(
                        scenario["steps"]![int.Parse(step[6..^1], System.Globalization.CultureInfo.InvariantCulture)]!["expect"],
                        names
                    );
                }
                return Answer(expect);
            },
        };
        traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        Assert.Empty(await ConformanceLive.Run(fake.Client(b => b.HttpHandler(traffic)), traffic, scenario, names));
        var labels = Enumerable
            .Range(0, scenario["steps"]!.AsArray().Count)
            .Select(i => $"steps[{i}]")
            .Concat(Enumerable.Range(0, scenario["finally"]!.AsArray().Count).Select(i => $"finally[{i}]"));
        Assert.Equal(labels, traffic.Exchanges.Select(e => e["step"]!.GetValue<string>()));
    }
}

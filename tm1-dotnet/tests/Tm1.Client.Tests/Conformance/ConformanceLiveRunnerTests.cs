using System.IO;
using System.Linq;
using System.Net;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>The live conformance runner, offline: the cubes scenario against a fake TM1 that
/// answers as TM1 would, writing traffic without the session's own requests.</summary>
public class ConformanceLiveRunnerTests
{
    private static FakeTm1 Fake(System.Collections.Generic.IReadOnlyDictionary<string, string> names) =>
        new()
        {
            Respond = r =>
            {
                var get = Regex.Match(r.Target, @"^Cubes\('([^']*)'\)(\?.*)?$");
                if (r.Method == "POST" && r.Target is "Dimensions" or "Cubes")
                {
                    return FakeTm1.Json(
                        new System.Collections.Generic.Dictionary<string, object?> { ["Name"] = r.Json.GetProperty("Name").GetString() },
                        HttpStatusCode.Created
                    );
                }
                if (r.Method == "GET" && get.Success)
                {
                    return get.Groups[1].Value == names["missing"]
                        ? FakeTm1.Json(new { error = new { code = "278", message = "no" } }, HttpStatusCode.NotFound)
                        : new System.Collections.Generic.Dictionary<string, object?> { ["Name"] = get.Groups[1].Value };
                }
                if (r.Method == "GET" && r.Target.EndsWith("/Dimensions?$select=Name", System.StringComparison.Ordinal))
                {
                    return FakeTm1.Collection(new { Name = "Sandboxes" }, new { Name = names["region"] }, new { Name = names["month"] });
                }
                return r.Target.EndsWith("tm1.CheckRules", System.StringComparison.Ordinal) ? FakeTm1.Collection() : null;
            },
        };

    [Fact]
    public async Task RunsTheCubesScenarioAndWritesItsTraffic()
    {
        ConformanceLive.TrafficDirectory = Directory.CreateTempSubdirectory("conformance-").FullName;
        var scenario = ConformanceLive.Load("cubes");
        var names = ConformanceLive.NamesFor(scenario);
        var fake = Fake(names);
        var traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        var client = fake.Client(b => b.HttpHandler(traffic));
        Assert.Empty(await ConformanceLive.Run(client, traffic, scenario, names));
        var lines = File.ReadAllLines(traffic.Write("cubes", names)).Select(l => JsonNode.Parse(l)!).ToList();
        Assert.Equal(names["cube"], lines[0]["names"]!["cube"]!.GetValue<string>());
        Assert.Equal(scenario["steps"]!.AsArray().Count + scenario["finally"]!.AsArray().Count, lines.Count - 1);
        Assert.Equal("Dimensions", lines[1]["request"]!["target"]!.GetValue<string>());
    }

    /// <summary>Answers as TM1 would for the cellsets, views and sandboxes scenarios: a cellset of
    /// 3 × 2 cells (and the slicer), names that exist but <c>missing</c>.</summary>
    private static FakeTm1 DataFake(System.Collections.Generic.IReadOnlyDictionary<string, string> names) =>
        new()
        {
            Respond = r =>
            {
                static object[] Cells(long begin, long end) =>
                    Enumerable.Range((int)begin, (int)(end - begin + 1)).Select(object (n) => new { Ordinal = n, Value = n }).ToArray();
                var (method, target) = (r.Method, r.Target);
                if (
                    target.Contains("('" + names["missing"] + "')", System.StringComparison.Ordinal)
                    || (method == "POST" && target.StartsWith("ExecuteRelationalDrillthrough", System.StringComparison.Ordinal))
                )
                {
                    return FakeTm1.Json(new { error = new { code = "278", message = "no" } }, HttpStatusCode.NotFound);
                }
                if (method == "POST" && Regex.IsMatch(target, @"^(Dimensions|Cubes|Sandboxes|Cubes\('[^']*'\)/(Private)?Views)$"))
                {
                    return FakeTm1.Json(new { Name = r.Json.GetProperty("Name").GetString() }, HttpStatusCode.Created);
                }
                if (method == "POST" && target.Split('?')[0] == "ExecuteMDX")
                {
                    if (r.Json.GetProperty("MDX").GetString()!.Contains("SELEKT", System.StringComparison.Ordinal))
                    {
                        return FakeTm1.Json(new { error = new { code = "501", message = "syntax" } }, HttpStatusCode.BadRequest);
                    }
                    var top = long.Parse(Regex.Match(target, @"\$top%3D(\d+)").Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture);
                    return new
                    {
                        ID = "cs1",
                        Axes = new[] { new { Ordinal = 0, Cardinality = 3 }, new { Ordinal = 1, Cardinality = 2 }, new { Ordinal = 2, Cardinality = 1 } },
                        Cells = Cells(0, top - 1),
                    };
                }
                if (method == "POST" && target.EndsWith("/tm1.Execute?$select=ID", System.StringComparison.Ordinal))
                {
                    return new { ID = "cs2" };
                }
                Match m;
                if (method == "GET" && (m = Regex.Match(target, @"^Cellsets\('cs1'\)/tm1\.GetPartition\(Begin=(\d+),End=(\d+)\)")).Success)
                {
                    return FakeTm1.Collection(
                        Cells(
                            long.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture),
                            long.Parse(m.Groups[2].Value, System.Globalization.CultureInfo.InvariantCulture)
                        )
                    );
                }
                if (method == "GET" && (m = Regex.Match(target, @"^Cubes\('([^']*)'\)/[A-Za-z]+\('[^']*'\)/Cube")).Success)
                {
                    return new { Name = m.Groups[1].Value };
                }
                if (method == "GET" && (m = Regex.Match(target, @"^Cubes\('[^']*'\)/(Private)?Views\('([^']*)'\)")).Success)
                {
                    return target.Contains("NativeView", System.StringComparison.Ordinal)
                        ? new System.Collections.Generic.Dictionary<string, object?>
                        {
                            ["@odata.type"] = "#ibm.tm1.api.v1.NativeView",
                            ["SuppressEmptyRows"] = true,
                            ["Name"] = m.Groups[2].Value,
                        }
                        : new System.Collections.Generic.Dictionary<string, object?> { ["Name"] = m.Groups[2].Value };
                }
                if (method == "GET" && Regex.IsMatch(target, @"^Cubes\('[^']*'\)/PrivateViews\?"))
                {
                    return FakeTm1.Collection(new { Name = names["native"] });
                }
                if (method == "GET" && (m = Regex.Match(target, @"^Sandboxes\('([^']*)'\)")).Success)
                {
                    return new { Name = m.Groups[1].Value, IsLoaded = true };
                }
                return null;
            },
        };

    [Theory]
    [InlineData("cellsets")]
    [InlineData("views")]
    [InlineData("sandboxes")]
    public async Task RunsTheDataScenarios(string name)
    {
        ConformanceLive.TrafficDirectory = Directory.CreateTempSubdirectory("conformance-").FullName;
        var scenario = ConformanceLive.Load(name);
        var names = ConformanceLive.NamesFor(scenario);
        var fake = DataFake(names);
        var traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        var failures = await ConformanceLive.Run(fake.Client(b => b.HttpHandler(traffic)), traffic, scenario, names);
        Assert.True(failures.Count == 0, string.Join("; ", failures));
        Assert.Equal(
            scenario["steps"]!.AsArray().Count + scenario["finally"]!.AsArray().Count,
            traffic.Exchanges.Select(e => e["step"]!.GetValue<string>()).Distinct().Count()
        );
    }

    [Fact]
    public async Task StopsAtAStepThatMissesItsExpectationAndStillCleansUp()
    {
        var scenario = ConformanceLive.Load("cubes");
        var names = ConformanceLive.NamesFor(scenario);
        var fake = new FakeTm1 { Respond = _ => FakeTm1.Json(new { error = new { code = "1", message = "down" } }, HttpStatusCode.InternalServerError) };
        var traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        var failures = await ConformanceLive.Run(fake.Client(b => b.HttpHandler(traffic)), traffic, scenario, names);
        Assert.StartsWith("step 0 dimensions.create", Assert.Single(failures));
        Assert.Equal(["finally[0]", "finally[1]", "finally[2]"], traffic.Exchanges.TakeLast(3).Select(e => e["step"]!.GetValue<string>()));
    }

    /// <summary>What a step expects, as TM1's answer to its one request.</summary>
    private static System.Net.Http.HttpResponseMessage Expected(JsonObject expect)
    {
        static System.Net.Http.HttpResponseMessage Reply(HttpStatusCode status, JsonNode body) =>
            new(status) { Content = new System.Net.Http.StringContent(body.ToJsonString(), System.Text.Encoding.UTF8, "application/json") };
        if (expect["error"] is JsonObject error)
        {
            var kind = error["kind"]?.GetValue<string>();
            var status = error["status"]?.GetValue<int>()
                ?? kind switch
                {
                    "NOT_FOUND" => 404,
                    "CONFLICT" => 409,
                    "PRECONDITION_FAILED" => 412,
                    _ => 400,
                };
            var message = kind == "NOT_FOUND" ? "can not be found" : "refused";
            return Reply(
                (HttpStatusCode)status,
                new JsonObject
                {
                    ["error"] = new JsonObject { ["code"] = kind == "INVALID_UNIQUE_NAME" ? "248" : "278", ["message"] = message },
                }
            );
        }
        if (expect["fields"] is JsonObject { } bytes && bytes["$bytes"] is JsonValue content)
        {
            var answer = new System.Net.Http.HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new System.Net.Http.ByteArrayContent(System.Convert.FromBase64String(content.GetValue<string>())),
            };
            answer.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
            return answer;
        }
        if (expect["fields"] is JsonObject fields)
        {
            return Reply(HttpStatusCode.OK, fields.DeepClone());
        }
        if (expect.ContainsKey("count") || expect.ContainsKey("items"))
        {
            var value = new JsonArray();
            for (var i = 0; i < (expect["count"]?.GetValue<int>() ?? 0); i++)
            {
                value.Add(new JsonObject());
            }
            foreach (var (field, values) in expect["items"]?.AsObject() ?? [])
            {
                var list = values!.AsArray();
                for (var i = 0; i < list.Count; i++)
                {
                    if (i == value.Count)
                    {
                        value.Add(new JsonObject());
                    }
                    value[i]![field] = list[i]?.DeepClone();
                }
            }
            return Reply(HttpStatusCode.OK, new JsonObject { ["value"] = value });
        }
        return new System.Net.Http.HttpResponseMessage(HttpStatusCode.NoContent);
    }

    /// <summary>Answers each step's one request with what the step expects, and a finally step's with 204.</summary>
    /// <summary>Whether a step sends a request: all but those refused before one (an argument error).</summary>
    private static bool Sends(JsonNode? step) => step!["expect"]?["error"] is not JsonObject error || error["category"]?.GetValue<string>() == "api";

    private static FakeTm1 AnsweringAsExpected(JsonObject scenario, System.Collections.Generic.IReadOnlyDictionary<string, string> names)
    {
        var answers = new System.Collections.Generic.Queue<System.Net.Http.HttpResponseMessage>(
            scenario["steps"]!
                .AsArray()
                .Where(Sends)
                .Select(s => Expected(ConformanceLive.Substitute(s!["expect"], names)!.AsObject()))
                .Concat((scenario["finally"]?.AsArray() ?? []).Select(_ => new System.Net.Http.HttpResponseMessage(HttpStatusCode.NoContent)))
        );
        return new FakeTm1 { Respond = _ => answers.Count > 0 ? answers.Dequeue() : new System.Net.Http.HttpResponseMessage((HttpStatusCode)599) };
    }

    [Theory]
    [InlineData("subsets")]
    [InlineData("members")]
    [InlineData("edges")]
    [InlineData("member-sets")]
    [InlineData("content")]
    [InlineData("encryption")]
    [InlineData("git")]
    [InlineData("users")]
    [InlineData("sessions")]
    [InlineData("attributes")]
    [InlineData("annotations")]
    [InlineData("processes")]
    [InlineData("chores")]
    public async Task RunsAScenarioSendingOneRequestPerStep(string name)
    {
        var scenario = ConformanceLive.Load(name);
        var names = ConformanceLive.NamesFor(scenario);
        var fake = AnsweringAsExpected(scenario, names);
        var traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        Assert.Empty(await ConformanceLive.Run(fake.Client(b => b.HttpHandler(traffic)), traffic, scenario, names));
        Assert.Equal(scenario["steps"]!.AsArray().Count(Sends) + (scenario["finally"]?.AsArray().Count ?? 0), traffic.Exchanges.Count);
    }
}

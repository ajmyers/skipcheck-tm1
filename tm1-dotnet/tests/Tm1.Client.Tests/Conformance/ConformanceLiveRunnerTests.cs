using System.IO;
using System.Linq;
using System.Net;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace AjMyers.Tm1.Client.Tests.Conformance;

/// <summary>The live conformance runner, offline: the cubes scenario against a fake TM1 that
/// answers as TM1 would, writing traffic without the session's own requests.</summary>
public class ConformanceLiveRunnerTests
{
    private static FakeTm1 Fake(System.Collections.Generic.IReadOnlyDictionary<string, string> names) => new()
    {
        Respond = r =>
        {
            var get = Regex.Match(r.Target, @"^Cubes\('([^']*)'\)(\?.*)?$");
            if (r.Method == "POST" && r.Target is "Dimensions" or "Cubes")
            {
                return FakeTm1.Json(new System.Collections.Generic.Dictionary<string, object?> { ["Name"] = r.Json.GetProperty("Name").GetString() }, HttpStatusCode.Created);
            }
            if (r.Method == "GET" && get.Success)
            {
                return get.Groups[1].Value == names["missing"]
                    ? FakeTm1.Json(new { error = new { code = "278", message = "no" } }, HttpStatusCode.NotFound)
                    : new System.Collections.Generic.Dictionary<string, object?> { ["Name"] = get.Groups[1].Value };
            }
            if (r.Method == "GET" && r.Target.EndsWith("/Dimensions?$select=Name", System.StringComparison.Ordinal))
            {
                return FakeTm1.Collection(new { Name = names["region"] }, new { Name = names["month"] });
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
            var status = error["status"]?.GetValue<int>() ?? kind switch { "NOT_FOUND" => 404, "CONFLICT" => 409, "PRECONDITION_FAILED" => 412, _ => 400 };
            var message = kind == "NOT_FOUND" ? "can not be found" : "refused";
            return Reply((HttpStatusCode)status, new JsonObject { ["error"] = new JsonObject { ["code"] = "278", ["message"] = message } });
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
    private static FakeTm1 AnsweringAsExpected(JsonObject scenario, System.Collections.Generic.IReadOnlyDictionary<string, string> names)
    {
        var answers = new System.Collections.Generic.Queue<System.Net.Http.HttpResponseMessage>(
            scenario["steps"]!.AsArray().Select(s => Expected(ConformanceLive.Substitute(s!["expect"], names)!.AsObject()))
                .Concat((scenario["finally"]?.AsArray() ?? []).Select(_ => new System.Net.Http.HttpResponseMessage(HttpStatusCode.NoContent))));
        return new FakeTm1 { Respond = _ => answers.Count > 0 ? answers.Dequeue() : new System.Net.Http.HttpResponseMessage((HttpStatusCode)599) };
    }

    [Theory]
    [InlineData("subsets")]
    [InlineData("members")]
    [InlineData("edges")]
    [InlineData("member-sets")]
    public async Task RunsAScenarioSendingOneRequestPerStep(string name)
    {
        var scenario = ConformanceLive.Load(name);
        var names = ConformanceLive.NamesFor(scenario);
        var fake = AnsweringAsExpected(scenario, names);
        var traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        Assert.Empty(await ConformanceLive.Run(fake.Client(b => b.HttpHandler(traffic)), traffic, scenario, names));
        Assert.Equal(scenario["steps"]!.AsArray().Count + (scenario["finally"]?.AsArray().Count ?? 0), traffic.Exchanges.Count);
    }
}

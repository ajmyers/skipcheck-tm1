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
}

using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace AjMyers.Tm1.Client.Tests.Conformance;

/// <summary>The live conformance runner, offline: the processes, chores and server scenarios
/// against a fake TM1 that answers as TM1 would (the Java client's ProcessAndServerScenariosTest,
/// with the same fake).</summary>
public class ProcessAndServerScenariosTests
{
    private static readonly Regex Entity = new(@"^(Processes|Chores|Loggers|ErrorLogFiles|SQLDataSources)\('([^']*)'\)(\?.*)?$");

    private static readonly Regex Top = new(@"^(MessageLogEntries|MessageLog\(Reverse=true\))\?\$top=(\d+)$");

    private static FakeTm1 Fake(IReadOnlyDictionary<string, string> names)
    {
        var tasks = 0;
        var fake = new FakeTm1();
        fake.Respond = r =>
        {
            var (method, target) = (r.Method, r.Target);
            var entity = Entity.Match(target);
            var top = Top.Match(target);
            if (method == "POST" && target is "Processes" or "Chores")
            {
                if (target == "Chores")
                {
                    tasks = r.Json.TryGetProperty("Tasks", out var t) ? t.GetArrayLength() : 0;
                }
                return FakeTm1.Json(new Dictionary<string, object?> { ["Name"] = r.Json.GetProperty("Name").GetString() }, HttpStatusCode.Created);
            }
            if (method == "GET" && entity.Success)
            {
                var name = entity.Groups[2].Value;
                if (name == names["missing"] || name == names["missing"] + ".log")
                {
                    return FakeTm1.Json(new { error = new { code = "278", message = "no" } }, HttpStatusCode.NotFound);
                }
                return entity.Groups[1].Value == "Chores"
                    ? new Dictionary<string, object?>
                    {
                        ["Name"] = name,
                        ["Active"] = false,
                        ["ExecutionMode"] = "SingleCommit",
                    }
                    : new Dictionary<string, object?> { ["Name"] = name };
            }
            if (method == "GET" && target.StartsWith("Processes?", System.StringComparison.Ordinal))
            {
                return FakeTm1.Collection(new { Name = names["process"] });
            }
            if (method == "GET" && target.StartsWith("Chores?", System.StringComparison.Ordinal))
            {
                return FakeTm1.Collection(new { Name = names["chore"] });
            }
            if (target.EndsWith("/tm1.Compile", System.StringComparison.Ordinal))
            {
                return FakeTm1.Collection();
            }
            if (target == "CompileProcess")
            {
                return FakeTm1.Collection(
                    new
                    {
                        Procedure = "Prolog",
                        LineNumber = 1,
                        Message = "Syntax error",
                    }
                );
            }
            if (
                target.Contains("ExecuteWithReturn", System.StringComparison.Ordinal)
                || target.StartsWith("ExecuteProcessWithReturn", System.StringComparison.Ordinal)
            )
            {
                return new Dictionary<string, object?> { ["ProcessExecuteStatusCode"] = "CompletedSuccessfully" };
            }
            if (method == "GET" && target.StartsWith("ProcessDebugContexts", System.StringComparison.Ordinal))
            {
                return FakeTm1.Collection();
            }
            if (Regex.IsMatch(target, @"^Chores\('[^']*'\)/Tasks$"))
            {
                return FakeTm1.Json(new { Step = tasks++ }, HttpStatusCode.Created);
            }
            if (method == "GET" && Regex.IsMatch(target, @"^Chores\('[^']*'\)/Tasks\?.*$"))
            {
                return FakeTm1.Collection(Enumerable.Range(0, tasks).Select(i => (object)new { Step = i }).ToArray());
            }
            if (method == "DELETE" && Regex.IsMatch(target, @"^Chores\('[^']*'\)/Tasks\(\d+\)$"))
            {
                tasks--;
                return null;
            }
            if (method == "GET" && Regex.IsMatch(target, @"^Chores\('[^']*'\)/Tasks\(\d+\)/Process(\?.*)?$"))
            {
                return new Dictionary<string, object?> { ["Name"] = names["process"] };
            }
            if (method == "GET" && Regex.IsMatch(target, @"^Chores\('[^']*'\)/Tasks\(\d+\)/Chore(\?.*)?$"))
            {
                return new Dictionary<string, object?> { ["Name"] = names["chore"] };
            }
            if (method == "GET" && target.StartsWith("Server?", System.StringComparison.Ordinal))
            {
                return new Dictionary<string, object?> { ["Name"] = "tm1", ["ProductVersion"] = fake.ProductVersion };
            }
            if (method == "GET" && Regex.IsMatch(target, @"^(StaticConfiguration|ActiveConfiguration|Configuration)(\?.*)?$"))
            {
                return new Dictionary<string, object?> { ["ServerName"] = "tm1" };
            }
            if (method == "GET" && target.StartsWith("Loggers?", System.StringComparison.Ordinal))
            {
                return FakeTm1.Collection(new { Name = "TM1.Server", Level = "Info" });
            }
            if (method == "GET" && top.Success)
            {
                return FakeTm1.Collection(
                    Enumerable
                        .Range(0, int.Parse(top.Groups[2].Value, System.Globalization.CultureInfo.InvariantCulture))
                        .Select(i => (object)new { ID = i, Message = "m" })
                        .ToArray()
                );
            }
            return method == "GET" && target == "SQLDataSources" ? FakeTm1.Collection() : null;
        };
        return fake;
    }

    [Theory]
    [InlineData("server")]
    public async Task RunsTheScenarioAndWritesItsTraffic(string name)
    {
        ConformanceLive.TrafficDirectory = Directory.CreateTempSubdirectory("conformance-").FullName;
        var scenario = ConformanceLive.Load(name);
        var names = ConformanceLive.NamesFor(scenario);
        var fake = Fake(names);
        var traffic = new ConformanceLive.Traffic(fake, FakeTm1.Base);
        var client = fake.Client(b => b.HttpHandler(traffic));
        Assert.Empty(await ConformanceLive.Run(client, traffic, scenario, names));
        Assert.Equal(scenario["steps"]!.AsArray().Count + scenario["finally"]!.AsArray().Count, traffic.Exchanges.Count);
    }
}

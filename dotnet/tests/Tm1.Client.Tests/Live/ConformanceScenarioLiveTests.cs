using System;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Tests.Conformance;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The live conformance scenarios (spec/conformance/live) against a real TM1: each step must
/// meet its expectation, and what the client sent is written to build/conformance-live/dotnet for
/// scripts/compare-live-traffic.py to compare with the other clients'.</summary>
public sealed class ConformanceScenarioLiveTests
{
    [Tm1Fact]
    public async Task RunsEveryScenario()
    {
        var failures = new System.Collections.Generic.List<string>();
        foreach (var name in ConformanceLive.Names())
        {
            var scenario = ConformanceLive.Load(name);
            var names = ConformanceLive.NamesFor(scenario);
            var inner = new SocketsHttpHandler { UseCookies = false };
            if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
            {
                inner.SslOptions.RemoteCertificateValidationCallback = (_, _, _, _) => true;
            }
            var traffic = new ConformanceLive.Traffic(inner, global::Skipcheck.Tm1.Client.Tests.Live.Live.Url);
            await using var tm1 = global::Skipcheck.Tm1.Client.Tests.Live.Live.Builder().HttpHandler(traffic).Build();
            failures.AddRange((await ConformanceLive.Run(tm1, traffic, scenario, names)).Select(f => name + ": " + f));
            traffic.Write(scenario["name"]!.GetValue<string>(), names);
        }
        Assert.Empty(failures);
    }
}

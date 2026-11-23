using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Tests.Conformance;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>The live conformance scenarios (spec/conformance/live) against a real TM1: each step must
/// meet its expectation, and what the client sent is written to build/conformance-live/dotnet for
/// scripts/compare-live-traffic.py to compare with the other clients'. A port of the Java client's
/// ConformanceScenarioTm1Test.</summary>
public sealed class ConformanceScenarioLiveTests
{
    public static IEnumerable<object[]> Scenarios() => ConformanceLive.Names().Select(n => new object[] { n });

    [Tm1Theory]
    [MemberData(nameof(Scenarios))]
    public async Task RunsTheScenario(string name)
    {
        var scenario = ConformanceLive.Load(name);
        var names = ConformanceLive.NamesFor(scenario);
        var inner = new SocketsHttpHandler { UseCookies = false };
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            inner.SslOptions.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        }
        var traffic = new ConformanceLive.Traffic(inner, Live.Url);
        await using var tm1 = Live.Builder().HttpHandler(traffic).Build();
        var failures = await ConformanceLive.Run(tm1, traffic, scenario, names);
        traffic.Write(scenario["name"]!.GetValue<string>(), names);
        Assert.Empty(failures);
    }
}

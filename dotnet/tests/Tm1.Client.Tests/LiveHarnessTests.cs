using Skipcheck.Tm1.Client.Tests.Live;
using Skipcheck.Tm1.Testcontainers.Xunit;
using LiveTm1 = Skipcheck.Tm1.Client.Tests.Live.Live;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The live tests get TM1 from Skipcheck.Tm1.Testcontainers, whose own tests check where it
/// finds TM1 and how it mounts it (tests/Tm1.Testcontainers.Tests): here, only that the live tests
/// skip and run as the library's <c>[RequiresTm1Fact]</c> does, with <c>TM1_URL</c> on top.</summary>
public class LiveHarnessTests
{
    [Fact]
    public void SkipsAsTheLibraryDoesUnlessTm1UrlIsSet()
    {
        var url = System.Environment.GetEnvironmentVariable("TM1_URL");
        var library = RequiresTm1FactAttribute.Tm1SkipReason();
        Assert.Equal(!string.IsNullOrWhiteSpace(url) || library is null, LiveTm1.Configured);
        Assert.Equal(LiveTm1.Configured ? null : LiveTm1.Reason, new Tm1FactAttribute().Skip);
        Assert.EndsWith("Or set TM1_URL for a server that's already running.", LiveTm1.Reason, System.StringComparison.Ordinal);
    }

    [Fact]
    public void NamesTestObjectsForCleanup()
    {
        Assert.Matches("^tm1net_test_[0-9a-f]{8}$", LiveTm1.Name());
    }
}

// SPDX-License-Identifier: Apache-2.0

using System.Threading.Tasks;
using Skipcheck.Tm1.Testcontainers.Xunit;
using Xunit;

namespace Examples;

/// <summary>The Getting started example of the testcontainers guide's overview page.</summary>
public static class HelloTm1TestExamples
{
    // @start region = hello-test
    public sealed class HelloTm1Tests(Tm1ServerFixture server) : IClassFixture<Tm1ServerFixture>
    {
        [RequiresTm1Fact]
        public async Task Connects()
        {
            await using var tm1 = server.Client();
            Assert.NotNull(await tm1.Session.VersionAsync());
        }
    }
    // @end
}

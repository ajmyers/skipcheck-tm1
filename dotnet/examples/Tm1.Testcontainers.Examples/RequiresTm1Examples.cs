// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers.Xunit;
using Xunit;

namespace Examples;

/// <summary>Examples for <c>[RequiresTm1Fact]</c>, shown in the guide.</summary>
public static class RequiresTm1Examples
{
    // @start region = test-class
    // Every class with this fixture gets the same server, started once for the run
    public sealed class RegionDimensionTests(Tm1ServerFixture server) : IClassFixture<Tm1ServerFixture>
    {
        [RequiresTm1Fact]
        public async Task CreatesTheRegions()
        {
            await using var tm1 = server.Client();
            // The server is shared, so use a unique name and delete it afterwards
            var region = "Region-" + Guid.NewGuid();
            try
            {
                var created = await tm1.Dimensions.CreateAsync(
                    DimensionPatch
                        .Builder()
                        .Name(region)
                        .Hierarchies([
                            HierarchyPatch
                                .Builder()
                                .Name(region)
                                .Elements([
                                    ElementPatch.Builder().Name("North").Type(ElementType.Numeric).Build(),
                                    ElementPatch.Builder().Name("South").Type(ElementType.Numeric).Build(),
                                ])
                                .Build(),
                        ])
                        .Build()
                );
                Assert.Equal(region, created.Name);
            }
            finally
            {
                try
                {
                    await tm1.Dimensions.DeleteAsync(region);
                }
                catch (Tm1NotFoundException)
                {
                    // Never created
                }
            }
        }
    }

    // @end

    // @start region = by-version
    public sealed class NewFeatureTests(Tm1ServerFixture server) : IClassFixture<Tm1ServerFixture>
    {
        [RequiresTm1Fact]
        public void NeedsARecentServer()
        {
            // xUnit 2 can't skip a test that has started, so on a server older than the
            // feature the test ends here rather than failing
            var introducedIn = Tm1Version.Parse("11.8.03000");
            if (server.Tm1Server.Tm1Version.IsBefore(introducedIn))
            {
                return;
            }
            // ...
        }
    }
    // @end
}

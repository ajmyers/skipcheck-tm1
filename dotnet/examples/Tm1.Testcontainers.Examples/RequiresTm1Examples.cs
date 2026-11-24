// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;
using Xunit;

namespace Examples;

/// <summary>Examples for <c>[RequiresTm1Fact]</c> and the other <c>[Requires…Fact]</c>s, shown in the guide.</summary>
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
                    DimensionCreate
                        .Builder()
                        .Name(region)
                        .Hierarchies([
                            HierarchyCreate
                                .Builder()
                                .Name(region)
                                .Elements([
                                    ElementCreate.Builder().Name("North").Type(ElementType.Numeric).Build(),
                                    ElementCreate.Builder().Name("South").Type(ElementType.Numeric).Build(),
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

    // @start region = tm1-web
    public sealed class WebsheetTests(Tm1WebFixture web) : IClassFixture<Tm1WebFixture>
    {
        [RequiresTm1WebFact]
        public void OpensAWebsheet()
        {
            // http://localhost:54321/tm1web/, which lists the shared TM1 server
            Assert.EndsWith("/tm1web/", web.Tm1Web.Url);
            // ...
        }
    }

    // @end

    // @start region = paw
    public sealed class WorkspaceTests(PawFixture paw) : IClassFixture<PawFixture>
    {
        [RequiresPawFact]
        public void OpensPaw()
        {
            // http://localhost:54321/, which logs on to the shared TM1 server
            Assert.StartsWith("http://", paw.Paw.Url);
            // ...
        }
    }

    // @end

    // @start region = cognos
    public sealed class CamSecurityTests(CognosFixture cognos) : IClassFixture<CognosFixture>
    {
        [RequiresCognosFact]
        public async Task ReadersCanOnlyRead()
        {
            // No shared Cognos: each test starts its own, with the users it needs
            await using var container = new CognosContainer(cognos.Source).WithUser("viewer", "viewerpw", "Readers");
            await container.StartAsync();
            // ...
        }
    }
    // @end
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading.Tasks;
using Examples;
using Skipcheck.Tm1.Testcontainers;
using Skipcheck.Tm1.Testcontainers.Xunit;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Runs the walkthroughs' examples (ConnectWalkthrough and the rest) against IBM's SData sample, and
/// records what each step prints for the docs, which show it under the step's code (<see cref="ExampleOutput"/>).
/// Run the live tests after changing a walkthrough's example, and commit the recordings in api-site/example-output
/// with it. The walkthroughs are written for SData, so these start a server of their own with it rather than use
/// the shared, empty one, and are skipped without Docker or a TM1 source (a TM1_URL alone won't do). Every
/// language's tests do the same, so they record the same output. A port of the Java client's
/// WalkthroughExamplesTm1Test.</summary>
public sealed class WalkthroughExamplesLiveTests(WalkthroughExamplesLiveTests.Sdata sdata) : IClassFixture<WalkthroughExamplesLiveTests.Sdata>
{
    /// <summary>A TM1 with SData, and a client of it: started only when the tests run
    /// (<see cref="RequiresTm1FactAttribute"/>).</summary>
    public sealed class Sdata : IAsyncLifetime
    {
        private Tm1ServerContainer? _server;
        private Tm1Client? _tm1;

        public Tm1ServerContainer Server => _server ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);

        public Tm1Client Tm1 => _tm1 ?? throw new InvalidOperationException(RequiresTm1FactAttribute.NoSourceMessage);

        public async Task InitializeAsync()
        {
            if (new RequiresTm1FactAttribute().Skip is not null)
            {
                return;
            }
            _server = new Tm1ServerContainer(Tm1Source.Discover()!).WithSeed(Tm1Seed.Sdata);
            await _server.StartAsync();
            _tm1 = Tm1Client.Builder().BaseUrl(_server.RestUrl).BasicAuth(_server.Username, _server.Password).Build();
        }

        public async Task DisposeAsync()
        {
            if (_tm1 is not null)
            {
                await _tm1.DisposeAsync();
            }
            if (_server is not null)
            {
                await _server.DisposeAsync();
            }
        }
    }

    [RequiresTm1Fact]
    public async Task ConnectsAndListsCubes()
    {
        var tm1 = sdata.Tm1;
        using var output = new ExampleOutput("tm1-client", "ConnectWalkthrough");
        await output.RecordAsync("version", () => ConnectWalkthrough.PrintVersion(tm1));
        await output.RecordAsync("list-cubes", () => ConnectWalkthrough.ListCubes(tm1));
        Assert.Equal(["Connected to TM1 " + sdata.Server.Tm1Version], output.Output("version"));
        Assert.Contains("SalesCube", output.Output("list-cubes"));
        Assert.Contains("PriceCube", output.Output("list-cubes"));
    }

    [RequiresTm1Fact]
    public async Task ReadsAndWritesCells()
    {
        var tm1 = sdata.Tm1;
        using var output = new ExampleOutput("tm1-client", "CellsWalkthrough");
        await output.RecordAsync("read", () => CellsWalkthrough.Read(tm1));
        await output.RecordAsync("write", () => CellsWalkthrough.Write(tm1));
        await output.RecordAsync("read-back", () => CellsWalkthrough.ReadBack(tm1));
        Assert.Equal(3, output.Output("read").Count);
        Assert.Equal(["Budget for Jan: 150"], output.Output("read-back"));
    }

    [RequiresTm1Fact]
    public async Task RunsAProcessAndReadsItsResult()
    {
        var tm1 = sdata.Tm1;
        using var output = new ExampleOutput("tm1-client", "ProcessWalkthrough");
        await output.RecordAsync("create", () => ProcessWalkthrough.Create(tm1));
        try
        {
            await output.RecordAsync("run", () => ProcessWalkthrough.Run(tm1));
            await output.RecordAsync("check", () => ProcessWalkthrough.Check(tm1));
            await output.RecordAsync("fail", () => ProcessWalkthrough.Fail(tm1));
        }
        finally
        {
            await output.RecordAsync("delete", () => ProcessWalkthrough.Delete(tm1));
        }
        Assert.Equal(["Status: CompletedSuccessfully"], output.Output("run"));
        Assert.NotEqual("Status: CompletedSuccessfully", output.Output("fail")[0]);
    }
}

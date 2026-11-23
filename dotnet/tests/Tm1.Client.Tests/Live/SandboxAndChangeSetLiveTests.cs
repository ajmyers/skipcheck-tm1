using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Sandboxes and change sets, on a cube of two dimensions of X and Y: Java's
/// SandboxAndChangeSetTm1Test.</summary>
public sealed class SandboxAndChangeSetLiveTests : IAsyncLifetime
{
    private readonly Tm1Client _tm1 = Live.Client();
    private readonly string _cube = Live.Name();
    private string A => _cube + "-a";
    private string B => _cube + "-b";

    public async Task InitializeAsync()
    {
        foreach (var d in new[] { A, B })
        {
            await LiveModel.DimensionAsync(_tm1, d, "X", "Y");
        }
        await _tm1.Cubes.CreateAsync(CubePatch.Builder().Name(_cube).BindDimensions(LiveModel.Dim(A), LiveModel.Dim(B)).Build());
    }

    public async Task DisposeAsync()
    {
        foreach (var s in await _tm1.Sandboxes.ListAsync().ToListAsync())
        {
            await LiveModel.Quietly(() => _tm1.Sandboxes.DeleteAsync(s.Name!));
        }
        await LiveModel.Quietly(() => _tm1.Cubes.DeleteAsync(_cube));
        await LiveModel.DeleteDimensionsAsync(_tm1, A, B);
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task KeepsSandboxWritesApartUntilPublished()
    {
        await _tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("S").Build());
        Assert.Contains("S", await _tm1.Sandboxes.ListAsync().Select(s => s.Name).ToListAsync());
        await _tm1.WithSandbox("S").Cells.UpdateAsync(_cube, Cell("X"), 5);
        Assert.Equal(5.0, await Value(_tm1.WithSandbox("S"), "X"));
        Assert.Equal(0.0, await Value(_tm1, "X")); // base data untouched
        await _tm1.Sandboxes.PublishAsync("S");
        Assert.Equal(5.0, await Value(_tm1, "X"));
    }

    [Tm1Fact]
    public async Task MergesDiscardsLoadsAndUnloads()
    {
        await _tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("S").Build());
        await _tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("T").Build());
        await _tm1.WithSandbox("S").Cells.UpdateAsync(_cube, Cell("X"), 7);
        await _tm1.Sandboxes.MergeAsync("S", "T");
        Assert.Equal(7.0, await Value(_tm1.WithSandbox("T"), "X"));
        await _tm1.Sandboxes.DiscardChangesAsync("S");
        Assert.Equal(0.0, await Value(_tm1.WithSandbox("S"), "X"));
        await _tm1.Sandboxes.UnloadAsync("T");
        await _tm1.Sandboxes.LoadAsync("T");
        Assert.True((await _tm1.Sandboxes.GetAsync("T")).IsLoaded);
        await _tm1.Sandboxes.DeleteAsync("T");
        Assert.DoesNotContain("T", await _tm1.Sandboxes.ListAsync().Select(s => s.Name).ToListAsync());
    }

    [Tm1Fact]
    public async Task UndoesTheWritesOfAChangeSetOnly()
    {
        string? id = null;
        await _tm1.ChangeSets.WithinAsync(
            async (Tm1Session session) =>
            {
                id = session.ChangeSet();
                await Tm1Client.Of(session).Cells.UpdateAsync(_cube, Cell("X"), 5);
            }
        );
        await _tm1.Cells.UpdateAsync(_cube, Cell("Y"), 7);
        Assert.Equal(5.0, await Value(_tm1, "X"));
        await _tm1.ChangeSets.UndoAsync(id!);
        Assert.Equal(0.0, await Value(_tm1, "X")); // in the change set: reverted
        Assert.Equal(7.0, await Value(_tm1, "Y")); // outside it: kept
    }

    [Tm1Fact]
    public async Task BeginsAndEndsAChangeSetByHand()
    {
        var id = await _tm1.ChangeSets.BeginAsync();
        Assert.False(string.IsNullOrWhiteSpace(id));
        await _tm1.WithChangeSet(id).Cells.UpdateAsync(_cube, Cell("Y"), 3);
        await _tm1.ChangeSets.EndAsync(id);
        await _tm1.ChangeSets.UndoAsync(id);
        Assert.Equal(0.0, await Value(_tm1, "Y"));
    }

    private Dictionary<string, string> Cell(string element) => new() { [A] = element, [B] = element };

    private Task<double> Value(Tm1Client client, string element) =>
        LiveModel.NumberAsync(client, $"SELECT {{[{B}].[{B}].[{element}]}} ON 0, {{[{A}].[{A}].[{element}]}} ON 1 FROM [{_cube}]");
}

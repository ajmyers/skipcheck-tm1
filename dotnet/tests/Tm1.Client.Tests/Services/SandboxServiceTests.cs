using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>SandboxService against a fake TM1: the sandboxes themselves and their actions.</summary>
public class SandboxServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesUpdatesAndDeletes()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "What if", IsLoaded = true });
        var sandbox = Assert.Single(await tm1.Sandboxes.ListAsync(ODataQuery.None().Select("Name")).ToListAsync());
        Assert.Equal(("What if", true), (sandbox.Name, sandbox.IsLoaded));
        Assert.Equal("GET Sandboxes?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "What if" };
        Assert.Equal("What if", (await tm1.Sandboxes.GetAsync("What if")).Name);
        Assert.Equal("Sandboxes('What%20if')", _tm1.Last.Target);
        await tm1.Sandboxes.CreateAsync(SandboxPatch.Builder().Name("What if").Build());
        Assert.Equal(("POST", "Sandboxes", """{"Name":"What if"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Sandboxes.UpdateAsync("What if", SandboxPatch.Builder().IncludeInSandboxDimension(false).Build());
        Assert.Equal(("PATCH", "Sandboxes('What%20if')", """{"IncludeInSandboxDimension":false}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.Sandboxes.DeleteAsync("What if");
        Assert.Equal("DELETE Sandboxes('What%20if')", $"{_tm1.Last.Method} {_tm1.Last.Target}");
        await Assert.ThrowsAsync<ArgumentNullException>(() => tm1.Sandboxes.CreateAsync(null!));
    }

    [Fact]
    public async Task Actions()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => null;
        foreach (
            var (call, action) in new (Func<Task>, string)[]
            {
                (() => tm1.Sandboxes.LoadAsync("s"), "Load"),
                (() => tm1.Sandboxes.UnloadAsync("s"), "Unload"),
                (() => tm1.Sandboxes.DiscardChangesAsync("s"), "DiscardChanges"),
                (() => tm1.Sandboxes.PublishAsync("s"), "Publish"),
            }
        )
        {
            await call();
            Assert.Equal(("POST", $"Sandboxes('s')/tm1.{action}", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        }
    }

    [Fact]
    public async Task PublishesAndMergesWithOptions()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => null;
        await tm1.Sandboxes.PublishAsync("s", ConflictResolution.UsingSource, true, false);
        Assert.Equal(
            ("Sandboxes('s')/tm1.Publish", """{"ConflictResolution":"UsingSource","AllowQueueing":true,"CleanAfter":false}"""),
            (_tm1.Last.Target, _tm1.Last.Text)
        );

        await tm1.Sandboxes.MergeAsync("s", "t");
        Assert.Equal(("Sandboxes('s')/tm1.Merge", """{"Target@odata.bind":"Sandboxes('t')"}"""), (_tm1.Last.Target, _tm1.Last.Text));
        await tm1.Sandboxes.MergeAsync("s", "t", ConflictResolution.UsingTarget, false, true);
        Assert.Equal("""{"Target@odata.bind":"Sandboxes('t')","ConflictResolution":"UsingTarget","AllowQueueing":false,"CleanAfter":true}""", _tm1.Last.Text);
    }
}

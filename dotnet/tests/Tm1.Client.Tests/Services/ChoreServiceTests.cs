// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

#pragma warning disable CS0618 // the deprecated Activate, Deactivate and SetServerLocalStartTime are tested too

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ChoreService against a fake TM1: each method sends what the Java client's does.</summary>
public class ChoreServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesUpdatesAndDeletes()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Nightly Load" });
        var tm1 = _tm1.Client();
        Assert.Equal("Nightly Load", Assert.Single(await tm1.Chores.ListAsync(ODataQuery.None().Top(1)).ToListAsync()).Name);
        Assert.Equal("GET Chores?$top=1", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Nightly Load", ["@odata.etag"] = "W/\"3\"" };
        Assert.Equal("W/\"3\"", (await tm1.Chores.GetAsync("Nightly Load", ODataQuery.None().Expand("Tasks"))).ETag);
        Assert.Equal("Chores('Nightly%20Load')?$expand=Tasks", _tm1.Last.Target);

        var task = ChoreTaskPatch
            .Builder()
            .BindProcess(ODataPath.Of("Processes").Key("Load Sales"))
            .Parameters([ChoreTaskParameterPatch.Builder().Name("pYear").Value(2026).Build()])
            .Build();
        await tm1.Chores.CreateAsync(ChorePatch.Builder().Name("Nightly Load").Active(false).Tasks([task]).Build());
        Assert.Equal(("POST", "Chores"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.Equal(
            """{"Name":"Nightly Load","Active":false,"Tasks":[{"Process@odata.bind":"Processes('Load%20Sales')","Parameters":[{"Name":"pYear","Value":2026}]}]}""",
            _tm1.Last.Text
        );

        _tm1.Respond = _ => null;
        await tm1.Chores.UpdateAsync("Nightly Load", ChorePatch.Builder().Active(true).Build());
        Assert.Equal(("PATCH", """{"Active":true}"""), (_tm1.Last.Method, _tm1.Last.Text));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Chores.UpdateAsync("Nightly Load", ChorePatch.Builder().Active(true).Build(), "W/\"3\"");
        Assert.Equal("W/\"3\"", _tm1.Last.Headers["if-match"]);
        await tm1.Chores.DeleteAsync("Nightly Load");
        Assert.Equal(("DELETE", "Chores('Nightly%20Load')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task TasksByStep()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Step = 0 }, new { Step = 1 });
        var tm1 = _tm1.Client();
        Assert.Equal([0L, 1L], (await tm1.Chores.TasksAsync("c").ToListAsync()).Select(t => t.Step!.Value));
        Assert.Equal("Chores('c')/Tasks", _tm1.Last.Target);

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Step"] = 2 };
        var added = await tm1.Chores.AddTaskAsync("c", ChoreTaskPatch.Builder().BindProcess(ODataPath.Of("Processes").Key("p")).Build());
        Assert.Equal(2L, added.Step);
        Assert.Equal(("POST", "Chores('c')/Tasks", """{"Process@odata.bind":"Processes('p')"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Chores.UpdateTaskAsync("c", 2, ChoreTaskPatch.Builder().Parameters([]).Build());
        Assert.Equal(("PATCH", "Chores('c')/Tasks(2)"), (_tm1.Last.Method, _tm1.Last.Target));
        await tm1.Chores.DeleteTaskAsync("c", 2);
        Assert.Equal(("DELETE", "Chores('c')/Tasks(2)"), (_tm1.Last.Method, _tm1.Last.Target));

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "p" };
        Assert.Equal("p", (await tm1.Chores.TaskProcessAsync("c", 0, ODataQuery.None().Select("Name"))).Name);
        Assert.Equal("Chores('c')/Tasks(0)/Process?$select=Name", _tm1.Last.Target);
        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "c" };
        Assert.Equal("c", (await tm1.Chores.TaskChoreAsync("c", 0)).Name);
        Assert.Equal("Chores('c')/Tasks(0)/Chore", _tm1.Last.Target);
    }

    [Fact]
    public async Task Actions()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        foreach (
            var (call, action) in new (Func<Task>, string)[]
            {
                (() => tm1.Chores.ExecuteAsync("c"), "Execute"),
                (() => tm1.Chores.ActivateAsync("c"), "Activate"),
                (() => tm1.Chores.DeactivateAsync("c"), "Deactivate"),
            }
        )
        {
            await call();
            Assert.Equal(("POST", $"Chores('c')/tm1.{action}", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        }

        await tm1.Chores.SetServerLocalStartTimeAsync("c", new DateOnly(2026, 1, 2), new TimeOnly(2, 0));
        Assert.Equal(("Chores('c')/tm1.SetServerLocalStartTime", """{"StartDate":"2026-01-02","StartTime":"02:00"}"""), (_tm1.Last.Target, _tm1.Last.Text));
        await tm1.Chores.SetServerLocalStartTimeAsync("c", new DateOnly(2026, 1, 2), new TimeOnly(2, 0, 30));
        Assert.Equal("""{"StartDate":"2026-01-02","StartTime":"02:00:30"}""", _tm1.Last.Text);
    }
}

// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ChangeSetService against a fake TM1: begin, end and undo, and <c>WithinAsync</c>, which
/// ends the change set however the work ends.</summary>
public class ChangeSetServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    public ChangeSetServiceTests()
    {
        _tm1.Respond = r =>
            r.Target switch
            {
                "BeginChangeSet" => new Dictionary<string, object?> { ["value"] = "7" },
                "EndChangeSet" => new Dictionary<string, object?> { ["value"] = 2 },
                _ => null,
            };
    }

    [Fact]
    public async Task BeginsEndsAndUndoes()
    {
        var tm1 = _tm1.Client();
        Assert.Equal("7", await tm1.ChangeSets.BeginAsync());
        Assert.Equal(("POST", "BeginChangeSet", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        Assert.Equal(2, await tm1.ChangeSets.EndAsync("7"));
        Assert.Equal(("EndChangeSet", """{"ChangeSetID":"7"}"""), (_tm1.Last.Target, _tm1.Last.Text));
        await tm1.ChangeSets.UndoAsync("7");
        Assert.Equal(("UndoChangeSet", """{"ChangeSetID":"7"}"""), (_tm1.Last.Target, _tm1.Last.Text));
    }

    [Fact]
    public async Task WorksWithinAChangeSet()
    {
        var tm1 = _tm1.Client();
        var result = await tm1.ChangeSets.WithinAsync(async session =>
        {
            await Tm1Client.Of(session).Cells.UpdateAsync("Sales", new Dictionary<string, string> { ["Region"] = "North" }, 1);
            return "done";
        });
        Assert.Equal("done", result);
        Assert.Equal(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"], _tm1.Requests.Select(r => r.Target));

        _tm1.Requests.Clear();
        await tm1.ChangeSets.WithinAsync(session =>
            Tm1Client.Of(session).Cells.UpdateAsync("Sales", new Dictionary<string, string> { ["Region"] = "North" }, 2)
        );
        Assert.Equal(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"], _tm1.Requests.Select(r => r.Target));
    }

    [Fact]
    public async Task EndsTheChangeSetWhenTheWorkFails()
    {
        Func<Tm1Session, Task<int>> work = _ => throw new InvalidOperationException("work");
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => _tm1.Client().ChangeSets.WithinAsync(work));
        Assert.Equal("work", e.Message);
        Assert.Equal(["BeginChangeSet", "EndChangeSet"], _tm1.Requests.Select(r => r.Target));
    }

    [Fact]
    public async Task DoesNoWorkIfTheChangeSetCantBegin()
    {
        // Java: doesNoWorkIfTheChangeSetCantBegin
        _tm1.Respond = _ =>
            FakeTm1.Json(
                new Dictionary<string, object?>
                {
                    ["error"] = new Dictionary<string, object?> { ["code"] = "1", ["message"] = "down" },
                },
                System.Net.HttpStatusCode.InternalServerError
            );
        var ran = false;
        Func<Tm1Session, Task<int>> work = _ =>
        {
            ran = true;
            return Task.FromResult(1);
        };
        await Assert.ThrowsAsync<Skipcheck.Tm1.Client.Errors.Tm1ServerException>(() => _tm1.Client().ChangeSets.WithinAsync(work));
        Assert.False(ran);
        Assert.Equal(["BeginChangeSet"], _tm1.Requests.Select(r => r.Target));
    }

    [Fact]
    public async Task StreamsWithinAChangeSetAndEndsItHoweverTheStreamEnds()
    {
        var tm1 = _tm1.Client();
        Assert.Equal([1, 2, 3], await tm1.ChangeSets.WithinAsync(Numbers).ToListAsync());
        Assert.Equal(["BeginChangeSet", "Cubes('Sales')/tm1.Update?!ChangeSet=7", "EndChangeSet"], _tm1.Requests.Select(r => r.Target));

        _tm1.Requests.Clear();
        await foreach (var _ in tm1.ChangeSets.WithinAsync(Numbers))
        {
            break;
        }
        Assert.Equal("EndChangeSet", _tm1.Last.Target);
    }

    private static async IAsyncEnumerable<int> Numbers(Tm1Session session)
    {
        await Tm1Client.Of(session).Cells.UpdateAsync("Sales", new Dictionary<string, string> { ["Region"] = "North" }, 1);
        foreach (var n in new[] { 1, 2, 3 })
        {
            yield return n;
        }
    }
}

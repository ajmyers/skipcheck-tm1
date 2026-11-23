using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>LogService against a fake TM1: each method sends what the Java client's does, and the
/// tails poll the delta link, skipping what they've yielded.</summary>
public class LogServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    private string Sent => $"{_tm1.Last.Method} {_tm1.Last.Target}";

    [Fact]
    public async Task ReadsTheMessageLog()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { ID = 1, Message = "Login Success" });
        Assert.Equal("Login Success", Assert.Single(await tm1.Logs.MessageLogEntriesAsync(ODataQuery.None().Top(1)).ToListAsync()).Message);
        Assert.Equal("GET MessageLogEntries?$top=1", Sent);
        await tm1.Logs.MessageLogAsync(true, ODataQuery.None().Top(3)).ToListAsync();
        Assert.Equal("GET MessageLog(Reverse=true)?$top=3", Sent);
    }

    [Fact]
    public async Task ReadsTheTransactionAndAuditLogs()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Cube = "Sales" });
        Assert.Equal("Sales", Assert.Single(await tm1.Logs.TransactionLogEntriesAsync().ToListAsync()).Cube);
        Assert.Equal("GET TransactionLogEntries", Sent);
        await tm1.Logs.TransactionLogAsync(true, false).ToListAsync();
        Assert.Equal("GET TransactionLog(Reverse=true,IgnoreStatusMessages=false)", Sent);
        _tm1.Respond = _ => FakeTm1.Collection();
        await tm1.Logs.AuditLogEntriesAsync(ODataQuery.None().Expand("AuditDetails")).ToListAsync();
        Assert.Equal("GET AuditLogEntries?$expand=AuditDetails", Sent);
    }

    [Fact]
    public async Task ReadsErrorLogs()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = _ => FakeTm1.Collection(new { Filename = "a.log" });
        Assert.Equal("a.log", Assert.Single(await tm1.Logs.ErrorLogFilesAsync().ToListAsync()).Filename);
        Assert.Equal("GET ErrorLogFiles", Sent);
        _tm1.Respond = _ => new { Filename = "a.log" };
        Assert.Equal("a.log", (await tm1.Logs.ErrorLogFileAsync("a.log")).Filename);
        Assert.Equal("GET ErrorLogFiles('a.log')", Sent);

        // TM1 (11.8) starts the content with a byte order mark, which is dropped
        _tm1.Respond = _ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Encoding.UTF8.GetBytes("﻿Line 1")) };
        Assert.Equal("Line 1", await tm1.Logs.ErrorLogContentAsync("a.log"));
        Assert.Equal("GET ErrorLogFiles('a.log')/Content", Sent);
    }

    [Fact]
    public async Task TailsALogSkippingWhatItHasYielded()
    {
        // TM1 (11.8) answers the delta link with every entry since it was made, each time
        var polls = new Queue<object>([
            FakeTm1.Collection(new { Message = "a" }),
            FakeTm1.Collection(new { Message = "a" }, new { Message = "b" }, new { Message = "c" }),
        ]);
        _tm1.Respond = r =>
            r.Target == "TailMessageLog()" ? new Dictionary<string, object?> { ["value"] = "MessageLogEntries/!delta('0[4096]38@11!19')" } : polls.Dequeue();
        var entries = await _tm1.Client().Logs.TailMessageLogAsync(TimeSpan.FromMilliseconds(1)).Take(3).ToListAsync();
        Assert.Equal(["a", "b", "c"], entries.Select(e => e.Message));
        Assert.Single(_tm1.Requests, r => r.Target == "TailMessageLog()");
        Assert.Equal("GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')", Sent);
    }

    [Fact]
    public async Task TailsTheTransactionAndAuditLogsUntilCancelled()
    {
        var tm1 = _tm1.Client();
        _tm1.Respond = r =>
            r.Target.StartsWith("Tail", StringComparison.Ordinal)
                ? new Dictionary<string, object?> { ["value"] = "TransactionLogEntries/!delta('1')" }
                : FakeTm1.Collection(new { Cube = "Sales" });
        Assert.Equal("Sales", (await tm1.Logs.TailTransactionLogAsync(TimeSpan.FromMilliseconds(1)).FirstAsync()).Cube);
        Assert.Contains(_tm1.Requests, r => r.Target == "TailTransactionLog()");

        using var cancel = new CancellationTokenSource();
        var seen = new List<AuditLogEntry>();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
        {
            await foreach (var entry in tm1.Logs.TailAuditLogAsync(TimeSpan.FromMilliseconds(1), cancel.Token))
            {
                seen.Add(entry);
                cancel.Cancel();
            }
        });
        Assert.Single(seen);
        Assert.Contains(_tm1.Requests, r => r.Target == "TailAuditLog()");
    }
}

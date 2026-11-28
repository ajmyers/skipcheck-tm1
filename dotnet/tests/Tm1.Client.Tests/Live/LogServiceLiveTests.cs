// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>LogService against a real TM1: a port of the Java client's LogServiceTm1Test.</summary>
public class LogServiceLiveTests
{
    [Tm1Fact]
    public async Task ReadsTheMessageLog()
    {
        await using var tm1 = Live.Client();
        if (await ServerAssumptions.IsV12Async(tm1))
        {
            return; // TM1 v12 does not populate MessageLogEntries (xUnit 2 can't skip once started)
        }
        var first = await tm1.Logs.MessageLogEntriesAsync(ODataQuery.None().Top(20)).ToListAsync();
        Assert.NotEmpty(first);
        // An entry without a time reads as null rather than failing
        Assert.Contains(first, e => e.TimeStamp is null);
        var again = await tm1.Logs.MessageLogEntriesAsync(ODataQuery.None().Top(1)).FirstAsync();
        Assert.Equal(first[0].Message, again.Message);
        Assert.NotEqual(first[0].Id, again.Id); // an ID numbers the entry within its response
        var newest = await tm1.Logs.MessageLogAsync(true, ODataQuery.None().Top(3)).ToListAsync();
        Assert.Equal(3, newest.Count);
        Assert.True(newest[0].TimeStamp >= newest[2].TimeStamp);
    }

    [Tm1Fact]
    public async Task FollowsTheMessageLogWithoutRepeating()
    {
        await using var tm1 = Live.Client();
        if (await ServerAssumptions.IsV12Async(tm1))
        {
            return; // TM1 v12 does not populate MessageLogEntries
        }
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(6));
        var logins = Task.Run(async () =>
        {
            for (var i = 0; i < 2; i++)
            {
                await Task.Delay(1500);
                await using var other = Live.Client();
                await other.Cubes.ListAsync(ODataQuery.None().Select("Name")).ToListAsync();
            }
        });
        var seen = new List<MessageLogEntry>();
        try
        {
            await foreach (var entry in tm1.Logs.TailMessageLogAsync(TimeSpan.FromMilliseconds(300), stop.Token))
            {
                seen.Add(entry);
            }
        }
        catch (OperationCanceledException)
        {
            // the watch is over
        }
        await logins;
        Assert.True(seen.Count(e => e.Message?.Contains("Login Success", StringComparison.Ordinal) == true) >= 2);
        // No entry twice, though each poll returns them all again
        Assert.Empty(seen.GroupBy(e => $"{e.TimeStamp:O} {e.ThreadID} {e.SessionID} {e.Message}").Where(g => g.Count() > 1).Select(g => g.Key));
    }

    [Tm1Fact]
    public async Task ReadsAPasswordsHashThatIsntUtf8AsReplacementCharacters()
    {
        await using var tm1 = Live.Client();
        if (await ServerAssumptions.IsV12Async(tm1))
        {
            return; // TM1 v12 stores no local passwords and does not populate legacy TransactionLogEntries by default
        }
        var user = Live.Name();
        await tm1.Users.CreateAsync(UserCreate.Builder().Name(user).Password("pw").Build());
        try
        {
            var newest = await tm1.Logs.TransactionLogAsync(true, true, ODataQuery.None().Top(50)).ToListAsync();
            // The password's hash, which TM1 sends as bytes that aren't UTF-8
            var entry = Assert.Single(newest, e => e.Cube == "}ClientProperties" && e.Tuple!.Contains("PASSWORD") && e.Tuple!.Contains(user));
            Assert.Contains((char)0xFFFD, entry.NewValue!.ToString()!);
        }
        finally
        {
            await tm1.Users.DeleteAsync(user);
        }
    }

    [Tm1Fact]
    public async Task ReadsTheTransactionAndAuditLogs()
    {
        await using var tm1 = Live.Client();
        if (await ServerAssumptions.IsV12Async(tm1))
        {
            return; // TM1 v12 leaves TransactionLogEntries empty unless LegacyTransactionLog is set (xUnit 2 can't skip once started)
        }
        Assert.NotEmpty(await tm1.Logs.TransactionLogEntriesAsync(ODataQuery.None().Top(5)).ToListAsync());
        Assert.All(await tm1.Logs.TransactionLogAsync(true, true, ODataQuery.None().Top(5)).ToListAsync(), e => Assert.Null(e.StatusMessage));
        // The audit log is off on the test server
        Assert.Empty(await tm1.Logs.AuditLogEntriesAsync(ODataQuery.None().Expand("AuditDetails").Top(5)).ToListAsync());
        await Watch(tm1.Logs.TailTransactionLogAsync, TimeSpan.FromSeconds(1));
        Assert.Empty(await Watch(tm1.Logs.TailAuditLogAsync, TimeSpan.FromSeconds(1)));
    }

    [Tm1Fact]
    public async Task ReadsAProcessErrorLog()
    {
        await using var tm1 = Live.Client();
        var v12 = await ServerAssumptions.IsV12Async(tm1);
        var process = Live.Name();
        await tm1.Processes.CreateAsync(ProcessCreate.Builder().Name(process).PrologProcedure("ProcessError;\n").Build());
        try
        {
            var filename = (await tm1.Processes.ExecuteWithReturnAsync(process, null, ODataQuery.None().Expand("ErrorLogFile"))).ErrorLogFile!.Filename!;
            Assert.Contains(await tm1.Logs.ErrorLogFilesAsync().ToListAsync(), f => f.Filename == filename);
            Assert.Equal(filename, (await tm1.Logs.ErrorLogFileAsync(filename)).Filename);
            var content = await tm1.Logs.ErrorLogContentAsync(filename);
            Assert.StartsWith(v12 ? "{" : "Error:", content); // without the byte order mark (JSONL on v12)
            Assert.Contains("ProcessError", content);
        }
        finally
        {
            await tm1.Processes.DeleteAsync(process);
        }
    }

    private static async Task<List<T>> Watch<T>(Func<TimeSpan, CancellationToken, IAsyncEnumerable<T>> tail, TimeSpan for_)
    {
        using var stop = new CancellationTokenSource(for_);
        var seen = new List<T>();
        try
        {
            await foreach (var entry in tail(TimeSpan.FromMilliseconds(300), stop.Token))
            {
                seen.Add(entry);
            }
        }
        catch (OperationCanceledException)
        {
            // the watch is over
        }
        return seen;
    }
}

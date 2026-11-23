using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>ServerService and LogService against a real TM1: ports of the Java client's
/// ServerServiceTm1Test and LogServiceTm1Test.</summary>
public class ServerServicesLiveTests
{
    [Tm1Fact]
    public async Task ReadsTheServerAndItsConfigurations()
    {
        await using var tm1 = Live.Client();
        var read = await tm1.Server.ServerAsync();
        Assert.False(string.IsNullOrEmpty(read.ProductVersion));
        Assert.Null(read.ETag); // none for the Server
        var fixedSettings = await tm1.Server.StaticConfigurationAsync();
        var active = await tm1.Server.ActiveConfigurationAsync();
        Assert.Equal(read.Name, fixedSettings.ServerName);
        Assert.Equal(read.Name, active.ServerName);
#pragma warning disable CS0618
        Assert.Equal(read.Name, (await tm1.Server.ConfigurationAsync()).ServerName);
#pragma warning restore CS0618
        await tm1.Server.UpdateStaticConfigurationAsync(ServerSettingsPatch.Builder().ServerName(fixedSettings.ServerName).Build());
        // The active configuration is read only
        await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
            tm1.Session.ExecuteAsync(Tm1Request.Patch(ODataPath.Of("ActiveConfiguration"), ServerSettingsPatch.Builder().ServerName(active.ServerName).Build()))
        );
    }

    [Tm1Fact]
    public async Task ReadsAndSetsLoggerLevels()
    {
        await using var tm1 = Live.Client();
        var loggers = await tm1.Server.LoggersAsync().ToListAsync();
        Assert.NotEmpty(loggers);
        // Not the first, TM1's root logger: at DEBUG it floods the message log of the server every
        // test shares, with lines alike to the millisecond
        var first = loggers.First(l => l.Name == "TM1.Blob");
        Assert.Equal(first.Level, (await tm1.Server.LoggerAsync(first.Name!)).Level);
        var other = first.Level == LogLevel.Debug ? LogLevel.Info : LogLevel.Debug;
        await tm1.Server.SetLoggerLevelAsync(first.Name!, other);
        try
        {
            Assert.Equal(other, (await tm1.Server.LoggerAsync(first.Name!)).Level);
        }
        finally
        {
            await tm1.Server.SetLoggerLevelAsync(first.Name!, first.Level!.Value);
        }
    }

    [Tm1Fact]
    public async Task ReadsTheMessageLog()
    {
        await using var tm1 = Live.Client();
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
        var distinct = seen.Select(e => $"{e.TimeStamp:O} {e.ThreadID} {e.SessionID} {e.Message}").ToHashSet();
        Assert.Equal(seen.Count, distinct.Count); // no entry twice, though each poll returns them all again
    }

    [Tm1Fact]
    public async Task ReadsAPasswordsHashThatIsntUtf8AsReplacementCharacters()
    {
        await using var tm1 = Live.Client();
        var user = Live.Name();
        await tm1.Users.CreateAsync(UserPatch.Builder().Name(user).Password("pw").Build());
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
        var process = Live.Name();
        await tm1.Processes.CreateAsync(ProcessPatch.Builder().Name(process).PrologProcedure("ProcessError;\n").Build());
        try
        {
            var filename = (await tm1.Processes.ExecuteWithReturnAsync(process, null, ODataQuery.None().Expand("ErrorLogFile"))).ErrorLogFile!.Filename!;
            Assert.Contains(await tm1.Logs.ErrorLogFilesAsync().ToListAsync(), f => f.Filename == filename);
            Assert.Equal(filename, (await tm1.Logs.ErrorLogFileAsync(filename)).Filename);
            var content = await tm1.Logs.ErrorLogContentAsync(filename);
            Assert.StartsWith("Error:", content); // without the byte order mark
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

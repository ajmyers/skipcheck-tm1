// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using ServerThread = Skipcheck.Tm1.Client.Model.Thread;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>SessionService and ThreadService against a real TM1, with a second client to look at,
/// close and interrupt: Java's <c>SessionAndThreadTm1Test</c>.</summary>
public sealed class SessionAndThreadLiveTests
{
    [Tm1Fact]
    public async Task ReadsSessionsTheirUsersAndThreadsAndClosesAnother()
    {
        await using var tm1 = Live.Client();
        var label = Live.Name();
        await using var other = OtherClient.Build(b => b.SessionContext(label));
        await other.ConnectAsync();
        var id = (await other.Sessions.ActiveSessionAsync()).Id!.Value;
        Assert.NotEqual(id, (await tm1.Sessions.ActiveSessionAsync()).Id); // each client its own
        Assert.Contains(id, await tm1.Sessions.ListAsync().Select(s => s.Id).ToListAsync());
        Assert.Equal(label, (await tm1.Sessions.GetAsync(id)).Context);
        Assert.Equal(Live.Target().User, (await tm1.Sessions.UserAsync(id)).Name, ignoreCase: true);
        Assert.NotNull(await tm1.Sessions.ThreadsAsync(id).ToListAsync());
        Assert.Contains(id, await tm1.Users.SessionsAsync(Live.Target().User).Select(s => s.Id).ToListAsync());

        await tm1.Sessions.CloseAsync(id);
        // The other client logs in again, as a new session
        Assert.NotEqual(id, (await other.Sessions.ActiveSessionAsync()).Id);
    }

    [Tm1Fact]
    public async Task CancelsARunningProcessThroughItsThread()
    {
        await using var tm1 = Live.Client();
        var process = Live.Name();
        await tm1.Processes.CreateAsync(ProcessPatch.Builder().Name(process).PrologProcedure("i = 0;\nWhile(i < 100000000);\n  i = i + 1;\nEnd;\n").Build());
        var runner = OtherClient.Build();
        var run = runner.Processes.ExecuteWithReturnAsync(process);
        try
        {
            ServerThread? running = null;
            for (var i = 0; i < 100 && running is null; i++)
            {
                running = (await tm1.Threads.ListAsync().ToListAsync()).FirstOrDefault(t =>
                    t.Function is { } f && f.Contains($"Processes('{process}')/tm1.ExecuteWithReturn", StringComparison.Ordinal)
                );
                if (running is null)
                {
                    await Task.Delay(100);
                }
            }
            Assert.NotNull(running); // the process's thread
            Assert.Equal("", running.ObjectName); // a process run through REST shows as its request
            Assert.Contains(process, (await tm1.Threads.GetAsync(running.Id!.Value)).Function);
            Assert.NotNull((await tm1.Threads.SessionAsync(running.Id.Value)).Id);

            await tm1.Threads.CancelAsync(running.Id.Value);
            // The run's request fails, rather than reporting Aborted
            var e = await Assert.ThrowsAsync<Tm1ServerException>(() => run.WaitAsync(TimeSpan.FromSeconds(60)));
            Assert.Contains("Cancel", e.Message, StringComparison.Ordinal);
        }
        finally
        {
            try
            {
                await run.WaitAsync(TimeSpan.FromSeconds(120));
            }
            catch (Exception)
            {
                // Cancelled, as tested
            }
            try
            {
                await runner.DisposeAsync();
            }
            catch (Tm1Exception)
            {
                // Its session may be gone
            }
            await tm1.Processes.DeleteAsync(process);
        }
    }
}

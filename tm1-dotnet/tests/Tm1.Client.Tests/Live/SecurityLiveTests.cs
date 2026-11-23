using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Services;
using ServerThread = AjMyers.Tm1.Client.Model.Thread;

namespace AjMyers.Tm1.Client.Tests.Live;

/// <summary>Clients beside <see cref="Live.Client"/>, logging in to the same TM1: as another session
/// (as Live.Client does), or as another user (with TM1's own security).</summary>
internal static class OtherClient
{
    public static Tm1Client Build(Func<Tm1ClientBuilder, Tm1ClientBuilder>? configure = null, string? user = null, string? password = null)
    {
        return (configure ?? (b => b))(Live.Builder(user, password)).Build();
    }
}

/// <summary>UserService and GroupService against a real TM1, on a user and two groups of their own:
/// Java's <c>UserAndGroupTm1Test</c>.</summary>
public sealed class UserAndGroupLiveTests : IAsyncLifetime
{
    private Tm1Client _tm1 = null!;
    private readonly string _user;
    private readonly string _g1;
    private readonly string _g2;

    public UserAndGroupLiveTests()
    {
        var id = Guid.NewGuid().ToString("N")[..8];
        (_user, _g1, _g2) = ("tm1net_test_" + id, "tm1net_test_g1_" + id, "tm1net_test_g2_" + id);
    }

    public async Task InitializeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        _tm1 = Live.Client();
        await _tm1.Groups.CreateAsync(_g1);
        await _tm1.Groups.CreateAsync(_g2);
        await _tm1.Users.CreateAsync(UserPatch.Builder().Name(_user).Password("pw").BindGroups(ODataPath.Of("Groups").Key(_g1)).Build());
    }

    public async Task DisposeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        foreach (var delete in new Func<Task>[] { () => _tm1.Users.DeleteAsync(_user), () => _tm1.Groups.DeleteAsync(_g1), () => _tm1.Groups.DeleteAsync(_g2) })
        {
            try
            {
                await delete();
            }
            catch (Tm1Exception)
            {
                // Already gone
            }
        }
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesAUser()
    {
        var read = await _tm1.Users.GetAsync(_user);
        Assert.Equal(UserType.User, read.Type);
        // Names match ignoring case and spaces
        await _tm1.Users.GetAsync(_user.ToUpperInvariant().Replace("_", " _ ", StringComparison.Ordinal));
        Assert.Null(read.ETag); // TM1 sends none for a user
        await _tm1.Users.UpdateAsync(_user, UserPatch.Builder().FriendlyName("Friend").Build());
        Assert.Equal("Friend", (await _tm1.Users.GetAsync(_user)).FriendlyName);
        Assert.Equal(Live.Target().User, (await _tm1.Users.ActiveUserAsync()).Name, ignoreCase: true);
        await _tm1.Users.DeleteAsync(_user);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.Users.GetAsync(_user));
    }

    [Tm1Fact]
    public async Task SetsGroupsWholeAndListsMembersFromBothSides()
    {
        Assert.Equal([_g1], await Groups());
        await _tm1.Users.SetGroupsAsync(_user, [_g1, _g2]);
        Assert.Equal(new[] { _g1, _g2 }.Order(), (await Groups()).Order());
        await _tm1.Users.SetGroupsAsync(_user, [_g2]);
        Assert.Equal([_g2], await Groups()); // replaced
        Assert.Equal([_user], await _tm1.Groups.UsersAsync(_g2).Select(u => u.Name).ToListAsync());
        Assert.Equal(_g1, (await _tm1.Groups.GetAsync(_g1)).Name); // left, not deleted
        var e = await Assert.ThrowsAsync<ArgumentException>(() =>
            _tm1.Users.UpdateAsync(_user, UserPatch.Builder().BindGroups(ODataPath.Of("Groups").Key(_g1)).Build())
        );
        Assert.Contains("SetGroups", e.Message, StringComparison.Ordinal);
    }

    [Tm1Fact]
    public async Task DeletesTheGroupItselfOnAReferenceDelete()
    {
        // The call UserService never makes: OData's "remove the link" deletes the group
        await _tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Users").Key(_user).Nav("Groups").Key(_g1).Ref()));
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.Groups.GetAsync(_g1));
    }

    [Tm1Fact]
    public async Task DisconnectsAUser()
    {
        await using var asUser = OtherClient.Build(user: _user, password: "pw");
        await asUser.ConnectAsync();
        Assert.True((await _tm1.Users.GetAsync(_user)).IsActive);
        await _tm1.Users.DisconnectAsync(_user);
        Assert.False((await _tm1.Users.GetAsync(_user)).IsActive);
    }

    private async Task<List<string?>> Groups() => await _tm1.Users.GroupsAsync(_user).Select(g => g.Name).ToListAsync();
}

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

/// <summary>OidcKeyService and SqlDataSourceService against a real TM1: the test server checks no
/// OIDC tokens and its host has no ODBC sources, so both are empty; the test pins how TM1 says so.
/// Java's <c>OidcKeyAndSqlDataSourceTm1Test</c>.</summary>
public sealed class OidcKeyAndSqlDataSourceLiveTests
{
    [Tm1Fact]
    public async Task ReadsAndClearsTheOidcKeyCache()
    {
        await using var tm1 = Live.Client();
        Assert.Empty(await tm1.OidcKeys.KeysAsync().ToListAsync());
        await tm1.OidcKeys.RemoveAsync([]);
        // TM1 answers 400 ObjectNotFound, read as not found
        var e = await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.OidcKeys.RemoveAsync(["not-cached"]));
        Assert.Contains("ObjectNotFound", e.Message, StringComparison.Ordinal);
    }

    [Tm1Fact]
    public async Task ListsTheHostsOdbcSources()
    {
        await using var tm1 = Live.Client();
        Assert.Empty(await tm1.SqlDataSources.ListAsync().ToListAsync());
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => tm1.SqlDataSources.GetAsync("none"));
    }
}
